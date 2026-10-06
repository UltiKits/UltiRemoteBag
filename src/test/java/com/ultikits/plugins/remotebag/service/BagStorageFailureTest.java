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
import java.util.Map;
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

    /** Every message the player has been sent and not yet read, without colour codes. */
    private static List<String> messages(PlayerMock player) {
        List<String> all = new ArrayList<>();
        String next;
        while ((next = player.nextMessage()) != null) {
            all.add(org.bukkit.ChatColor.stripColor(next));
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
        StorageFaults bagsA = bagsOf(serverA).onlyMethods("transaction", "updateIf");
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
        // The database answers again, but the kept write has not been retried yet (/bag see reads the bag first).
        bagsA.heal();
        Window adminView = serverA.openAsAdmin(admin, ownerId(), PAGE);
        assertThat(adminView.isReadOnly()).as("another player on this server: read-only too").isTrue();
        assertThat(adminView.shown(OTHER_SLOT)).as("showing the kept content").isNotNull();
        adminView.close();

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

    // ==================== Gate 1 round 2: fenced saves, settled outcomes, guards ====================

    /** B's admin sees the claim, waits one full timeout of B's clock, takes the page over and empties it. */
    private boolean bTakesOverAndAdminTakesAll() {
        return bTakesOver(true);
    }

    /** B's admin sees the claim, waits one full timeout of B's clock and takes the page over; empties it if asked. */
    private boolean bTakesOver(boolean emptyIt) {
        Window look = serverB.openAsAdmin(admin, ownerId(), PAGE);
        if (look.gui != null) {
            look.close();
        }
        serverB.advance(TIMEOUT_MS + 1);
        Window taken = serverB.openAsAdmin(admin, ownerId(), PAGE);
        if (!taken.isEdit()) {
            if (taken.gui != null) {
                taken.close();
            }
            return false;
        }
        for (int slot = 0; emptyIt && slot < SharedDatabaseServers.PAGE_SIZE; slot++) {
            if (taken.shown(slot) != null) {
                taken.pickUp(slot);
                SharedDatabaseServers.returnCursorToInventory(admin);
            }
        }
        taken.close();
        return true;
    }

    private void assumeRelational() {
        org.junit.jupiter.api.Assumptions.assumeTrue(backend() == SharedDatabaseServers.Backend.SQLITE,
                "the JSON backend belongs to one server: its two files share no transaction");
    }

    @Test
    @DisplayName("R2-1 (N1): a save that landed although it reported an error is not given back at disable, the database still down")
    void aLandedSaveThatReportedAnErrorIsNotGivenBackAtDisable() throws Exception {
        StorageFaults bagsA = bagsOf(serverA).onlyMethods("transaction", "updateIf");
        Window editing = ownerEditsPage(serverA);
        bagsA.set(StorageFaults.Mode.COMMIT_THEN_THROW);
        editing.close();
        bagsA.onlyMethods();
        bagsA.set(StorageFaults.Mode.THROW);

        serverA.shutdown();
        bagsA.heal();

        assertThat(servers.total(Material.EMERALD, ownerId(), owner, admin)).as("one emerald").isEqualTo(1);
        assertThat(count(owner.getInventory(), Material.EMERALD)).as("not given back: the save had landed").isZero();
    }

    @Test
    @DisplayName("R2-1 (N2): a save that landed although it reported an error is not given back when the claim is later found lost")
    void aLandedSaveThatReportedAnErrorIsNotGivenBackAfterATakeover() throws Exception {
        StorageFaults bagsA = bagsOf(serverA).onlyMethods("transaction", "updateIf");
        StorageFaults claimsA = claimsOf(serverA);
        Window editing = ownerEditsPage(serverA);
        bagsA.set(StorageFaults.Mode.COMMIT_THEN_THROW);
        editing.close();
        bagsA.onlyMethods();
        bagsA.set(StorageFaults.Mode.THROW);
        claimsA.set(StorageFaults.Mode.THROW);
        backgroundPass(serverA);

        assertThat(bTakesOverAndAdminTakesAll()).as("B took the page over and emptied it").isTrue();
        bagsA.heal();
        claimsA.heal();
        backgroundPass(serverA);
        backgroundPass(serverA);

        assertThat(servers.total(Material.EMERALD, ownerId(), owner, admin)).as("one emerald (B's admin has it)").isEqualTo(1);
        assertThat(count(owner.getInventory(), Material.EMERALD)).as("not given back to the owner").isZero();
    }

    @Test
    @DisplayName("R2-2 (N4): a save started while the claim was confirmed and held up past a takeover writes nothing once it runs")
    void aLateSaveCannotLandAfterATakeover() throws Exception {
        StorageFaults bagsA = bagsOf(serverA).onlyMethods("transaction", "updateIf");
        StorageFaults claimsA = claimsOf(serverA).onlyMethods("updateIf", "getById");
        Window editing = ownerEditsPage(serverA);
        Window look = serverB.openAsAdmin(admin, ownerId(), PAGE);
        if (look.gui != null) {
            look.close();
        }
        claimsA.set(StorageFaults.Mode.HANG);
        serverA.advance(TIMEOUT_MS / 2);
        bagsA.set(StorageFaults.Mode.HANG);
        editing.save();

        serverB.advance(TIMEOUT_MS + 1);
        Window taken = serverB.openAsAdmin(admin, ownerId(), PAGE);
        assertThat(taken.isEdit()).as("B took over after a full timeout").isTrue();
        bagsA.heal();
        claimsA.heal();
        assertThat(waitUntil(() -> bagsA.hungCalls() == 0 && claimsA.hungCalls() == 0)).isTrue();
        Thread.sleep(200L);

        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.EMERALD))
                .as("A's late save did not land after B took over").isZero();
        taken.close();
        backgroundPass(serverA);
        backgroundPass(serverA);
        editing.close();
        backgroundPass(serverA);
        assertThat(servers.total(Material.EMERALD, ownerId(), owner, admin)).as("one emerald").isEqualTo(1);
    }

    @Test
    @DisplayName("R2-4: a claim found lost while its save is still running gives nothing back until that save settles, then once")
    void aLostClaimWithASaveInFlightGivesBackOnceAfterItSettles() throws Exception {
        StorageFaults bagsA = bagsOf(serverA).onlyMethods("transaction", "updateIf");
        StorageFaults claimsA = claimsOf(serverA);
        Window editing = ownerEditsPage(serverA);
        bagsA.set(StorageFaults.Mode.HANG);
        editing.close();
        claimsA.set(StorageFaults.Mode.THROW);
        backgroundPass(serverA);
        // B takes over and changes nothing: the page still is what A's save read, so only the claim can stop it.
        assertThat(bTakesOver(false)).as("B took the page over").isTrue();
        claimsA.heal();
        backgroundPass(serverA);
        backgroundPass(serverA);
        assertThat(count(owner.getInventory(), Material.EMERALD)).as("nothing given back while the save may still land").isZero();

        bagsA.heal();
        assertThat(waitUntil(() -> bagsA.hungCalls() == 0)).isTrue();
        backgroundPass(serverA);
        backgroundPass(serverA);

        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.EMERALD)).as("the late save wrote nothing").isZero();
        assertThat(count(owner.getInventory(), Material.EMERALD)).as("given back once it settled").isEqualTo(1);
        assertThat(servers.total(Material.EMERALD, ownerId(), owner, admin)).as("one emerald").isEqualTo(1);
    }

    @Test
    @DisplayName("R2-4: disable with a save still running gives nothing back; the save landing afterwards leaves one emerald")
    void disableWithASaveInFlightGivesNothingBack() throws Exception {
        StorageFaults bagsA = bagsOf(serverA).onlyMethods("updateIf");
        Window editing = ownerEditsPage(serverA);
        bagsA.set(StorageFaults.Mode.HANG);
        editing.close();

        serverA.shutdown();
        assertThat(count(owner.getInventory(), Material.EMERALD)).as("not given back: the save is still running").isZero();
        bagsA.heal();
        assertThat(waitUntil(() -> bagsA.hungCalls() == 0)).isTrue();
        Thread.sleep(200L);

        assertThat(servers.total(Material.EMERALD, ownerId(), owner, admin)).as("one emerald, never two").isLessThanOrEqualTo(1);
        verify(serverA.logger, atLeastOnce()).error(contains("log_bag_save_abandoned"));
    }

    @Test
    @DisplayName("R2-2: a save is fenced on the claim in one transaction -- it raises the claim's counter, and a failed page write takes that back")
    void aSaveIsFencedOnTheClaimInOneTransaction() throws Exception {
        assumeRelational();
        StorageFaults bagsA = bagsOf(serverA).onlyMethods("updateIf");
        Window editing = ownerEditsPage(serverA);
        long before = claim().getRenewals();

        editing.save();
        long afterSave = claim().getRenewals();
        assertThat(afterSave).as("the save's fence raised the counter").isEqualTo(before + 1);

        owner.getInventory().addItem(new ItemStack(Material.GOLD_INGOT));
        putIn(editing, OTHER_SLOT + 2, Material.GOLD_INGOT);
        bagsA.set(StorageFaults.Mode.THROW);
        editing.save();
        assertThat(claim().getRenewals()).as("the page write failed: the fence was rolled back with it").isEqualTo(afterSave);
    }

    @Test
    @DisplayName("R2-2: another server's takeover started while a save runs does not succeed (the fence moved the counter it waits on); "
            + "that fence and page write are one transaction is proven by aSaveIsFencedOnTheClaimInOneTransaction")
    void aTakeoverStartedDuringASaveDoesNotSucceed() throws Exception {
        assumeRelational();
        StorageFaults bagsA = bagsOf(serverA).onlyMethods("updateIf");
        Window editing = ownerEditsPage(serverA);
        Window look = serverB.openAsAdmin(admin, ownerId(), PAGE);
        if (look.gui != null) {
            look.close();
        }
        serverB.advance(TIMEOUT_MS + 1);
        java.util.concurrent.atomic.AtomicReference<BagEditClaimService.Outcome> fromB = new java.util.concurrent.atomic.AtomicReference<>();
        Thread[] other = new Thread[1];
        bagsA.onceBefore(() -> {
            other[0] = new Thread(() -> fromB.set(serverB.claimService.claim(ownerId(), PAGE, admin.getUniqueId())));
            other[0].start();
        });

        editing.save();
        other[0].join(5_000L);

        assertThat(fromB.get()).as("B did not take the page over in the middle of A's save").isNotEqualTo(BagEditClaimService.Outcome.CLAIMED);
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.EMERALD)).as("A's save landed").isEqualTo(1);
    }

    @Test
    @DisplayName("R2-3: a give-back that cannot reach the main thread (framework disabled at stop) is made at disable, on the disabling thread")
    void aGiveBackTheMainThreadRejectedIsMadeAtDisable() throws Exception {
        StorageFaults bagsA = bagsOf(serverA);
        Window editing = ownerEditsPage(serverA);
        bagsA.set(StorageFaults.Mode.THROW);
        editing.close();
        servers.writePageElsewhere(ownerId(), PAGE, pageWith(SLOT + 1, new ItemStack(Material.GOLD_INGOT)));
        bagsA.heal();
        // As Bukkit's scheduler is reached while the framework is already disabled at server stop: nothing runs.
        com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper.setField(serverA.claimService, "mainThread",
                (java.util.concurrent.Executor) task -> { });

        backgroundPass(serverA);
        serverA.shutdown();

        assertThat(count(owner.getInventory(), Material.EMERALD)).as("given back at disable").isEqualTo(1);
        assertThat(servers.total(Material.EMERALD, ownerId(), owner, admin)).isEqualTo(1);
    }

    // ==================== Gate 1 round 3 ====================

    /**
     * Models MySQL's {@code socketTimeout} on {@code server}'s page store: its next transaction runs on a thread of its
     * own (the database server), and the caller is told it failed after 200 ms while that transaction goes on.
     *
     * @return the "database server" thread, once the transaction has started
     */
    private java.util.concurrent.atomic.AtomicReference<Thread> clientGivesUpOnTheNextTransaction(Server server) throws Exception {
        Object real = com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper.getField(server.bagService, "dataOperator");
        java.util.concurrent.atomic.AtomicReference<Thread> databaseSide = new java.util.concurrent.atomic.AtomicReference<>();
        Object proxy = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {com.ultikits.ultitools.interfaces.DataOperator.class}, (self, method, args) -> {
                    if (method.getName().equals("transaction") && args != null && args.length == 1
                            && args[0] instanceof java.util.concurrent.Callable && databaseSide.get() == null) {
                        Thread transaction = new Thread(() -> {
                            try {
                                method.invoke(real, args);
                            } catch (Exception ignored) {
                                // The database side's own outcome; the test reads the store.
                            }
                        }, "modelled database-side transaction");
                        databaseSide.set(transaction);
                        transaction.start();
                        transaction.join(200L);
                        if (transaction.isAlive()) {
                            throw new com.ultikits.ultitools.exceptions.DataAccessException(
                                    com.ultikits.ultitools.exceptions.ErrorCode.DATA_OPERATION_FAILED,
                                    "test: socket timeout -- the client gave up, the database goes on",
                                    new java.sql.SQLException("test: read timed out"));
                        }
                        return null;
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper.setField(server.bagService, "dataOperator", proxy);
        return databaseSide;
    }

    /** How many record rows (a session's save receipt: an id without {@code :}) the claims table holds. */
    private long receiptRows() {
        return servers.claimStore().getAll().stream().filter(row -> !row.getId().contains(":")).count();
    }

    @Test
    @DisplayName("R3-1 (PC): a save still running on the database when the module stops is not given back; it commits afterwards and exists once")
    void aSaveStillRunningOnTheDatabaseAtDisableIsNotGivenBack() throws Exception {
        assumeRelational();
        StorageFaults receiptInsert = claimsOf(serverA).onlyMethods("insert");
        java.util.concurrent.atomic.AtomicReference<Thread> databaseSide = clientGivesUpOnTheNextTransaction(serverA);
        Window editing = ownerEditsPage(serverA);
        // The database has fenced the claim and written the page; its receipt write -- the last statement -- waits.
        receiptInsert.set(StorageFaults.Mode.HANG);
        editing.close();

        serverA.shutdown();
        assertThat(count(owner.getInventory(), Material.EMERALD)).as("not given back: the save may still commit").isZero();
        verify(serverA.logger, atLeastOnce()).error(contains("log_bag_save_abandoned"));

        receiptInsert.heal();
        databaseSide.get().join(5_000L);
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.EMERALD)).as("the save committed after the stop").isEqualTo(1);
        assertThat(servers.total(Material.EMERALD, ownerId(), owner, admin)).as("one emerald").isEqualTo(1);
    }

    @Test
    @DisplayName("R3-2: a claim found lost with no save pending leaves no receipt row behind")
    void aLostClaimLeavesNoReceiptRow() throws Exception {
        Window editing = ownerEditsPage(serverA);
        editing.save();
        assertThat(receiptRows()).as("precondition: the save wrote its receipt").isEqualTo(1L);
        assertThat(bTakesOver(false)).isTrue();
        serverA.advance(TIMEOUT_MS / 3 + 1);
        serverA.renewOffMainThread();
        serverA.runMainThread();
        editing.close();
        backgroundPass(serverA);

        assertThat(receiptRows()).as("the receipt of a session that lost its claim is deleted").isZero();
    }

    @Test
    @DisplayName("R3-3: the display cache shows a page only once its save committed")
    void theCacheIsUpdatedOnlyAfterTheCommit() throws Exception {
        StorageFaults receiptInsert = claimsOf(serverA).onlyMethods("insert");
        serverA.bagService.loadBagIfNeeded(ownerId());
        Window editing = ownerEditsPage(serverA);
        receiptInsert.set(StorageFaults.Mode.THROW);

        editing.close();

        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.EMERALD)).as("rolled back").isZero();
        assertThat(count(serverA.bagService.getBagPage(ownerId(), PAGE), Material.EMERALD))
                .as("the cache does not show the rolled-back page").isZero();
    }

    @Test
    @DisplayName("R4-1: the stop-time decision writes the claim row before it reads anything (on MySQL a read first would miss a save still running)")
    void theStopDecisionWritesTheClaimRowBeforeItReads() throws Exception {
        StorageFaults bagsA = bagsOf(serverA).onlyMethods("transaction", "updateIf");
        Window editing = ownerEditsPage(serverA);
        bagsA.set(StorageFaults.Mode.COMMIT_THEN_THROW);
        editing.close();
        bagsA.onlyMethods();
        bagsA.set(StorageFaults.Mode.THROW);
        // Records, in order, the claims calls made inside a transaction of the claims store: the stop-time decision.
        List<String> insideDecision = new java.util.concurrent.CopyOnWriteArrayList<>();
        ThreadLocal<Boolean> deciding = ThreadLocal.withInitial(() -> false);
        Object real = com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper.getField(serverA.claimService, "claims");
        Object recorder = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {com.ultikits.ultitools.interfaces.DataOperator.class}, (self, method, args) -> {
                    boolean outer = method.getName().equals("transaction") && !deciding.get();
                    if (deciding.get()) {
                        insideDecision.add(method.getName());
                    }
                    if (outer) {
                        deciding.set(true);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    } finally {
                        if (outer) {
                            deciding.set(false);
                        }
                    }
                });
        com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper.setField(serverA.claimService, "claims", recorder);

        serverA.shutdown();
        bagsA.heal();

        assertThat(insideDecision).as("the decision made claims calls").isNotEmpty();
        assertThat(insideDecision.get(0)).as("its first call is the claim-row write").isEqualTo("updateIf");
        assertThat(insideDecision).as("and it reads the receipt after it").contains("getById");
        assertThat(servers.total(Material.EMERALD, ownerId(), owner, admin)).isEqualTo(1);
    }

    // ==================== Gate 2, Codex run 1 ====================

    @Test
    @DisplayName("Codex P1: a Save's baseline is a copy, not the window's live stacks -- items stacked on after a Save are given back exactly when the claim is lost")
    void itemsStackedOnAfterASaveAreGivenBackExactly() throws Exception {
        servers.writePageElsewhere(ownerId(), PAGE, pageWith(SLOT, new ItemStack(Material.DIAMOND, 32)));
        owner.getInventory().addItem(new ItemStack(Material.DIAMOND, 32));
        Window editing = serverA.openAsOwner(owner, PAGE);
        editing.save();
        // Vanilla stacking onto an existing stack changes that stack in place: the inventory's own item grows.
        owner.getInventory().removeItem(new ItemStack(Material.DIAMOND, 32));
        editing.gui.getInventory().getItem(SLOT).setAmount(64);
        assertThat(editing.shown(SLOT).getAmount()).as("precondition: the window's stack grew in place").isEqualTo(64);
        // Something outside this server rewrites the claim: the claim is lost.
        RemoteBagEditClaim stolen = claim();
        stolen.setHolderRun("intruder-run");
        stolen.setHolderToken("intruder-token");
        stolen.setRenewals(stolen.getRenewals() + 1);
        servers.claimStore().updateCounted(stolen);

        backgroundPass(serverA);

        assertThat(count(owner.getInventory(), Material.DIAMOND)).as("the 32 stacked on after the Save are given back").isEqualTo(32);
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("the saved 32 stay stored").isEqualTo(32);
        assertThat(servers.total(Material.DIAMOND, ownerId(), owner, admin)).as("64 diamonds, none lost").isEqualTo(64);
    }

    @Test
    @DisplayName("Codex P2: receipt reads of several abandoned saves share one deadline")
    void receiptReadsOfAbandonedSavesShareOneDeadline() throws Exception {
        servers.seedPage(ownerId(), 2, pageWith(SLOT, new ItemStack(Material.EMERALD)));
        servers.seedPage(ownerId(), 3, pageWith(SLOT, new ItemStack(Material.EMERALD)));
        StorageFaults bagsA = bagsOf(serverA);
        StorageFaults claimsA = claimsOf(serverA);
        Window one = serverA.openAsOwner(owner, PAGE);
        Window two = serverA.openAsAdmin(admin, ownerId(), 2);
        Window three = serverA.openAsAdmin(otherPlayer(), ownerId(), 3);
        bagsA.set(StorageFaults.Mode.THROW);
        one.close();
        two.close();
        three.close();
        claimsA.set(StorageFaults.Mode.THROW);
        backgroundPass(serverA);
        for (int page = 1; page <= 3; page++) {
            Window look = serverB.openAsAdmin(admin, ownerId(), page);
            if (look.gui != null) {
                look.close();
            }
        }
        serverB.advance(TIMEOUT_MS + 1);
        for (int page = 1; page <= 3; page++) {
            Window taken = serverB.openAsAdmin(admin, ownerId(), page);
            assertThat(taken.isEdit()).as("B took page %d over", page).isTrue();
            taken.close();
        }
        bagsA.heal();
        // A's claims answer again, but every receipt read (a receipt's id is a bare token) hangs.
        claimsA.onlyMethods("getById").onlyFirstArgument(id -> !String.valueOf(id).contains(":"));
        claimsA.set(StorageFaults.Mode.HANG);

        serverA.advance(TIMEOUT_MS / 3 + 1);
        long started = System.currentTimeMillis();
        serverA.renewOffMainThread();
        long took = System.currentTimeMillis() - started;

        assertThat(took).as("one shared deadline (%d ms each), not one per page (ms)", SharedDatabaseServers.CALL_DEADLINE_MILLIS)
                .isLessThan(3 * SharedDatabaseServers.CALL_DEADLINE_MILLIS - 100);
        claimsA.heal();
    }

    @Test
    @DisplayName("Codex P2: /bag <page> and /bag see reopen a page whose save is kept read-only from the kept content, without reading the database")
    void reopeningAKeptPageDoesNotReadTheDatabase() throws Exception {
        StorageFaults bagsA = bagsOf(serverA);
        Window editing = ownerEditsPage(serverA);
        bagsA.set(StorageFaults.Mode.THROW);
        editing.close();
        messages(owner);
        BagCommand commandOnA = new BagCommand(serverA.plugin, serverA.bagService, serverA.lockService, servers.config());

        commandOnA.openPage(owner, PAGE);

        assertThat(messages(owner)).as("the owner is told").contains("bag_read_only_save_pending");
        org.bukkit.inventory.InventoryView view = owner.getOpenInventory();
        assertThat(view.getTopInventory().getItem(OTHER_SLOT)).as("the kept content is shown").isNotNull();
        assertThat(view.getTopInventory().getItem(OTHER_SLOT).getType()).isEqualTo(Material.EMERALD);
        owner.closeInventory();

        commandOnA.seePlayerBagPage(admin, "Owner", PAGE);
        assertThat(messages(admin)).as("the administrator too").contains("bag_read_only_save_pending");
        admin.closeInventory();
        bagsA.heal();
    }

    @Test
    @DisplayName("Codex P2: /bag save answers 'not saved yet' while a save of the sender's is kept")
    void bagSaveSaysNotSavedYetWhileASaveIsKept() throws Exception {
        StorageFaults bagsA = bagsOf(serverA);
        serverA.bagService.loadBagIfNeeded(ownerId());
        Window editing = ownerEditsPage(serverA);
        bagsA.set(StorageFaults.Mode.THROW);
        editing.save();
        messages(owner);
        BagCommand commandOnA = new BagCommand(serverA.plugin, serverA.bagService, serverA.lockService, servers.config());

        commandOnA.saveBag(owner);

        List<String> said = messages(owner);
        assertThat(said).as("not reported as saved").doesNotContain("bag_saved_manually");
        assertThat(said).as("told it is not saved yet").contains("msg_save_pending");
        bagsA.heal();
    }

    // ==================== Gate 2, Codex run 2 ====================

    @Test
    @DisplayName("Codex run 2: a claim-lost notice queued for the old session does not touch the window of a new session of the same page")
    void aStaleClaimLostNoticeLeavesTheNextSessionAlone() throws Exception {
        Window old = serverA.openAsOwner(owner, PAGE);
        // Something outside this server releases the claim and moves the counter: A's next renewal finds it lost
        // and queues the notice for A's main thread, which has not run it yet.
        RemoteBagEditClaim released = claim();
        released.setHolderRun("intruder-run");
        released.setHolderToken("");
        released.setRenewals(released.getRenewals() + 1);
        servers.claimStore().updateCounted(released);
        serverA.advance(TIMEOUT_MS / 3 + 1);
        serverA.renewOffMainThread();
        assertThat(serverA.mainThreadTasks).as("precondition: the notice is queued").isNotEmpty();

        old.close();
        Window next = serverA.openAsOwner(owner, PAGE);
        assertThat(next.isEdit()).as("precondition: the page was claimed again").isTrue();
        owner.getInventory().addItem(new ItemStack(Material.GOLD_INGOT));
        putIn(next, OTHER_SLOT, Material.GOLD_INGOT);
        messages(owner);

        serverA.runMainThread();

        assertThat(messages(owner)).as("the new session is not told its claim was lost").doesNotContain("bag_claim_lost_read_only");
        owner.setItemOnCursor(new ItemStack(Material.STONE));
        assertThat(next.place(OTHER_SLOT + 1).isCancelled()).as("the new window still edits").isFalse();
        SharedDatabaseServers.returnCursorToInventory(owner);
        assertThat(count(owner.getInventory(), Material.GOLD_INGOT)).as("its put-in is not given back").isZero();
        next.close();
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.GOLD_INGOT)).as("and it is saved").isEqualTo(1);
    }

    @Test
    @DisplayName("Codex run 2: an unconfirmed-claim notice queued for the old session does not touch the window of a new session of the same page")
    void aStaleUnconfirmedNoticeLeavesTheNextSessionAlone() throws Exception {
        StorageFaults claimsA = claimsOf(serverA).onlyMethods("updateIf");
        Window old = serverA.openAsOwner(owner, PAGE);
        claimsA.set(StorageFaults.Mode.THROW);
        serverA.advance(TIMEOUT_MS / 3 + 1);
        serverA.renewOffMainThread();
        assertThat(serverA.mainThreadTasks).as("precondition: the notice is queued").isNotEmpty();
        claimsA.heal();
        // The old window closes before the notice runs: its save is kept (the claim is unconfirmed), then lands,
        // and the claim is released; the owner claims the page again.
        old.close();
        serverA.advance(1);
        serverA.renewOffMainThread();
        serverA.renewOffMainThread();
        Window next = serverA.openAsOwner(owner, PAGE);
        assertThat(next.isEdit()).as("precondition: the page was claimed again").isTrue();
        owner.getInventory().addItem(new ItemStack(Material.GOLD_INGOT));
        putIn(next, OTHER_SLOT, Material.GOLD_INGOT);
        messages(owner);

        serverA.runMainThread();

        assertThat(messages(owner)).as("the new session is not turned read-only").doesNotContain("bag_storage_trouble_read_only");
        owner.setItemOnCursor(new ItemStack(Material.STONE));
        assertThat(next.place(OTHER_SLOT + 1).isCancelled()).as("the new window still edits").isFalse();
        SharedDatabaseServers.returnCursorToInventory(owner);
        next.close();
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.GOLD_INGOT)).as("its put-in is saved").isEqualTo(1);
    }

    @Test
    @DisplayName("Codex run 2: releases that do not answer, after kept saves land, do not hold up a background pass")
    void hungReleasesDoNotHoldUpThePass() throws Exception {
        servers.seedPage(ownerId(), 2, pageWith(SLOT, new ItemStack(Material.EMERALD)));
        servers.seedPage(ownerId(), 3, pageWith(SLOT, new ItemStack(Material.EMERALD)));
        StorageFaults bagsA = bagsOf(serverA);
        StorageFaults claimsA = claimsOf(serverA);
        Window one = serverA.openAsOwner(owner, PAGE);
        Window two = serverA.openAsAdmin(admin, ownerId(), 2);
        Window three = serverA.openAsAdmin(otherPlayer(), ownerId(), 3);
        bagsA.set(StorageFaults.Mode.THROW);
        one.close();
        two.close();
        three.close();
        bagsA.heal();
        // Every release (an updateIf that empties the token) hangs; renewals and the saves themselves answer.
        claimsA.onlyMethods("updateIf").onlyFirstArgument(row -> row instanceof RemoteBagEditClaim
                && ((RemoteBagEditClaim) row).getHolderToken() != null && ((RemoteBagEditClaim) row).getHolderToken().isEmpty());
        claimsA.set(StorageFaults.Mode.HANG);

        serverA.advance(1);
        long started = System.currentTimeMillis();
        serverA.renewOffMainThread();
        long took = System.currentTimeMillis() - started;

        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("precondition: the kept saves landed").isEqualTo(1);
        assertThat(took).as("the pass did not wait for the hung releases one after another (ms)")
                .isLessThan(2 * SharedDatabaseServers.CALL_DEADLINE_MILLIS);
        claimsA.heal();
    }

    // ==================== Gate 2 top-up: session state is per session, not per page ====================

    private String claimToken() {
        RemoteBagEditClaim row = claim();
        return row == null ? null : row.getHolderToken();
    }

    @Test
    @DisplayName("Top-up P1 (probe): a stale renewal of session T1 finding T1's claim lost does not mark the reopened session T2 lost; T2's take-out exists once")
    void aStaleLostRenewalLeavesTheNextSessionAlone() throws Exception {
        StorageFaults claimsA = claimsOf(serverA);
        StorageFaults bagsA = bagsOf(serverA);
        Window first = serverA.openAsOwner(owner, PAGE);
        String t1 = claimToken();
        serverA.advance(TIMEOUT_MS / 3 + 1);
        AtomicBoolean armed = new AtomicBoolean(false);
        claimsA.onlyMethods("updateIf").onlyFirstArgument(row -> armed.get()
                && row instanceof RemoteBagEditClaim && t1.equals(((RemoteBagEditClaim) row).getHolderToken()));
        claimsA.set(StorageFaults.Mode.HANG);
        java.util.concurrent.atomic.AtomicReference<Thread> renewer = new java.util.concurrent.atomic.AtomicReference<>();
        bagsA.onlyMethods("updateIf").onceBefore(() -> {
            // Inside T1's close-time save (it holds the claim's lock): a background pass starts and its renewal of
            // T1 waits for that lock; once the save is done, its updateIf on T1 hangs.
            Thread pass = new Thread(serverA.claimService::renewDue, "test-renewal-pass");
            renewer.set(pass);
            pass.start();
            try {
                Map<?, ?> held = (Map<?, ?>) com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper.getField(serverA.claimService, "held");
                long until = System.currentTimeMillis() + 2_000L;
                while (System.currentTimeMillis() < until) {
                    Object claim = held.values().iterator().next();
                    if (com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper.getField(claim, "inFlight") != null) {
                        break;
                    }
                    Thread.sleep(2L);
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            armed.set(true);
        });
        first.close();

        Window second = serverA.openAsOwner(owner, PAGE);
        assertThat(second.isEdit()).as("precondition: T2 claimed the released page").isTrue();
        second.pickUp(SLOT);
        SharedDatabaseServers.returnCursorToInventory(owner);
        claimsA.release();
        renewer.get().join(10_000L);
        serverA.runMainThread();

        assertThat(serverA.claimService.isLost(ownerId(), PAGE)).as("T2's page is not lost").isFalse();
        owner.setItemOnCursor(new ItemStack(Material.STONE));
        assertThat(second.place(OTHER_SLOT).isCancelled()).as("T2 still edits").isFalse();
        SharedDatabaseServers.returnCursorToInventory(owner);
        second.close();
        assertThat(servers.total(Material.DIAMOND, ownerId(), owner)).as("one diamond").isEqualTo(1);
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("T2's take-out was saved").isZero();
    }

    @Test
    @DisplayName("Top-up sweep: a page this run holds for one player's session is not handed to another player's session")
    void aHeldClaimIsNotReusedForAnotherPlayersSession() {
        assertThat(serverA.claimService.claim(ownerId(), PAGE, owner.getUniqueId())).isEqualTo(BagEditClaimService.Outcome.CLAIMED);

        BagEditClaimService.Outcome second = serverA.claimService.claim(ownerId(), PAGE, admin.getUniqueId());

        assertThat(second).as("another player's session does not take over this one's claim").isNotEqualTo(BagEditClaimService.Outcome.CLAIMED);
    }

    @Test
    @DisplayName("Top-up sweep: releasing a page's claim names the session; another session's token releases nothing")
    void aReleaseNamingAnotherSessionReleasesNothing() throws Exception {
        Window editing = serverA.openAsOwner(owner, PAGE);
        String token = claimToken();
        java.lang.reflect.Method scoped = null;
        try {
            scoped = BagEditClaimService.class.getMethod("release", UUID.class, int.class, String.class);
        } catch (NoSuchMethodException absent) {
            // Before the sweep a release named the page only.
        }

        if (scoped != null) {
            scoped.invoke(serverA.claimService, ownerId(), PAGE, "an-earlier-session");
        } else {
            serverA.claimService.release(ownerId(), PAGE);
        }

        assertThat(claimToken()).as("the session's claim is still held").isEqualTo(token);
        editing.close();
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
