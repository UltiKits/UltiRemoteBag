package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.commands.BagCommand;
import com.ultikits.plugins.remotebag.entity.RemoteBagEditClaim;
import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers;
import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.Server;
import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.Window;
import com.ultikits.plugins.remotebag.testsupport.StorageFaults;

import mc.obliviate.inventory.InventoryAPI;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.count;
import static com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.pageWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Every storage call of the edit claim and the page save can fail in three ways -- it is refused, it throws, or it
 * never returns -- and each has its decided answer (UltiKits/UltiRemoteBag#54, gate-1 findings F1-F4 and F8 of
 * plan 17-84; maintainer decisions of 2026-10-06).
 *
 * <h2>The rules under test</h2>
 * <ul>
 *   <li><b>F1.</b> A renewal that throws, or does not return within its deadline, turns the holder's window
 *       read-only at once; one hung renewal does not hold up the renewal of other claims; and a window whose claim
 *       has not been confirmed for longer than the renewal schedule allows is read-only even if the renewal task
 *       itself is stuck. What the window shows at that moment is kept and written as soon as the claim is
 *       confirmed again; nothing is given back while that write can still land.</li>
 *   <li><b>F2.</b> A save that throws or does not return keeps the claim, keeps the page's content in memory and
 *       retries the same conditional write in the background until it lands; meanwhile the page is read-only
 *       everywhere, and a reopen on this server shows the kept content. Only a write the database refuses (the
 *       stored page is not what the window read) gives back the items put in. A write that landed although the
 *       server was told it failed is not taken for a refusal. Kept writes survive quit, a reopen and module
 *       disable (a final bounded flush; what still cannot land is logged with its items).</li>
 *   <li><b>F3.</b> Module disable saves the edit windows still open.</li>
 *   <li><b>F4.</b> {@code /bag clear} and {@code /bag delete} claim the page for their own action.</li>
 * </ul>
 *
 * <h2>The instrument</h2>
 * Real SQLite, as in {@link BagEditClaimTest}: each server opens the shared file through its own operators, has its
 * own monotonic clock moved by the test, and its own main-thread queue. {@link StorageFaults} breaks one server's
 * store only. {@link BagStorageFailureJsonTest} runs every case on the JSON backend as well.
 */
@DisplayName("Storage failures of the edit claim and the page save have their decided answers (UltiRemoteBag#54, gate 1 F1-F4, F8)")
class BagStorageFailureTest {

    private static final int PAGE = 1;
    private static final int SLOT = 0;
    private static final int OTHER_SLOT = 5;
    private static final long TIMEOUT_MS = 300_000L;
    /** Longer than a renewal schedule allows (a third of the timeout, its deadline, one tick of the task). */
    private static final long PAST_THE_RENEWAL_SCHEDULE_MS = TIMEOUT_MS / 2 + 1_001L;

    @TempDir
    Path dir;

    private SharedDatabaseServers servers;
    private Server serverA;
    private Server serverB;
    private PlayerMock owner;
    private PlayerMock admin;
    private final List<StorageFaults> faults = new ArrayList<>();

    /** The shared storage; {@link BagStorageFailureJsonTest} overrides it. */
    protected SharedDatabaseServers.Backend backend() {
        return SharedDatabaseServers.Backend.SQLITE;
    }

    @BeforeEach
    void setUp() throws Exception {
        servers = SharedDatabaseServers.start(dir, backend());
        serverA = servers.newServer("A");
        serverB = servers.newServer("B");
        owner = servers.live().addPlayer("Owner");
        admin = servers.live().addPlayer("Admin");
        servers.seedPage(ownerId(), PAGE, pageWith(SLOT, new ItemStack(Material.DIAMOND)));
    }

    @AfterEach
    void tearDown() throws Exception {
        for (StorageFaults fault : faults) {
            fault.heal();
        }
        for (Server server : new Server[] {serverA, serverB}) {
            if (server != null) {
                server.claimService.shutdown();
            }
        }
        servers.stop();
    }

    private UUID ownerId() {
        return owner.getUniqueId();
    }

    private StorageFaults claimsOf(Server server) throws Exception {
        StorageFaults fault = StorageFaults.install(server.claimService, "claims");
        faults.add(fault);
        return fault;
    }

    private StorageFaults bagsOf(Server server) throws Exception {
        StorageFaults fault = StorageFaults.install(server.bagService, "dataOperator");
        faults.add(fault);
        return fault;
    }

    private RemoteBagEditClaim claim() {
        return servers.claimRow(ownerId(), PAGE);
    }

    private static boolean held(RemoteBagEditClaim claim) {
        return claim != null && claim.getHolderToken() != null && !claim.getHolderToken().isEmpty();
    }

    /** Every message the player has been sent and not yet read. */
    private static List<String> messages(PlayerMock player) {
        List<String> all = new ArrayList<>();
        String next;
        while ((next = player.nextMessage()) != null) {
            all.add(next);
        }
        return all;
    }

    /** Moves one item from the viewer's own inventory into an empty content slot of their open window. */
    private static void putIn(Window window, int slot, Material material) {
        window.viewer.getInventory().removeItem(new ItemStack(material));
        window.viewer.setItemOnCursor(new ItemStack(material));
        window.place(slot);
    }

    /** The owner puts an emerald in and takes the page's diamond out: the edit every case starts from. */
    private Window ownerEditsPage(Server server) {
        owner.getInventory().addItem(new ItemStack(Material.EMERALD));
        Window editing = server.openAsOwner(owner, PAGE);
        assertThat(editing.isEdit()).as("precondition: the owner edits").isTrue();
        putIn(editing, OTHER_SLOT, Material.EMERALD);
        editing.pickUp(SLOT);
        SharedDatabaseServers.returnCursorToInventory(owner);
        return editing;
    }

    /** {@code server}'s administrator tries the page and closes again; whether it opened for editing. */
    private boolean triesToEdit(Server server, PlayerMock viewer) {
        Window window = server.openAsAdmin(viewer, ownerId(), PAGE);
        boolean edit = window.isEdit();
        if (window.gui != null) {
            window.close();
        }
        return edit;
    }

    /** One background pass of {@code server} (renewals and kept writes), then its main thread runs. */
    private static void backgroundPass(Server server) throws InterruptedException {
        server.advance(TIMEOUT_MS / 3 + 1);
        server.renewOffMainThread();
        server.runMainThread();
    }

    /** The owner's edit is stored: the emerald in, the diamond out; each exists exactly once. */
    private void assertTheEditIsStoredOnce() {
        ItemStack[] stored = servers.storedPage(ownerId(), PAGE);
        assertThat(count(stored, Material.EMERALD)).as("the emerald put in is stored").isEqualTo(1);
        assertThat(count(stored, Material.DIAMOND)).as("the diamond taken out is not").isZero();
        assertThat(count(owner.getInventory(), Material.EMERALD)).as("the emerald was not given back as well").isZero();
        assertThat(servers.total(Material.EMERALD, ownerId(), owner, admin)).as("one emerald").isEqualTo(1);
        assertThat(servers.total(Material.DIAMOND, ownerId(), owner, admin)).as("one diamond").isEqualTo(1);
    }

    // ==================== F1: a renewal that throws or never returns ====================

    @Test
    @DisplayName("F1: a renewal that throws turns the window read-only at once; its content is written when the claim is confirmed again")
    void aRenewalThatThrowsTurnsTheWindowReadOnlyAtOnce() throws Exception {
        StorageFaults claimsA = claimsOf(serverA);
        Window editing = ownerEditsPage(serverA);
        claimsA.set(StorageFaults.Mode.THROW);

        backgroundPass(serverA);

        assertThat(messages(owner)).as("the owner is told").contains("bag_storage_trouble_read_only");
        owner.setItemOnCursor(new ItemStack(Material.STONE));
        assertThat(editing.place(OTHER_SLOT + 1).isCancelled()).as("the window is read-only now").isTrue();
        owner.setItemOnCursor(null);
        assertThat(count(owner.getInventory(), Material.EMERALD)).as("nothing is given back while the write is kept").isZero();
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("nothing written yet").isEqualTo(1);
        assertThat(triesToEdit(serverB, admin)).as("the page stays claimed: read-only on B").isFalse();

        claimsA.heal();
        backgroundPass(serverA);

        assertTheEditIsStoredOnce();
        verify(serverA.logger, never()).error(contains("log_bag_claim_lost"));
        editing.close();
        assertThat(held(claim())).as("released on close").isFalse();
        assertThat(triesToEdit(serverB, admin)).as("B can edit once A closed").isTrue();
    }

    @Test
    @DisplayName("F1: a renewal that never returns turns the window read-only by its deadline; its late success still counts")
    void aRenewalThatNeverReturnsTurnsTheWindowReadOnlyByItsDeadline() throws Exception {
        StorageFaults claimsA = claimsOf(serverA).onlyMethods("updateIf");
        Window editing = ownerEditsPage(serverA);
        claimsA.set(StorageFaults.Mode.HANG);

        long started = System.currentTimeMillis();
        backgroundPass(serverA);
        long took = System.currentTimeMillis() - started;

        assertThat(took).as("the pass gave up on the hung call (ms)").isLessThan(5_000L);
        assertThat(messages(owner)).contains("bag_storage_trouble_read_only");
        owner.setItemOnCursor(new ItemStack(Material.STONE));
        assertThat(editing.place(OTHER_SLOT + 1).isCancelled()).as("read-only").isTrue();
        owner.setItemOnCursor(null);

        claimsA.heal();
        assertThat(waitUntil(() -> claimsA.hungCalls() == 0)).as("the hung renewal finished").isTrue();
        backgroundPass(serverA);

        assertTheEditIsStoredOnce();
        verify(serverA.logger, never()).error(contains("log_bag_claim_lost"));
    }

    @Test
    @DisplayName("F1: with the renewal task itself stuck, a click past the renewal schedule finds the window read-only")
    void aStuckRenewalTaskStillTurnsTheWindowReadOnly() throws Exception {
        Window editing = serverA.openAsOwner(owner, PAGE);
        serverA.advance(TIMEOUT_MS / 3 + 1);
        assertThat(editing.pickUp(SLOT).isCancelled()).as("control: within the schedule the window edits").isFalse();
        SharedDatabaseServers.returnCursorToInventory(owner);

        serverA.advance(PAST_THE_RENEWAL_SCHEDULE_MS - TIMEOUT_MS / 3 - 1);
        owner.setItemOnCursor(new ItemStack(Material.STONE));

        assertThat(editing.place(OTHER_SLOT).isCancelled()).as("past the schedule with no confirmed renewal: read-only").isTrue();
        owner.setItemOnCursor(null);
        assertThat(messages(owner)).contains("bag_storage_trouble_read_only");

        // The task recovers: the claim is confirmed and the window's content (the diamond taken out) is written.
        backgroundPass(serverA);
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("the take-out is stored").isZero();
        assertThat(servers.total(Material.DIAMOND, ownerId(), owner)).as("one diamond").isEqualTo(1);
    }

    @Test
    @DisplayName("F1: one hung renewal does not hold up the renewal of this server's other claims")
    void oneHungRenewalDoesNotHoldUpTheOthers() throws Exception {
        servers.seedPage(ownerId(), 2, pageWith(SLOT, new ItemStack(Material.EMERALD)));
        StorageFaults claimsA = claimsOf(serverA).onlyMethods("updateIf")
                .onlyFirstArgument(row -> row instanceof RemoteBagEditClaim && ((RemoteBagEditClaim) row).getPageNumber() == PAGE);
        Window pageOne = serverA.openAsOwner(owner, PAGE);
        Window pageTwo = serverA.openAsAdmin(admin, ownerId(), 2);
        long pageTwoBefore = servers.claimRow(ownerId(), 2).getRenewals();
        claimsA.set(StorageFaults.Mode.HANG);

        backgroundPass(serverA);

        assertThat(servers.claimRow(ownerId(), 2).getRenewals()).as("page 2's claim was renewed").isGreaterThan(pageTwoBefore);
        assertThat(pageTwo.pickUp(SLOT).isCancelled()).as("page 2 still edits").isFalse();
        SharedDatabaseServers.returnCursorToInventory(admin);
        owner.setItemOnCursor(new ItemStack(Material.STONE));
        assertThat(pageOne.place(OTHER_SLOT).isCancelled()).as("page 1 is read-only").isTrue();
        owner.setItemOnCursor(null);
    }

    @Test
    @DisplayName("F1: a renewal that landed although it reported an error is not taken for a lost claim")
    void aRenewalThatLandedButReportedAnErrorIsNotALoss() throws Exception {
        StorageFaults claimsA = claimsOf(serverA).onlyMethods("updateIf");
        ownerEditsPage(serverA);
        claimsA.set(StorageFaults.Mode.COMMIT_THEN_THROW);

        backgroundPass(serverA);
        backgroundPass(serverA);

        verify(serverA.logger, never()).error(contains("log_bag_claim_lost"));
        assertTheEditIsStoredOnce();
    }

    @Test
    @DisplayName("F1: cut off for a full timeout while another server takes over -- the window was read-only throughout, its put-in is given back")
    void aTakeoverAfterAFullOutageGivesBackThePutIns() throws Exception {
        StorageFaults claimsA = claimsOf(serverA);
        Window editing = ownerEditsPage(serverA);
        claimsA.set(StorageFaults.Mode.THROW);
        backgroundPass(serverA);
        owner.setItemOnCursor(new ItemStack(Material.STONE));
        assertThat(editing.place(OTHER_SLOT + 1).isCancelled()).as("read-only from the first failed renewal").isTrue();
        owner.setItemOnCursor(null);

        // B sees the claim unchanged for one full timeout of its own clock and takes the page over.
        assertThat(triesToEdit(serverB, admin)).isFalse();
        serverB.advance(TIMEOUT_MS + 1);
        backgroundPass(serverA);
        Window taken = serverB.openAsAdmin(admin, ownerId(), PAGE);
        assertThat(taken.isEdit()).as("B took the page over").isTrue();
        taken.pickUp(SLOT);
        SharedDatabaseServers.returnCursorToInventory(admin);
        taken.close();

        claimsA.heal();
        backgroundPass(serverA);

        assertThat(count(owner.getInventory(), Material.EMERALD)).as("the emerald put in is given back").isEqualTo(1);
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.EMERALD)).as("and not stored").isZero();
        assertThat(servers.total(Material.EMERALD, ownerId(), owner, admin)).as("one emerald").isEqualTo(1);
        verify(serverA.logger, atLeastOnce()).warn(contains("log_bag_items_returned"));
        // Documented limitation (README): a server cut off for a full timeout from a database another server
        // still reaches -- the item taken out before the cut is also taken out on the other server.
        assertThat(servers.total(Material.DIAMOND, ownerId(), owner, admin)).isEqualTo(2);
    }

    // ==================== F2: a save that throws or never returns ====================

    @Test
    @DisplayName("F2: a save that throws at close keeps the claim and the content; the write lands when the database is back")
    void aSaveThatThrowsAtCloseIsKeptAndLands() throws Exception {
        StorageFaults bagsA = bagsOf(serverA);
        Window editing = ownerEditsPage(serverA);
        bagsA.set(StorageFaults.Mode.THROW);

        editing.close();

        assertThat(messages(owner)).as("the owner is told").contains("msg_save_pending");
        assertThat(count(owner.getInventory(), Material.EMERALD)).as("the emerald is in the kept write, not given back").isZero();
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("nothing written yet").isEqualTo(1);
        assertThat(held(claim())).as("the claim is kept").isTrue();
        assertThat(triesToEdit(serverB, admin)).as("read-only on B").isFalse();
        serverB.advance(TIMEOUT_MS + 1);
        backgroundPass(serverA);
        assertThat(triesToEdit(serverB, admin)).as("A renews the claim while the write is kept").isFalse();

        bagsA.heal();
        backgroundPass(serverA);

        assertTheEditIsStoredOnce();
        assertThat(held(claim())).as("released once written").isFalse();
        assertThat(triesToEdit(serverB, admin)).as("B can edit now").isTrue();
        verify(serverA.logger, never()).warn(contains("log_bag_items_returned"));
    }

    @Test
    @DisplayName("F2: a save that never returns at close is given up on by its deadline; its late success counts")
    void aSaveThatHangsAtCloseIsKeptAndItsLateResultCounts() throws Exception {
        StorageFaults bagsA = bagsOf(serverA).onlyMethods("updateIf");
        Window editing = ownerEditsPage(serverA);
        bagsA.set(StorageFaults.Mode.HANG);

        long started = System.currentTimeMillis();
        editing.close();
        long took = System.currentTimeMillis() - started;

        assertThat(took).as("the close gave up on the hung write (ms)").isLessThan(5_000L);
        assertThat(count(owner.getInventory(), Material.EMERALD)).isZero();
        assertThat(held(claim())).isTrue();

        bagsA.heal();
        assertThat(waitUntil(() -> bagsA.hungCalls() == 0)).isTrue();
        backgroundPass(serverA);
        backgroundPass(serverA);

        assertTheEditIsStoredOnce();
        assertThat(held(claim())).isFalse();
    }

    @Test
    @DisplayName("F2: a write that landed although it reported an error is not taken for a refusal")
    void aWriteThatLandedButReportedAnErrorIsNotGivenBack() throws Exception {
        StorageFaults bagsA = bagsOf(serverA).onlyMethods("updateIf");
        Window editing = ownerEditsPage(serverA);
        bagsA.set(StorageFaults.Mode.COMMIT_THEN_THROW);

        editing.close();
        backgroundPass(serverA);

        assertTheEditIsStoredOnce();
        assertThat(held(claim())).isFalse();
        verify(serverA.logger, never()).warn(contains("log_bag_items_returned"));
    }

    @Test
    @DisplayName("F2: a kept write the database refuses (an outside writer changed the page) gives back the put-in and releases")
    void aKeptWriteThatIsRefusedGivesBackThePutIn() throws Exception {
        StorageFaults bagsA = bagsOf(serverA);
        Window editing = ownerEditsPage(serverA);
        bagsA.set(StorageFaults.Mode.THROW);
        editing.close();
        servers.writePageElsewhere(ownerId(), PAGE, pageWith(SLOT + 1, new ItemStack(Material.GOLD_INGOT)));

        bagsA.heal();
        backgroundPass(serverA);

        assertThat(count(owner.getInventory(), Material.EMERALD)).as("given back").isEqualTo(1);
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.GOLD_INGOT)).as("the other writer's page stays").isEqualTo(1);
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.EMERALD)).isZero();
        assertThat(held(claim())).as("released").isFalse();
        verify(serverA.logger, atLeastOnce()).warn(contains("log_bag_items_returned"));
    }

    @Test
    @DisplayName("F2: a kept write survives the player's quit")
    void aKeptWriteSurvivesQuit() throws Exception {
        StorageFaults bagsA = bagsOf(serverA);
        ownerEditsPage(serverA);
        bagsA.set(StorageFaults.Mode.THROW);

        serverA.quit(owner);

        assertThat(held(claim())).as("still claimed after the quit").isTrue();
        assertThat(triesToEdit(serverB, admin)).isFalse();
        bagsA.heal();
        backgroundPass(serverA);
        assertTheEditIsStoredOnce();
        assertThat(held(claim())).isFalse();
    }

    @Test
    @DisplayName("F2: a reopen on this server while the write is kept shows the kept content, read-only")
    void aReopenWhileTheWriteIsKeptShowsTheKeptContent() throws Exception {
        StorageFaults bagsA = bagsOf(serverA);
        Window editing = ownerEditsPage(serverA);
        bagsA.set(StorageFaults.Mode.THROW);
        editing.close();
        messages(owner);

        Window reopened = serverA.openAsOwner(owner, PAGE);

        assertThat(reopened.isReadOnly()).as("read-only while the write is kept").isTrue();
        assertThat(reopened.result.renderMessage(serverA.plugin)).isEqualTo("bag_read_only_save_pending");
        assertThat(reopened.shown(OTHER_SLOT)).as("the kept content: the emerald").isNotNull();
        assertThat(reopened.shown(OTHER_SLOT).getType()).isEqualTo(Material.EMERALD);
        assertThat(reopened.shown(SLOT)).as("the diamond was taken out").isNull();
        assertThat(reopened.pickUp(OTHER_SLOT).isCancelled()).isTrue();
        reopened.close();
        Window adminView = serverA.openAsAdmin(admin, ownerId(), PAGE);
        assertThat(adminView.isReadOnly()).as("another player on this server: read-only too").isTrue();
        adminView.close();

        bagsA.heal();
        backgroundPass(serverA);
        assertTheEditIsStoredOnce();
        Window afterwards = serverA.openAsOwner(owner, PAGE);
        assertThat(afterwards.isEdit()).as("editable once written").isTrue();
        afterwards.close();
    }

    @Test
    @DisplayName("F2: module disable flushes a kept write")
    void disableFlushesAKeptWrite() throws Exception {
        StorageFaults bagsA = bagsOf(serverA);
        Window editing = ownerEditsPage(serverA);
        bagsA.set(StorageFaults.Mode.THROW);
        editing.close();
        bagsA.heal();

        serverA.shutdown();

        assertTheEditIsStoredOnce();
        assertThat(held(claim())).isFalse();
    }

    @Test
    @DisplayName("F2: a kept write that still cannot land at disable is logged with its items, and the put-in goes back to the online player")
    void disableLogsAKeptWriteThatCannotLand() throws Exception {
        StorageFaults bagsA = bagsOf(serverA);
        Window editing = ownerEditsPage(serverA);
        bagsA.set(StorageFaults.Mode.THROW);
        editing.close();

        long started = System.currentTimeMillis();
        serverA.shutdown();
        long took = System.currentTimeMillis() - started;

        assertThat(took).as("disable is bounded (ms)").isLessThan(10_000L);
        verify(serverA.logger, atLeastOnce()).error(contains("log_bag_save_abandoned"));
        assertThat(count(owner.getInventory(), Material.EMERALD)).as("the put-in goes back").isEqualTo(1);
        assertThat(held(claim())).as("released at disable").isFalse();
    }

    @Test
    @DisplayName("F2: a kept write refused after the player left gives the put-in back at their next join")
    void aRefusedKeptWriteOfAPlayerWhoLeftIsGivenBackAtTheirNextJoin() throws Exception {
        StorageFaults bagsA = bagsOf(serverA);
        ownerEditsPage(serverA);
        bagsA.set(StorageFaults.Mode.THROW);
        serverA.quit(owner);
        owner.disconnect();
        servers.writePageElsewhere(ownerId(), PAGE, pageWith(SLOT + 1, new ItemStack(Material.GOLD_INGOT)));

        bagsA.heal();
        backgroundPass(serverA);
        assertThat(count(owner.getInventory(), Material.EMERALD)).as("not yet: the player is away").isZero();

        owner.reconnect();
        serverA.join(owner);
        serverA.runMainThread();

        assertThat(count(owner.getInventory(), Material.EMERALD)).as("given back at the join").isEqualTo(1);
        assertThat(servers.total(Material.EMERALD, ownerId(), owner)).isEqualTo(1);
    }

    // ==================== F3: disable saves open edit windows ====================

    @Test
    @DisplayName("F3: module disable saves an edit window that is still open, and closes it")
    void disableSavesAnOpenEditWindow() throws Exception {
        ownerEditsPage(serverA);

        serverA.shutdown();

        assertTheEditIsStoredOnce();
        assertThat(held(claim())).isFalse();
        assertThat(InventoryAPI.getInstance().getPlayersCurrentGui(owner)).as("the window is closed").isNull();
    }

    // ==================== F4: clear and delete claim the page ====================

    @Test
    @DisplayName("F4: /bag clear and /bag delete claim the page for their action: another server cannot edit it meanwhile")
    void clearAndDeleteClaimThePage() throws Exception {
        StorageFaults bagsA = bagsOf(serverA);
        BagCommand commandOnA = new BagCommand(serverA.plugin, serverA.bagService, serverA.lockService, servers.config());
        AtomicBoolean editedDuringClear = new AtomicBoolean(true);
        bagsA.onlyMethods("updateIf").onceBefore(() -> editedDuringClear.set(triesToEdit(serverB, otherPlayer())));

        commandOnA.clearBag(admin, "Owner", PAGE);

        assertThat(editedDuringClear.get()).as("B was read-only while A cleared").isFalse();
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("cleared").isZero();
        assertThat(held(claim())).as("released after the clear").isFalse();

        AtomicBoolean editedDuringDelete = new AtomicBoolean(true);
        bagsA.onlyMethods("delById").onceBefore(() -> editedDuringDelete.set(triesToEdit(serverB, otherPlayer())));
        commandOnA.deleteBag(admin, "Owner", PAGE);

        assertThat(editedDuringDelete.get()).as("B was read-only while A deleted").isFalse();
        assertThat(servers.storedRowCount(ownerId(), PAGE)).as("deleted").isZero();
        assertThat(held(claim())).as("released after the delete").isFalse();
        verify(serverA.logger, never()).error(anyString());
    }

    private PlayerMock otherPlayer() {
        PlayerMock other = (PlayerMock) servers.live().getPlayerExact("OtherAdmin");
        return other != null ? other : servers.live().addPlayer("OtherAdmin");
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }
}
