package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.entity.BagOpenResult;
import com.ultikits.plugins.remotebag.entity.RemoteBagEditClaim;
import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers;
import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.Server;
import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.Window;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Path;
import java.util.UUID;

import static com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.count;
import static com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.pageWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * One bag page is edited on one server at a time (UltiKits/UltiRemoteBag#54; maintainer decision of
 * 2026-10-06, option A): opening a page for editing claims it in the database ({@code remote_bag_claims});
 * while another server's live claim exists the page opens read-only; a claim left by a crash expires after
 * {@code lock.timeout_seconds}; the holder renews it every third of that while the window is open; it is
 * released when the window closes, when the holder quits, and when the module disables.
 *
 * <h2>What makes a vacuous pass impossible here</h2>
 * Two servers' own beans ({@link SharedDatabaseServers}) over shared stores; each refusal is paired with
 * the same scenario's acceptance (the claim released, expired or never taken), and item counts are read
 * from the stored rows.
 */
@DisplayName("A bag page opened for editing is claimed in the database; another server opens it read-only (UltiRemoteBag#54)")
class BagEditClaimTest {

    private static final int PAGE = 1;
    private static final int SLOT = 0;
    private static final long TIMEOUT_MS = 300_000L;
    /** Bottom row slot 3 -- the Refresh icon of a read-only page. */
    private static final int REFRESH_SLOT = 48;

    @TempDir
    Path dir;

    private SharedDatabaseServers servers;
    private Server serverA;
    private Server serverB;
    private PlayerMock owner;
    private PlayerMock admin;

    private void start(boolean primaryKey) throws Exception {
        servers = primaryKey ? SharedDatabaseServers.start(dir) : SharedDatabaseServers.startWithoutPrimaryKey(dir);
        serverA = servers.newServer("A");
        serverB = servers.newServer("B");
        owner = servers.live().addPlayer("Owner");
        admin = servers.live().addPlayer("Admin");
        servers.seedPage(owner.getUniqueId(), PAGE, pageWith(SLOT, new ItemStack(Material.DIAMOND)));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (servers != null) {
            servers.stop();
        }
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

    @Nested
    @DisplayName("Claim and refusal")
    class ClaimAndRefusal {

        @Test
        @DisplayName("Opening a page for editing claims it: one row <uuid>:<page> holding this server's token and the time")
        void openingForEditClaims() throws Exception {
            start(true);

            Window page = serverA.openAsOwner(owner, PAGE);

            assertThat(page.isEdit()).isTrue();
            RemoteBagEditClaim claim = claim();
            assertThat(claim).as("a claim row for the page").isNotNull();
            assertThat(claim.getId()).isEqualTo(ownerId() + ":" + PAGE);
            assertThat(claim.getPlayerUuid()).isEqualTo(ownerId().toString());
            assertThat(claim.getPageNumber()).isEqualTo(PAGE);
            assertThat(held(claim)).as("it holds a token").isTrue();
            assertThat(claim.getClaimedAt()).isEqualTo(serverA.clock.get());
        }

        @Test
        @DisplayName("While server A edits, an administrator on server B gets the page read-only with the other-server notice; clicks are refused; B's close writes nothing")
        void anotherServersAdministratorIsReadOnly() throws Exception {
            start(true);
            Window editing = serverA.openAsOwner(owner, PAGE);
            assertThat(editing.isEdit()).as("precondition: A edits").isTrue();
            String storedBefore = servers.storedRow(ownerId(), PAGE).getContents();

            Window view = serverB.openAsAdmin(admin, ownerId(), PAGE);

            assertThat(view.isReadOnly()).as("B opens it read-only").isTrue();
            assertThat(view.result.getNotice()).isEqualTo(BagOpenResult.Notice.READ_ONLY_OTHER_SERVER);
            assertThat(view.result.renderMessage(serverB.plugin)).isEqualTo("bag_read_only_other_server");
            assertThat(view.pickUp(SLOT).isCancelled()).as("taking the diamond is refused").isTrue();
            view.close();
            assertThat(servers.storedRow(ownerId(), PAGE).getContents())
                    .as("B's close writes nothing").isEqualTo(storedBefore);
            assertThat(count(admin.getInventory(), Material.DIAMOND)).isZero();
        }

        @Test
        @DisplayName("While an administrator on server A edits, the owner on server B gets the page read-only")
        void theOwnerOnAnotherServerIsReadOnly() throws Exception {
            start(true);
            Window editing = serverA.openAsAdmin(admin, ownerId(), PAGE);
            assertThat(editing.isEdit()).as("precondition: A's administrator edits").isTrue();

            Window page = serverB.openAsOwner(owner, PAGE);

            assertThat(page.isReadOnly()).as("first claimant edits, owner or not").isTrue();
            assertThat(page.result.getNotice()).isEqualTo(BagOpenResult.Notice.READ_ONLY_OTHER_SERVER);
        }

        @Test
        @DisplayName("Two first claims of one page at once: exactly one holds it (the primary key decides), the other opens read-only")
        void concurrentFirstClaimWithPrimaryKey() throws Exception {
            start(true);
            Window[] fromB = new Window[1];
            // Server B claims in the moment between server A's read (no row) and A's insert.
            servers.claimKey().beforeNextInsert(() -> fromB[0] = serverB.openAsAdmin(admin, ownerId(), PAGE));

            Window fromA = serverA.openAsOwner(owner, PAGE);

            assertThat(fromB[0]).as("precondition: B's open ran inside A's claim").isNotNull();
            assertThat(fromB[0].isEdit()).as("B inserted first and holds the claim").isTrue();
            assertThat(fromA.isReadOnly()).as("A's insert fails on the key; A opens read-only").isTrue();
            assertThat(fromA.result.getNotice()).isEqualTo(BagOpenResult.Notice.READ_ONLY_OTHER_SERVER);
        }

        @Test
        @DisplayName("JSON storage ignores a duplicate insert: the read-back still lets only one of two first claims hold the page")
        void concurrentFirstClaimWithoutPrimaryKey() throws Exception {
            start(false);
            Window[] fromB = new Window[1];
            // Server B claims in the moment between server A's read (no row) and A's insert, which the JSON
            // store then ignores and returns from normally.
            servers.claimKey().beforeNextInsert(() -> fromB[0] = serverB.openAsAdmin(admin, ownerId(), PAGE));

            Window fromA = serverA.openAsOwner(owner, PAGE);

            assertThat(fromB[0]).as("precondition: B's open ran inside A's claim").isNotNull();
            assertThat(fromB[0].isEdit()).as("B's claim was stored").isTrue();
            assertThat(fromA.isReadOnly()).as("A reads back B's claim, not its own, and opens read-only").isTrue();
            assertThat(servers.claimStore().getAll()).as("one claim row").hasSize(1);
        }
    }

    @Nested
    @DisplayName("Expiry and renewal")
    class ExpiryAndRenewal {

        @Test
        @DisplayName("A claim left by a crash refuses until lock.timeout_seconds has passed, then the page opens for editing on the other server")
        void crashLeftClaimExpires() throws Exception {
            start(true);
            serverA.openAsOwner(owner, PAGE);
            // Server A crashes: nothing is closed, released or renewed again (the window is simply abandoned).

            serverB.clock.set(serverA.clock.get() + TIMEOUT_MS - 1_000L);
            Window early = serverB.openAsAdmin(admin, ownerId(), PAGE);
            assertThat(early.isReadOnly()).as("before the timeout, read-only").isTrue();
            early.close();

            serverB.clock.set(serverA.clock.get() + TIMEOUT_MS + 1_000L);
            Window late = serverB.openAsAdmin(admin, ownerId(), PAGE);
            assertThat(late.isEdit()).as("after the timeout, B claims and edits").isTrue();
            late.pickUp(SLOT);
            late.close();

            assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("B's edit is stored").isZero();
            assertThat(count(admin.getInventory(), Material.DIAMOND)).isEqualTo(1);
        }

        @Test
        @DisplayName("Renewed every third of the timeout while the window is open, the claim keeps another server read-only for longer than the timeout")
        void renewalKeepsTheClaim() throws Exception {
            start(true);
            Window editing = serverA.openAsOwner(owner, PAGE);
            assertThat(editing.isEdit()).isTrue();

            for (int step = 1; step <= 9; step++) {
                serverA.advance(TIMEOUT_MS / 3);
                serverA.claimService.renewDue();
                serverB.clock.set(serverA.clock.get());
                Window view = serverB.openAsAdmin(admin, ownerId(), PAGE);
                assertThat(view.isReadOnly()).as("after %d thirds of the timeout B is still read-only", step).isTrue();
                view.close();
            }
            assertThat(claim().getClaimedAt()).as("the claim was renewed").isEqualTo(serverA.clock.get());
        }

        @Test
        @DisplayName("A server that stalled past the timeout loses its claim: one SEVERE line, and its later save does not overwrite the other server's edit")
        void aStalledServerLosesItsClaim() throws Exception {
            start(true);
            Window stalled = serverA.openAsOwner(owner, PAGE);
            // Server A stalls; server B's clock moves past the timeout and its administrator takes the page.
            serverB.clock.set(serverA.clock.get() + TIMEOUT_MS + 1_000L);
            Window taking = serverB.openAsAdmin(admin, ownerId(), PAGE);
            assertThat(taking.isEdit()).as("precondition: B took the expired claim").isTrue();
            taking.pickUp(SLOT);
            taking.close();

            serverA.advance(TIMEOUT_MS + 1_000L);
            serverA.claimService.renewDue();
            verify(serverA.logger, times(1)).error(contains("log_bag_claim_lost"));
            serverA.claimService.renewDue();
            verify(serverA.logger, times(1)).error(contains("log_bag_claim_lost"));

            stalled.close();
            assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND))
                    .as("A's stale window is not written over B's edit").isZero();
            assertThat(count(admin.getInventory(), Material.DIAMOND)).as("the diamond exists once, with B's administrator").isEqualTo(1);
            assertThat(servers.total(Material.DIAMOND, ownerId(), owner, admin)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Release")
    class Release {

        @Test
        @DisplayName("Closing the window releases the claim right after the save; the other server can edit at once")
        void releasedOnClose() throws Exception {
            start(true);
            Window editing = serverA.openAsOwner(owner, PAGE);
            editing.pickUp(SLOT);
            editing.close();

            assertThat(held(claim())).as("the token is empty").isFalse();
            assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("saved before release").isZero();
            Window next = serverB.openAsAdmin(admin, ownerId(), PAGE);
            assertThat(next.isEdit()).as("B claims at once").isTrue();
        }

        @Test
        @DisplayName("Quitting with the window open: the quit save runs, then the claim is released")
        void releasedOnQuit() throws Exception {
            start(true);
            Window editing = serverA.openAsOwner(owner, PAGE);
            editing.pickUp(SLOT);
            SharedDatabaseServers.returnCursorToInventory(owner);

            serverA.quit(owner);

            assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("the change was saved").isZero();
            assertThat(held(claim())).as("the token is empty").isFalse();
        }

        @Test
        @DisplayName("Disabling the module releases every claim it holds")
        void releasedOnDisable() throws Exception {
            start(true);
            servers.seedPage(ownerId(), 2, pageWith(SLOT, new ItemStack(Material.EMERALD)));
            Window ownersPage = serverA.openAsOwner(owner, PAGE);
            Window adminsPage = serverA.openAsAdmin(admin, ownerId(), 2);
            assertThat(ownersPage.isEdit()).isTrue();
            assertThat(adminsPage.isEdit()).isTrue();
            assertThat(held(servers.claimRow(ownerId(), 2))).as("precondition: page 2 is claimed").isTrue();

            serverA.shutdown();

            assertThat(held(claim())).as("page 1's claim is released").isFalse();
            assertThat(held(servers.claimRow(ownerId(), 2))).as("page 2's claim is released").isFalse();
        }
    }

    @Nested
    @DisplayName("The owner's own paths")
    class OwnerPaths {

        @Test
        @DisplayName("/bag <page> on another server while the page is being edited: read-only, and the owner is told why")
        void openPageTellsTheOwnerWhy() throws Exception {
            start(true);
            Window editing = serverA.openAsAdmin(admin, ownerId(), PAGE);
            assertThat(editing.isEdit()).isTrue();
            com.ultikits.plugins.remotebag.commands.BagCommand commandOnB = new com.ultikits.plugins.remotebag.commands.BagCommand(
                    serverB.plugin, serverB.bagService, serverB.lockService, servers.config());

            commandOnB.openPage(owner, PAGE);

            assertThat(owner.nextMessage()).isEqualTo("bag_read_only_other_server");
            assertThat(held(claim())).as("A's claim is untouched").isTrue();
        }

        @Test
        @DisplayName("Refresh on the owner's read-only page: still read-only while the other server edits; once released, the owner takes the page as its owner")
        void refreshUpgradesTheOwnerOnceReleased() throws Exception {
            start(true);
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
                    .as("as its owner, not as an administrator")
                    .hasValueSatisfying(lock -> assertThat(lock.getLockType())
                            .isEqualTo(com.ultikits.plugins.remotebag.enums.LockType.OWNER));
        }
    }

    @Nested
    @DisplayName("Single server and administration")
    class SingleServer {

        @Test
        @DisplayName("Pinned: on one server, the owner edits and an administrator gets read-only with the in-use notice, as BagLockService decides")
        void ownerThenAdministratorOnOneServer() throws Exception {
            start(true);
            Window ownersPage = serverA.openAsOwner(owner, PAGE);
            Window adminsView = serverA.openAsAdmin(admin, ownerId(), PAGE);

            assertThat(ownersPage.isEdit()).isTrue();
            assertThat(adminsView.isReadOnly()).isTrue();
            assertThat(adminsView.result.getNotice()).isEqualTo(BagOpenResult.Notice.READ_ONLY_IN_USE);
        }

        @Test
        @DisplayName("Pinned: on one server, an administrator who edits blocks the owner, as BagLockService decides")
        void administratorThenOwnerOnOneServer() throws Exception {
            start(true);
            serverA.openAsAdmin(admin, ownerId(), PAGE);

            Window ownersPage = serverA.openAsOwner(owner, PAGE);

            assertThat(ownersPage.result.isSuccess()).isFalse();
            assertThat(ownersPage.result.getNotice()).isEqualTo(BagOpenResult.Notice.BLOCKED_BY_ADMIN);
        }

        @Test
        @DisplayName("An administrator's /bag clear and /bag delete are refused while another server edits the page")
        void clearAndDeleteRefusedWhileClaimedElsewhere() throws Exception {
            start(true);
            Window editing = serverA.openAsOwner(owner, PAGE);
            assertThat(editing.isEdit()).isTrue();

            assertThat(serverB.lockService.isClaimedElsewhere(ownerId(), PAGE)).as("B sees A's claim").isTrue();
            assertThat(serverA.lockService.isClaimedElsewhere(ownerId(), PAGE)).as("A's own claim is not elsewhere").isFalse();
            com.ultikits.plugins.remotebag.commands.BagCommand commandOnB = new com.ultikits.plugins.remotebag.commands.BagCommand(
                    serverB.plugin, serverB.bagService, serverB.lockService, servers.config());

            commandOnB.clearBag(admin, "Owner", PAGE);
            commandOnB.deleteBag(admin, "Owner", PAGE);

            assertThat(admin.nextMessage()).contains("bag_in_use_cannot_clear");
            assertThat(admin.nextMessage()).contains("bag_in_use_cannot_delete");
            assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("nothing cleared").isEqualTo(1);
            assertThat(servers.storedRowCount(ownerId(), PAGE)).as("nothing deleted").isEqualTo(1);

            // Control: once A's window is closed, the same command clears the page.
            editing.close();
            assertThat(serverB.lockService.isClaimedElsewhere(ownerId(), PAGE)).as("released: no longer held").isFalse();
            commandOnB.clearBag(admin, "Owner", PAGE);
            assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("control: cleared").isZero();
            verify(serverA.logger, never()).error(anyString());
        }
    }
}
