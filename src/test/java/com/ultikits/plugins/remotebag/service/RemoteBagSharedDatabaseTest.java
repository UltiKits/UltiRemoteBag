package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers;
import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.Server;
import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.Window;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Path;
import java.util.UUID;

import static com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.count;
import static com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.pageWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Bag pages on servers that share one database
 * (<a href="https://github.com/UltiKits/UltiRemoteBag/issues/54">UltiRemoteBag#54</a>, Phase 17 plan 17-84).
 *
 * <h2>The defect</h2>
 * A server kept every page of a player's bag in memory from the first read -- including after an
 * administrator only looked at another player's bag -- and wrote the whole cached copy back: every
 * cached page of that player on any save, and every cached page of every player at shutdown. A page
 * changed on another server in between was overwritten: items taken out there came back here
 * (duplicated), items put in there were gone (lost).
 *
 * <h2>The decision this implements (maintainer, 2026-10-06 00:04)</h2>
 * The cache belongs to the session that opened it and is never written back; nothing is written at
 * shutdown; a page is saved with {@code updateIf} on the stored contents its window read, and a miss is
 * logged and not written.
 *
 * <h2>What makes a vacuous pass impossible here</h2>
 * Two servers' own beans over one real framework store ({@link SharedDatabaseServers}); items are counted
 * in the stored rows and in the players' inventories and cursors, and each test first asserts that the
 * other server's change really reached the stored row.
 */
@DisplayName("A bag page changed on another server sharing the database is never overwritten by a stale copy (UltiRemoteBag#54)")
class RemoteBagSharedDatabaseTest {

    private static final int PAGE = 1;
    private static final int SLOT = 0;

    @TempDir
    Path dir;

    private SharedDatabaseServers servers;
    private Server serverA;
    private Server serverB;
    private PlayerMock owner;
    private PlayerMock admin;

    @BeforeEach
    void setUp() throws Exception {
        servers = SharedDatabaseServers.start(dir, backend());
        serverA = servers.newServer("A");
        serverB = servers.newServer("B");
        owner = servers.live().addPlayer("Owner");
        admin = servers.live().addPlayer("Admin");
    }

    @AfterEach
    void tearDown() throws Exception {
        servers.stop();
    }

    /** The shared storage; {@link RemoteBagSharedDatabaseSqliteTest} runs every case on real SQLite. */
    protected SharedDatabaseServers.Backend backend() {
        return SharedDatabaseServers.Backend.JSON;
    }

    private UUID ownerId() {
        return owner.getUniqueId();
    }

    /** Moves one item from the player's own inventory into a content slot of their open window. */
    private static void putIn(Window window, int slot, Material material) {
        window.viewer.getInventory().removeItem(new ItemStack(material));
        window.viewer.setItemOnCursor(new ItemStack(material));
        window.place(slot);
    }

    @Test
    @DisplayName("Tracer: an administrator's old copy is not written back at shutdown -- the diamond the owner took out on another server is not duplicated")
    void anAdministratorsOldCopyIsNotWrittenBackAtShutdown() throws Exception {
        servers.seedPage(ownerId(), PAGE, pageWith(SLOT, new ItemStack(Material.DIAMOND)));
        assertThat(servers.total(Material.DIAMOND, ownerId(), owner, admin))
                .as("precondition: exactly one diamond exists").isEqualTo(1);

        // An administrator on server A looks at the owner's page 1 and closes it.
        Window adminsView = serverA.openAsAdmin(admin, ownerId(), PAGE);
        assertThat(adminsView.isEdit()).as("precondition: nobody holds the page on A").isTrue();
        adminsView.close();

        // The owner, on server B, takes the diamond out of page 1 and closes it.
        Window ownersPage = serverB.openAsOwner(owner, PAGE);
        assertThat(ownersPage.isEdit()).as("precondition: the owner edits the page on B").isTrue();
        ownersPage.pickUp(SLOT);
        ownersPage.close();
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND))
                .as("precondition: server B's change reached the stored row").isZero();
        assertThat(count(owner.getInventory(), Material.DIAMOND))
                .as("precondition: the owner holds the diamond").isEqualTo(1);

        // Server A stops.
        serverA.shutdown();

        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND))
                .as("server A's old copy is not written back over server B's change").isZero();
        assertThat(servers.total(Material.DIAMOND, ownerId(), owner, admin))
                .as("the diamond exists exactly once -- not in the owner's hands and again in the bag").isEqualTo(1);
    }

    @Test
    @DisplayName("Shutdown writes no cached page: a page another server emptied after this server read it stays empty")
    void shutdownWritesNoCachedPage() throws Exception {
        servers.seedPage(ownerId(), PAGE, pageWith(SLOT, new ItemStack(Material.EMERALD)));
        Window ownersPage = serverA.openAsOwner(owner, PAGE);
        ownersPage.close();

        servers.writePageElsewhere(ownerId(), PAGE, new ItemStack[SharedDatabaseServers.PAGE_SIZE]);
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.EMERALD))
                .as("precondition: the other server's change reached the stored row").isZero();

        serverA.shutdown();

        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.EMERALD))
                .as("nothing cached on server A is written at its shutdown").isZero();
    }

    @Test
    @DisplayName("A page save is written only if the stored contents are still what the window read; a miss is logged and not written")
    void aSaveOverAChangeMadeAfterTheWindowReadIsNotWritten() {
        servers.seedPage(ownerId(), PAGE, pageWith(SLOT, new ItemStack(Material.DIAMOND)));
        Window ownersPage = serverB.openAsOwner(owner, PAGE);
        assertThat(ownersPage.shown(SLOT).getType()).as("precondition: the window read the diamond").isEqualTo(Material.DIAMOND);

        // Another writer replaces the page while the window is open.
        servers.writePageElsewhere(ownerId(), PAGE, pageWith(SLOT + 1, new ItemStack(Material.EMERALD)));

        ownersPage.close();

        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.EMERALD))
                .as("the other writer's contents stay").isEqualTo(1);
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND))
                .as("the window's copy is not written over them").isZero();
        verify(serverB.logger, times(1)).error("log_bag_update_failed");
    }

    @Test
    @DisplayName("Saving one page writes only that page: another page of the same player that another server changed is not touched")
    void savingOnePageDoesNotWriteAnotherCachedPage() {
        servers.seedPage(ownerId(), PAGE, pageWith(SLOT, new ItemStack(Material.DIAMOND)));
        servers.seedPage(ownerId(), 2, pageWith(SLOT, new ItemStack(Material.EMERALD)));

        // Server A reads both of the owner's pages (an administrator's view of page 1 loads the bag).
        Window adminsView = serverA.openAsAdmin(admin, ownerId(), PAGE);
        // On another server the emerald is taken out of page 2.
        servers.writePageElsewhere(ownerId(), 2, new ItemStack[SharedDatabaseServers.PAGE_SIZE]);
        assertThat(count(servers.storedPage(ownerId(), 2), Material.EMERALD))
                .as("precondition: the other server's change reached page 2").isZero();

        adminsView.save();
        adminsView.close();

        assertThat(count(servers.storedPage(ownerId(), 2), Material.EMERALD))
                .as("page 2 is not written back from server A's copy").isZero();
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND))
                .as("page 1 is as the administrator left it").isEqualTo(1);
    }

    @Test
    @DisplayName("An administrator's view of a player who is not on this server leaves no cache entry once it closes")
    void anAdministratorsViewLeavesNoCacheEntry() throws Exception {
        UUID offlineOwner = UUID.randomUUID();
        servers.seedPage(offlineOwner, PAGE, pageWith(SLOT, new ItemStack(Material.DIAMOND)));

        Window view = serverA.openAsAdmin(admin, offlineOwner, PAGE);
        assertThat(view.shown(SLOT).getType()).as("precondition: the view shows the stored page").isEqualTo(Material.DIAMOND);
        view.close();

        assertThat(serverA.bagService.hasCachedPages(offlineOwner))
                .as("no copy of another player's bag outlives the view that read it").isFalse();
    }

    @Test
    @DisplayName("Pinned: the owner's change is saved when they quit with the window open, and their cache entry is dropped")
    void quittingSavesAndDropsTheOwnersEntry() {
        owner.getInventory().addItem(new ItemStack(Material.EMERALD));
        Window ownersPage = serverA.openAsOwner(owner, PAGE);
        putIn(ownersPage, SLOT, Material.EMERALD);

        serverA.quit(owner);

        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.EMERALD))
                .as("the item put in before quitting is stored").isEqualTo(1);
        assertThat(serverA.bagService.hasCachedPages(ownerId()))
                .as("the owner's cache entry is dropped on quit").isFalse();
        verify(serverA.logger, never()).error(anyString());
    }

    @Test
    @DisplayName("Pinned: on one server, an administrator's create, clear and delete of a page behave as before")
    void createClearDeleteOnOneServer() {
        servers.seedPage(ownerId(), PAGE, pageWith(SLOT, new ItemStack(Material.DIAMOND)));

        int created = serverA.bagService.createBagPage(ownerId());
        assertThat(created).as("the next page after the stored page 1").isEqualTo(2);
        assertThat(servers.storedPages(ownerId())).containsExactlyInAnyOrder(1, 2);

        assertThat(serverA.bagService.clearBagPage(ownerId(), PAGE)).as("clear reports success").isTrue();
        assertThat(count(servers.storedPage(ownerId(), PAGE), Material.DIAMOND)).as("page 1 is empty").isZero();
        assertThat(servers.storedRowCount(ownerId(), PAGE)).as("page 1 is still stored").isEqualTo(1);

        assertThat(serverA.bagService.deleteBagPage(ownerId(), 2)).as("delete reports success").isTrue();
        assertThat(servers.storedPages(ownerId())).containsExactly(1);
        assertThat(serverA.bagService.deleteBagPage(ownerId(), 2)).as("a page that is gone is not deleted twice").isFalse();
    }
}
