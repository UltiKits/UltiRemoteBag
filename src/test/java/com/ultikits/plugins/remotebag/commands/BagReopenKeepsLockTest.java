package com.ultikits.plugins.remotebag.commands;

import com.ultikits.plugins.remotebag.MockBukkitSupport;
import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.BagLockInfo;
import com.ultikits.plugins.remotebag.entity.BagOpenResult;
import com.ultikits.plugins.remotebag.enums.AccessMode;
import com.ultikits.plugins.remotebag.enums.LockType;
import com.ultikits.plugins.remotebag.gui.RemoteBagContentGUI;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.InMemoryRemoteBagStore;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import mc.obliviate.inventory.Gui;
import mc.obliviate.inventory.InventoryAPI;

import org.bukkit.Material;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Re-running {@code /bag <page>} or {@code /bag see <player> <page>} on a page the sender already
 * holds in edit mode (<a href="https://github.com/UltiKits/UltiRemoteBag/issues/41">UltiRemoteBag#41</a>).
 *
 * <h2>The defect</h2>
 * The lock service answers "own lock, keep editing" without taking a new lock, and the new page's
 * {@code Gui#open()} then fires the GUI library's fake close event for the page still open. That
 * close is the OLD page's {@code onClose}, which saves and releases the lock. The new page opens in
 * edit mode with no lock held, so a second player can take an edit lock on the same stored page.
 *
 * <h2>What makes a vacuous pass impossible here</h2>
 * Everything on the path is real: the GUI library's own listener ({@link InventoryAPI#init()}), a
 * real {@link BagLockService}, and a real {@link RemoteBagService} over an in-memory store. The
 * assertions read the lock the service holds and the row the store holds, never a recorded call.
 * Each re-run case is paired with a first-open control proving the harness does take the lock, so
 * "not locked after the re-run" cannot come from a harness that never locks at all.
 */
@DisplayName("Re-opening a page you already hold keeps its lock (UltiRemoteBag#41)")
class BagReopenKeepsLockTest {

    private static final int PAGE = 1;
    private static final int CONTENT_SLOT = 0;

    private ServerMock server;
    private PlayerMock owner;
    private PlayerMock admin;
    private InventoryAPI inventoryApi;

    private BagCommand command;
    private RemoteBagService bagService;
    private BagLockService lockService;
    private InMemoryRemoteBagStore store;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkitSupport.bootstrapLiveServer();
        UltiRemoteBagTestHelper.setUp();

        inventoryApi = new InventoryAPI(MockBukkit.createMockPlugin("ObliviateHost"));
        inventoryApi.init();

        store = new InMemoryRemoteBagStore();
        RemoteBagConfig config = UltiRemoteBagTestHelper.createDefaultConfig();

        UltiToolsPlugin mockPlugin = mock(UltiToolsPlugin.class);
        lenient().when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(mockPlugin.getLogger()).thenReturn(mock(PluginLogger.class));

        bagService = new RemoteBagService(mockPlugin, config);
        UltiRemoteBagTestHelper.setField(bagService, "dataOperator", store);

        lockService = new BagLockService();
        UltiRemoteBagTestHelper.setField(lockService, "plugin", mockPlugin);
        UltiRemoteBagTestHelper.setFieldIfPresent(lockService, "config", config);
        command = new BagCommand(mockPlugin, bagService, lockService, config);

        owner = server.addPlayer("Owner");
        admin = server.addPlayer("Admin");
        admin.setOp(true);
        // /bag see resolves its target through getOfflinePlayer(name).hasPlayedBefore().
        server.getPlayerList().setFirstPlayed(owner.getUniqueId(), 1L);

        // The owner's page 1 exists and is empty.
        bagService.setBagPage(owner.getUniqueId(), PAGE, new ItemStack[45]);
    }

    @AfterEach
    void tearDown() throws Exception {
        HandlerList.unregisterAll(inventoryApi.getListener());
        UltiRemoteBagTestHelper.tearDown();
        MockBukkitSupport.safeUnmock();
    }

    @Test
    @DisplayName("Control: the first /bag 1 takes the owner's lock")
    void firstOpenTakesTheOwnersLock() {
        command.openPage(owner, PAGE);

        assertOwnerHoldsTheLock("control: this harness's first open does lock the page");
        assertThat(currentPage(owner)).as("control: the page is open").isNotNull();
    }

    @Test
    @DisplayName("After the owner re-runs /bag 1 on the open page, the owner still holds the lock")
    void ownerRerunKeepsTheLock() {
        command.openPage(owner, PAGE);
        assertOwnerHoldsTheLock("precondition: the first open locked the page");

        command.openPage(owner, PAGE);

        assertOwnerHoldsTheLock("the re-run must leave page 1 locked by its owner");
        RemoteBagContentGUI reopened = currentPage(owner);
        assertThat(reopened).as("the owner is looking at page 1 again").isNotNull();
        assertThat(modeOf(reopened)).as("in edit mode").isEqualTo(AccessMode.EDIT);
    }

    @Test
    @DisplayName("After the owner re-runs /bag 1, an administrator's /bag see opens read-only")
    void ownerRerunLeavesTheAdminReadOnly() {
        command.openPage(owner, PAGE);
        command.openPage(owner, PAGE);

        command.seePlayerBagPage(admin, "Owner", PAGE);

        RemoteBagContentGUI adminsPage = currentPage(admin);
        assertThat(adminsPage).as("the administrator's page opened").isNotNull();
        assertThat(modeOf(adminsPage))
                .as("a second editor on the owner's open page is exactly the defect")
                .isEqualTo(AccessMode.READ_ONLY);
        assertOwnerHoldsTheLock("and the administrator's open did not take the lock");
    }

    @Test
    @DisplayName("Mirror: after an administrator re-runs /bag see on a page they hold, the owner is blocked")
    void adminRerunKeepsTheAdminLock() {
        command.seePlayerBagPage(admin, "Owner", PAGE);
        assertAdminHoldsTheLock("precondition: the administrator took the edit lock");

        command.seePlayerBagPage(admin, "Owner", PAGE);

        assertAdminHoldsTheLock("the re-run must leave page 1 locked by the administrator");
        assertThat(modeOf(currentPage(admin))).as("still editing").isEqualTo(AccessMode.EDIT);

        BagOpenResult ownersTry = lockService.ownerOpen(owner.getUniqueId(), PAGE, owner);
        assertThat(ownersTry.isSuccess())
                .as("the owner must wait while the administrator edits, not become a second editor")
                .isFalse();
    }

    @Test
    @DisplayName("An item placed into the page before the re-run is in the stored page and in the re-opened page")
    void anEditBeforeTheRerunIsKept() {
        command.openPage(owner, PAGE);
        currentPage(owner).getInventory().setItem(CONTENT_SLOT, new ItemStack(Material.DIAMOND));

        command.openPage(owner, PAGE);

        assertThat(store.storedContents(owner.getUniqueId().toString(), PAGE))
                .as("the old page's edit was written before the new lock was decided")
                .contains("minecraft:diamond");
        assertThat(currentPage(owner).getInventory().getItem(CONTENT_SLOT))
                .as("and the re-opened page shows it")
                .isEqualTo(new ItemStack(Material.DIAMOND));
    }

    @Test
    @DisplayName("A re-open the lock then refuses still saves the page that was open")
    void refusedReopenKeepsTheOpenPagesEdit() throws Exception {
        owner.addAttachment(MockBukkit.createMockPlugin("Perms"), "ultibag.pages.2", true);
        bagService.setBagPage(owner.getUniqueId(), 2, new ItemStack[45]);
        // An administrator holds page 1's edit lock (they have it open), so the owner's /bag 1 is refused.
        command.seePlayerBagPage(admin, "Owner", PAGE);
        assertAdminHoldsTheLock("precondition: the administrator holds page 1");
        command.openPage(owner, 2);
        currentPage(owner).getInventory().setItem(CONTENT_SLOT, new ItemStack(Material.EMERALD));

        command.openPage(owner, PAGE);

        assertThat(store.storedContents(owner.getUniqueId().toString(), 2))
                .as("closing page 2 before the refused lock decision saved it")
                .contains("minecraft:emerald");
        assertThat(lockService.getLockInfo(owner.getUniqueId(), 2))
                .as("and released page 2's lock").isEmpty();
        assertAdminHoldsTheLock("the refusal left the administrator's lock alone");
    }

    @Test
    @DisplayName("An administrator re-running /bag see on a page they view read-only is still a read-only viewer")
    void readOnlyAdminRerunStaysARegisteredViewer() throws Exception {
        command.openPage(owner, PAGE);
        command.seePlayerBagPage(admin, "Owner", PAGE);
        assertThat(modeOf(currentPage(admin))).as("precondition: read-only").isEqualTo(AccessMode.READ_ONLY);

        command.seePlayerBagPage(admin, "Owner", PAGE);

        @SuppressWarnings("unchecked")
        java.util.Map<String, java.util.Set<java.util.UUID>> sessions =
                (java.util.Map<String, java.util.Set<java.util.UUID>>) UltiRemoteBagTestHelper.getField(lockService, "readOnlySessions");
        assertThat(sessions.get(owner.getUniqueId() + ":" + PAGE))
                .as("the re-run must not drop the administrator from the owner's read-only viewers,"
                        + " or they stop receiving the owner's notices")
                .contains(admin.getUniqueId());
    }

    private void assertOwnerHoldsTheLock(String why) {
        Optional<BagLockInfo> lock = lockService.getLockInfo(owner.getUniqueId(), PAGE);
        assertThat(lock).as(why).isPresent();
        assertThat(lock.get().getHolderUuid()).as(why).isEqualTo(owner.getUniqueId());
        assertThat(lock.get().getLockType()).as(why).isEqualTo(LockType.OWNER);
    }

    private void assertAdminHoldsTheLock(String why) {
        Optional<BagLockInfo> lock = lockService.getLockInfo(owner.getUniqueId(), PAGE);
        assertThat(lock).as(why).isPresent();
        assertThat(lock.get().getHolderUuid()).as(why).isEqualTo(admin.getUniqueId());
        assertThat(lock.get().getLockType()).as(why).isEqualTo(LockType.ADMIN);
    }

    /** The page's access mode; the class exposes no getter, so this reads the field it decides by. */
    private static AccessMode modeOf(RemoteBagContentGUI page) {
        try {
            return (AccessMode) UltiRemoteBagTestHelper.getField(page, "accessMode");
        } catch (Exception e) {
            throw new AssertionError("could not read accessMode", e);
        }
    }

    private RemoteBagContentGUI currentPage(PlayerMock viewer) {
        Gui gui = InventoryAPI.getInstance().getPlayersCurrentGui(viewer);
        return gui instanceof RemoteBagContentGUI ? (RemoteBagContentGUI) gui : null;
    }
}
