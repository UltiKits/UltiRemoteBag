package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.commands.BagCommand;
import com.ultikits.plugins.remotebag.entity.BagOpenResult;
import com.ultikits.plugins.remotebag.entity.RemoteBagEditClaim;
import com.ultikits.plugins.remotebag.enums.LockType;
import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers;
import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.Server;
import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.Window;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;

import static com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.count;
import static com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.pageWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * One bag page is edited on one server at a time, and the claim cannot be lost by accident
 * (UltiKits/UltiRemoteBag#54; maintainer decisions of 2026-10-06: option A, then the route change).
 *
 * <h2>The rules under test</h2>
 * <ul>
 *   <li>Opening a page for editing claims it in {@code remote_bag_claims}; while another server holds it, the
 *       page opens read-only.</li>
 *   <li>The holder renews the claim on a background task, which a stalled main thread cannot stop: each
 *       renewal increments the row's counter.</li>
 *   <li>Expiry uses no clock comparison between servers: an observer may take a claim over only after it has
 *       seen the same counter for a full {@code lock.timeout_seconds} on its own monotonic clock, and takes it
 *       with a conditional write on that counter.</li>
 *   <li>If a renewal fails, the holder's window turns read-only at once; a refused save gives back the items
 *       the player put in during the session (items taken out are not taken back).</li>
 *   <li>The claim is released on close, on quit and at module disable, and is not renewed after that.</li>
 * </ul>
 *
 * <h2>The instrument</h2>
 * Real SQLite: two or three "servers" ({@link SharedDatabaseServers}) open one file through their own
 * framework operators. Each has its own wall clock, its own monotonic clock (moved by the test) and its own
 * main-thread queue, which nobody drains while its main thread is "stalled". {@link BagEditClaimJsonTest} runs
 * every case on the JSON backend as well.
 */
@DisplayName("A bag page is edited on one server at a time; the claim is renewed off the main thread and expires by an observed counter (UltiRemoteBag#54)")
class BagEditClaimTest {

    private static final int PAGE = 1;
    private static final int SLOT = 0;
    private static final int OTHER_SLOT = 5;
    private static final long TIMEOUT_MS = 300_000L;
    /** Bottom row slot 3 -- the Refresh icon of a read-only page. */
    private static final int REFRESH_SLOT = 48;

    @TempDir
    Path dir;

    private SharedDatabaseServers servers;
    private Server serverA;
    private Server serverB;
    private Server serverC;
    private PlayerMock owner;
    private PlayerMock admin;
    private PlayerMock otherAdmin;

    /** The shared storage; {@link BagEditClaimJsonTest} overrides it. */
    protected SharedDatabaseServers.Backend backend() {
        return SharedDatabaseServers.Backend.SQLITE;
    }

    @BeforeEach
    void setUp() throws Exception {
        servers = SharedDatabaseServers.start(dir, backend());
        serverA = servers.newServer("A");
        serverB = servers.newServer("B");
        serverC = servers.newServer("C");
        owner = servers.live().addPlayer("Owner");
        admin = servers.live().addPlayer("Admin");
        otherAdmin = servers.live().addPlayer("OtherAdmin");
        servers.seedPage(ownerId(), PAGE, pageWith(SLOT, new ItemStack(Material.DIAMOND)));
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Server server : new Server[] {serverA, serverB, serverC}) {
            if (server != null) {
                server.claimService.shutdown();
            }
        }
        servers.stop();
    }

    private UUID ownerId() {
        return owner.getUniqueId();
    }

    private RemoteBagEditClaim claim() {
        return servers.claimRow(ownerId(), PAGE);
    }

    private static boolean held(RemoteBagEditClaim claim) {
        return claim != null && claim.getHolderToken() != null && !claim.getHolderToken().isEmpty();
    }

    /** Moves one item from the viewer's own inventory into an empty content slot of their open window. */
    private static void putIn(Window window, int slot, Material material) {
        window.viewer.getInventory().removeItem(new ItemStack(material));
        window.viewer.setItemOnCursor(new ItemStack(material));
        window.place(slot);
    }

    /** Waits (real time, at most two seconds) until the page's claim counter is above {@code than}. */
    private long awaitCounterAbove(long than) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            RemoteBagEditClaim row = claim();
            if (row != null && row.getRenewals() > than) {
                return row.getRenewals();
            }
            Thread.sleep(10L);
        }
        RemoteBagEditClaim row = claim();
        return row == null ? Long.MIN_VALUE : row.getRenewals();
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

    // ==================== Claim and refusal ====================

    @Test
    @DisplayName("Opening a page for editing claims it: one row <uuid>:<page> with this session's token and a renewal counter")
    void openingForEditClaims() {
        Window page = serverA.openAsOwner(owner, PAGE);

        assertThat(page.isEdit()).isTrue();
        RemoteBagEditClaim claim = claim();
        assertThat(claim).as("a claim row for the page").isNotNull();
        assertThat(claim.getId()).isEqualTo(ownerId() + ":" + PAGE);
        assertThat(claim.getPlayerUuid()).isEqualTo(ownerId().toString());
        assertThat(claim.getPageNumber()).isEqualTo(PAGE);
        assertThat(held(claim)).as("it holds a token").isTrue();
        assertThat(claim.getRenewals()).as("a fresh claim's counter").isZero();
    }

    @Test
    @DisplayName("While server A edits, an administrator on server B gets the page read-only with the other-server notice; clicks are refused; B's close writes nothing")
    void anotherServersAdministratorIsReadOnly() {
        Window editing = serverA.openAsOwner(owner, PAGE);
        assertThat(editing.isEdit()).as("precondition: A edits").isTrue();
        String storedBefore = servers.storedRow(ownerId(), PAGE).getContents();

        Window view = serverB.openAsAdmin(admin, ownerId(), PAGE);

        assertThat(view.isReadOnly()).as("B opens it read-only").isTrue();
        assertThat(view.result.getNotice()).isEqualTo(BagOpenResult.Notice.READ_ONLY_OTHER_SERVER);
        assertThat(view.result.renderMessage(serverB.plugin)).isEqualTo("bag_read_only_other_server");
        assertThat(view.pickUp(SLOT).isCancelled()).as("taking the diamond is refused").isTrue();
        view.close();
        assertThat(servers.storedRow(ownerId(), PAGE).getContents()).as("B's close writes nothing").isEqualTo(storedBefore);
        assertThat(count(admin.getInventory(), Material.DIAMOND)).isZero();
    }

    @Test
    @DisplayName("While an administrator on server A edits, the owner on server B gets the page read-only")
    void theOwnerOnAnotherServerIsReadOnly() {
        assertThat(serverA.openAsAdmin(admin, ownerId(), PAGE).isEdit()).as("precondition: A's administrator edits").isTrue();

        Window page = serverB.openAsOwner(owner, PAGE);

        assertThat(page.isReadOnly()).as("first claimant edits, owner or not").isTrue();
        assertThat(page.result.getNotice()).isEqualTo(BagOpenResult.Notice.READ_ONLY_OTHER_SERVER);
    }

    @Test
    @DisplayName("Two first claims of one page at once: exactly one holds it, the other opens read-only")
    void concurrentFirstClaim() throws Exception {
        // A's claim runs on the test thread, so the hook can run B's open in the middle of it (gate 1 F1 moved every
        // claim call onto the storage pool, with a deadline).
        serverA.runStorageCallsInline();
        Window[] fromB = new Window[1];
        // Server B claims in the moment between server A's read (no row) and A's insert.
        servers.claimHooks().beforeNextInsert(() -> fromB[0] = serverB.openAsAdmin(admin, ownerId(), PAGE));

        Window fromA = serverA.openAsOwner(owner, PAGE);

        assertThat(fromB[0]).as("precondition: B's open ran inside A's claim").isNotNull();
        assertThat(fromB[0].isEdit()).as("B inserted first and holds the claim").isTrue();
        assertThat(fromA.isReadOnly()).as("A opens read-only").isTrue();
        assertThat(fromA.result.getNotice()).isEqualTo(BagOpenResult.Notice.READ_ONLY_OTHER_SERVER);
        assertThat(servers.claimStore().getAll()).as("one claim row").hasSize(1);
    }

    // ==================== The claim cannot be lost by accident ====================

    @Test
    @DisplayName("A main-thread stall longer than the timeout does not lose the claim: the background renewal keeps the counter moving and the other server stays read-only")
    void aMainThreadStallDoesNotLoseTheClaim() throws Exception {
        Window editing = serverA.openAsOwner(owner, PAGE);
        assertThat(editing.isEdit()).isTrue();
        serverA.startClaimService(20L);
        long counter = claim().getRenewals();

        // Server A's main thread is stalled from here: nothing queued for it runs, and the test thread does not
        // call into A. Real time passes for everyone, on both clocks of both servers.
        for (int third = 1; third <= 9; third++) {
            serverA.advance(TIMEOUT_MS / 3 + 1);
            serverA.wallClock.addAndGet(TIMEOUT_MS / 3 + 1);
            serverB.advance(TIMEOUT_MS / 3 + 1);
            serverB.wallClock.addAndGet(TIMEOUT_MS / 3 + 1);
            long renewed = awaitCounterAbove(counter);
            assertThat(renewed).as("after %d thirds of the timeout, A's background task renewed the claim", third)
                    .isGreaterThan(counter);
            counter = renewed;
            assertThat(triesToEdit(serverB, admin)).as("after %d thirds of the timeout, B is still read-only", third).isFalse();
        }
        assertThat(servers.claimHooks().lastUpdateIfThread()).as("the renewal ran on its own thread")
                .contains("claim renewal");

        serverA.runMainThread();
        assertThat(editing.pickUp(SLOT).isCancelled()).as("A's window is still for editing when its main thread resumes").isFalse();
        editing.close();
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("and its save is written").isZero();
    }

    @Test
    @DisplayName("Clock skew larger than the timeout, in either direction, does not let another server take a renewed claim")
    void clockSkewDoesNotCauseAnEarlyTakeover() throws Exception {
        servers.seedPage(ownerId(), 2, pageWith(SLOT, new ItemStack(Material.EMERALD)));
        assertThat(serverA.openAsOwner(owner, PAGE).isEdit()).isTrue();
        assertThat(serverA.openAsAdmin(otherAdmin, ownerId(), 2).isEdit()).isTrue();
        serverB.wallClock.set(serverA.wallClock.get() + 2 * TIMEOUT_MS);
        serverC.wallClock.set(serverA.wallClock.get() - 2 * TIMEOUT_MS);

        for (int third = 0; third <= 6; third++) {
            Window ahead = serverB.openAsAdmin(admin, ownerId(), PAGE);
            assertThat(ahead.isEdit()).as("B (wall clock 2 timeouts ahead), after %d thirds", third).isFalse();
            ahead.close();
            Window behind = serverC.openAsAdmin(admin, ownerId(), 2);
            assertThat(behind.isEdit()).as("C (wall clock 2 timeouts behind), after %d thirds", third).isFalse();
            behind.close();
            for (Server server : new Server[] {serverA, serverB, serverC}) {
                server.advance(TIMEOUT_MS / 3);
                server.wallClock.addAndGet(TIMEOUT_MS / 3);
            }
            serverA.renewOffMainThread();
        }
    }

    @Test
    @DisplayName("A crashed holder (no renewals) is taken over after one timeout of the observer's own clock, whatever the wall clocks say")
    void aCrashedHolderIsTakenOverAfterOneObserverTimeout() {
        assertThat(serverA.openAsOwner(owner, PAGE).isEdit()).isTrue();
        // Server A crashes: its window is abandoned and it renews nothing again. B's wall clock is far behind.
        serverB.wallClock.set(serverA.wallClock.get() - 2 * TIMEOUT_MS);

        assertThat(triesToEdit(serverB, admin)).as("B first sees the claim: read-only").isFalse();
        serverB.advance(TIMEOUT_MS - 1);
        assertThat(triesToEdit(serverB, admin)).as("one millisecond short of the timeout: still read-only").isFalse();
        serverB.advance(1);
        Window taking = serverB.openAsAdmin(admin, ownerId(), PAGE);
        assertThat(taking.isEdit()).as("a full timeout of the same counter: B takes it over").isTrue();
        taking.pickUp(SLOT);
        taking.close();

        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("B's edit is stored").isZero();
        assertThat(count(admin.getInventory(), Material.DIAMOND)).isEqualTo(1);
    }

    @Test
    @DisplayName("Two observers racing to take over an expired claim: exactly one wins, the other stays read-only")
    void twoObserversRacingToTakeOverOneWins() throws Exception {
        serverC.runStorageCallsInline();
        assertThat(serverA.openAsOwner(owner, PAGE).isEdit()).isTrue();
        assertThat(triesToEdit(serverB, admin)).isFalse();
        assertThat(triesToEdit(serverC, otherAdmin)).isFalse();
        serverB.advance(TIMEOUT_MS);
        serverC.advance(TIMEOUT_MS);

        Window[] fromB = new Window[1];
        // Server B takes the claim over in the moment between C's read and C's conditional write.
        servers.claimHooks().beforeNextUpdateIf(() -> fromB[0] = serverB.openAsAdmin(admin, ownerId(), PAGE));
        Window fromC = serverC.openAsAdmin(otherAdmin, ownerId(), PAGE);

        assertThat(fromB[0]).as("precondition: B's open ran inside C's takeover").isNotNull();
        assertThat(fromB[0].isEdit()).as("B won").isTrue();
        assertThat(fromC.isEdit()).as("C lost: its write was conditional on the counter B moved").isFalse();
    }

    // ==================== Renewal stops with the window ====================

    @Test
    @DisplayName("Renewal stops on close: the claim is released and no later renewal writes it")
    void renewalStopsOnClose() throws Exception {
        Window editing = serverA.openAsOwner(owner, PAGE);
        editing.close();
        RemoteBagEditClaim released = claim();
        assertThat(held(released)).as("released on close").isFalse();

        serverA.advance(TIMEOUT_MS);
        serverA.renewOffMainThread();

        assertThat(claim().getRenewals()).as("not renewed after close").isEqualTo(released.getRenewals());
        assertThat(held(claim())).isFalse();
        assertThat(triesToEdit(serverB, admin)).as("another server can edit at once").isTrue();
    }

    @Test
    @DisplayName("Renewal stops on quit: the quit save runs, the claim is released, and no later renewal writes it")
    void renewalStopsOnQuit() throws Exception {
        Window editing = serverA.openAsOwner(owner, PAGE);
        editing.pickUp(SLOT);
        SharedDatabaseServers.returnCursorToInventory(owner);

        serverA.quit(owner);
        RemoteBagEditClaim released = claim();
        serverA.advance(TIMEOUT_MS);
        serverA.renewOffMainThread();

        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("the change was saved").isZero();
        assertThat(held(released)).as("released on quit").isFalse();
        assertThat(claim().getRenewals()).as("not renewed after quit").isEqualTo(released.getRenewals());
    }

    @Test
    @DisplayName("Renewal stops at module disable: every claim is released and the background task ends")
    void renewalStopsAtDisable() throws Exception {
        servers.seedPage(ownerId(), 2, pageWith(SLOT, new ItemStack(Material.EMERALD)));
        assertThat(serverA.openAsOwner(owner, PAGE).isEdit()).isTrue();
        assertThat(serverA.openAsAdmin(admin, ownerId(), 2).isEdit()).isTrue();
        serverA.startClaimService(20L);
        // The task itself, held before disable: the service drops its own reference when it stops it.
        ScheduledExecutorService renewer =
                (ScheduledExecutorService) UltiRemoteBagTestHelper.getField(serverA.claimService, "renewer");
        assertThat(renewer).as("precondition: the background task runs").isNotNull();
        assertThat(renewer.isShutdown()).isFalse();

        serverA.shutdown();

        assertThat(held(claim())).as("page 1's claim is released").isFalse();
        assertThat(held(servers.claimRow(ownerId(), 2))).as("page 2's claim is released").isFalse();
        assertThat(renewer.isTerminated()).as("the background task ended").isTrue();
        assertThat(UltiRemoteBagTestHelper.getField(serverA.claimService, "renewer")).as("and is not kept").isNull();
        long counter = claim().getRenewals();
        serverA.advance(TIMEOUT_MS);
        Thread.sleep(150L);
        assertThat(claim().getRenewals()).as("nothing renews after disable").isEqualTo(counter);
    }

    // ==================== Holder side: a lost claim, a refused save ====================

    @Test
    @DisplayName("A failed renewal turns the holder's window read-only at once and gives back the items put in during the session")
    void aFailedRenewalMakesTheWindowReadOnly() throws Exception {
        owner.getInventory().addItem(new ItemStack(Material.EMERALD));
        Window editing = serverA.openAsOwner(owner, PAGE);
        putIn(editing, OTHER_SLOT, Material.EMERALD);
        // Something outside this server rewrites the claim (another token, the counter moved).
        RemoteBagEditClaim stolen = claim();
        stolen.setHolderRun("intruder-run");
        stolen.setHolderToken("intruder-token");
        stolen.setRenewals(stolen.getRenewals() + 1);
        servers.claimStore().updateCounted(stolen);

        serverA.advance(TIMEOUT_MS / 3 + 1);
        serverA.renewOffMainThread();
        serverA.runMainThread();

        assertThat(owner.nextMessage()).as("the owner is told").isEqualTo("bag_claim_lost_read_only");
        assertThat(editing.pickUp(SLOT).isCancelled()).as("the window is read-only now").isTrue();
        assertThat(count(owner.getInventory(), Material.EMERALD)).as("the emerald put in is given back").isEqualTo(1);
        verify(serverA.logger, times(1)).warn(contains("log_bag_items_returned"));
        editing.close();
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.EMERALD)).as("nothing of the window is stored").isZero();
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).isEqualTo(1);
        assertThat(servers.total(Material.EMERALD, ownerId(), owner, admin)).as("the emerald exists once").isEqualTo(1);
    }

    @Test
    @DisplayName("A refused save gives back the items the player put in during the session, with one log line")
    void aRefusedSaveReturnsThePutInItems() {
        owner.getInventory().addItem(new ItemStack(Material.EMERALD, 3));
        Window editing = serverA.openAsOwner(owner, PAGE);
        putIn(editing, OTHER_SLOT, Material.EMERALD);
        // A writer outside the claim changes the stored page while the window is open.
        servers.writePageElsewhere(ownerId(), PAGE, pageWith(SLOT + 1, new ItemStack(Material.GOLD_INGOT)));

        editing.close();

        assertThat(count(owner.getInventory(), Material.EMERALD)).as("all three emeralds are with the owner").isEqualTo(3);
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.EMERALD)).as("none in the stored page").isZero();
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.GOLD_INGOT)).as("the other writer's page stays").isEqualTo(1);
        verify(serverA.logger, times(1)).warn(contains("log_bag_items_returned"));
    }

    @Test
    @DisplayName("Given back with a full inventory, the rest drops at the player's feet")
    void givenBackItemsThatDoNotFitDropAtTheFeet() {
        Window editing = serverA.openAsOwner(owner, PAGE);
        owner.setItemOnCursor(new ItemStack(Material.EMERALD));
        editing.place(OTHER_SLOT);
        // Every slot the inventory has (storage, armour, off-hand): nothing fits.
        for (int slot = 0; slot < owner.getInventory().getSize(); slot++) {
            owner.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        assertThat(owner.getInventory().addItem(new ItemStack(Material.EMERALD))).as("precondition: the inventory is full").isNotEmpty();
        servers.writePageElsewhere(ownerId(), PAGE, pageWith(SLOT + 1, new ItemStack(Material.GOLD_INGOT)));

        editing.close();

        long dropped = owner.getWorld().getEntitiesByClass(Item.class).stream()
                .filter(item -> item.getItemStack().getType() == Material.EMERALD)
                .mapToLong(item -> item.getItemStack().getAmount()).sum();
        assertThat(dropped).as("the emerald lies at the owner's feet").isEqualTo(1);
    }

    @Test
    @DisplayName("Pinned (maintainer decision): an item taken out during a session whose save is refused is not taken back")
    void itemsTakenOutAreNotTakenBack() {
        Window editing = serverA.openAsOwner(owner, PAGE);
        editing.pickUp(SLOT);
        SharedDatabaseServers.returnCursorToInventory(owner);
        // A writer outside the module rewrites the page, still holding the diamond.
        servers.writePageElsewhere(ownerId(), PAGE, new ItemStack[] {new ItemStack(Material.DIAMOND), new ItemStack(Material.GOLD_INGOT)});

        editing.close();

        assertThat(count(owner.getInventory(), Material.DIAMOND)).as("the owner keeps the diamond").isEqualTo(1);
        assertThat(servers.total(Material.DIAMOND, ownerId(), owner))
                .as("documented limitation: a writer outside the module can duplicate it").isEqualTo(2);
    }

    // ==================== Release, one server, administration ====================

    @Test
    @DisplayName("Pinned: on one server, the owner edits and an administrator gets read-only with the in-use notice")
    void ownerThenAdministratorOnOneServer() {
        Window ownersPage = serverA.openAsOwner(owner, PAGE);
        Window adminsView = serverA.openAsAdmin(admin, ownerId(), PAGE);

        assertThat(ownersPage.isEdit()).isTrue();
        assertThat(adminsView.isReadOnly()).isTrue();
        assertThat(adminsView.result.getNotice()).isEqualTo(BagOpenResult.Notice.READ_ONLY_IN_USE);
    }

    @Test
    @DisplayName("Pinned: on one server, an administrator who edits blocks the owner")
    void administratorThenOwnerOnOneServer() {
        serverA.openAsAdmin(admin, ownerId(), PAGE);

        Window ownersPage = serverA.openAsOwner(owner, PAGE);

        assertThat(ownersPage.result.isSuccess()).isFalse();
        assertThat(ownersPage.result.getNotice()).isEqualTo(BagOpenResult.Notice.BLOCKED_BY_ADMIN);
    }

    @Test
    @DisplayName("An administrator's /bag clear and /bag delete are refused while another server edits the page")
    void clearAndDeleteRefusedWhileClaimedElsewhere() {
        Window editing = serverA.openAsOwner(owner, PAGE);
        BagCommand commandOnB = new BagCommand(serverB.plugin, serverB.bagService, serverB.lockService, servers.config());

        commandOnB.clearBag(admin, "Owner", PAGE);
        commandOnB.deleteBag(admin, "Owner", PAGE);

        assertThat(admin.nextMessage()).contains("bag_in_use_cannot_clear");
        assertThat(admin.nextMessage()).contains("bag_in_use_cannot_delete");
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("nothing cleared").isEqualTo(1);
        assertThat(servers.storedRowCount(ownerId(), PAGE)).as("nothing deleted").isEqualTo(1);

        editing.close();
        commandOnB.clearBag(admin, "Owner", PAGE);
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("control: cleared once released").isZero();
        verify(serverA.logger, never()).error(anyString());
    }

    @Test
    @DisplayName("/bag <page> on another server while the page is being edited: read-only, and the owner is told why")
    void openPageTellsTheOwnerWhy() {
        assertThat(serverA.openAsAdmin(admin, ownerId(), PAGE).isEdit()).isTrue();
        BagCommand commandOnB = new BagCommand(serverB.plugin, serverB.bagService, serverB.lockService, servers.config());

        commandOnB.openPage(owner, PAGE);

        assertThat(owner.nextMessage()).isEqualTo("bag_read_only_other_server");
        assertThat(held(claim())).as("A's claim is untouched").isTrue();
    }

    @Test
    @DisplayName("Refresh on the owner's read-only page stays read-only while another server edits; once released, the owner takes it as its owner")
    void refreshUpgradesTheOwnerOnceReleased() {
        Window editing = serverA.openAsAdmin(admin, ownerId(), PAGE);
        Window readOnly = serverB.openAsOwner(owner, PAGE);
        assertThat(readOnly.isReadOnly()).isTrue();
        String tokenOfA = claim().getHolderToken();

        readOnly.click(REFRESH_SLOT);
        assertThat(owner.nextMessage()).isEqualTo("bag_read_only_other_server");
        assertThat(claim().getHolderToken()).as("still A's claim").isEqualTo(tokenOfA);

        editing.close();
        readOnly.click(REFRESH_SLOT);

        assertThat(held(claim())).as("the owner on B claimed the page").isTrue();
        assertThat(claim().getHolderToken()).isNotEqualTo(tokenOfA);
        assertThat(serverB.lockService.getLockInfo(ownerId(), PAGE))
                .hasValueSatisfying(lock -> assertThat(lock.getLockType()).isEqualTo(LockType.OWNER));
    }
}
