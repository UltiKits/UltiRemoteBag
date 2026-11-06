package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.commands.BagCommand;
import com.ultikits.plugins.remotebag.gui.RemoteBagContentGUI;
import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers;
import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.Server;
import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.Window;
import mc.obliviate.inventory.InventoryAPI;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Path;

import static com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.count;
import static com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.pageWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Closing unsaved manual pages is refused; this is not volatile escrow masquerading as durable storage. */
class ManualSaveCloseTest {
    @TempDir Path dir;
    private SharedDatabaseServers servers;
    private Server a;
    private Server b;
    private PlayerMock owner;
    private PlayerMock admin;

    @BeforeEach
    void setUp() throws Exception {
        servers = SharedDatabaseServers.start(dir, backend());
        MockBukkit.createMockPlugin("UltiTools");
        a = servers.newServer("A");
        b = servers.newServer("B");
        a.healthyCallsGetAGenerousDeadline();
        b.healthyCallsGetAGenerousDeadline();
        owner = servers.live().addPlayer("Owner");
        admin = servers.live().addPlayer("Admin");
        servers.seedPage(owner.getUniqueId(), 1, pageWith(0, new ItemStack(Material.DIAMOND)));
        when(servers.config().isRequireManualSave()).thenReturn(true);
    }

    protected SharedDatabaseServers.Backend backend() {
        return SharedDatabaseServers.Backend.SQLITE;
    }

    @AfterEach
    void tearDown() throws Exception {
        RemoteBagContentGUI.keepOpenEditWindowsForDisable();
        if (a != null) a.stopClaimService();
        if (b != null) b.stopClaimService();
        if (servers != null) servers.stop();
    }

    private Window changedPage() {
        Window page = a.openAsOwner(owner, 1);
        assertThat(page.isEdit()).isTrue();
        page.gui.getInventory().setItem(0, null);
        owner.getInventory().addItem(new ItemStack(Material.DIAMOND));
        page.gui.getInventory().setItem(5, new ItemStack(Material.EMERALD, 2));
        return page;
    }

    private void reopenTick(PlayerMock viewer) {
        servers.live().getScheduler().performTicks(2L);
        // HumanEntityMock.openInventory already calls InventoryOpenEvent.callEvent(); never dispatch it twice.
    }

    @Test
    void closeIsRefusedAndReopensTheExactInventoryWithoutReadingOrWritingThePage() {
        Window page = changedPage();
        Inventory original = page.gui.getInventory();
        String token = a.claimService.tokenOf(owner.getUniqueId(), 1);
        String before = servers.storedRow(owner.getUniqueId(), 1).getContents();
        page.close();
        assertThat(servers.storedRow(owner.getUniqueId(), 1).getContents()).isEqualTo(before);
        reopenTick(owner);
        assertThat(InventoryAPI.getInstance().getPlayersCurrentGui(owner)).isSameAs(page.gui);
        assertThat(owner.getOpenInventory().getTopInventory()).isSameAs(original);
        assertThat(count(original, Material.EMERALD)).isEqualTo(2);
        assertThat(count(original, Material.DIAMOND)).isZero();
        assertThat(count(owner.getInventory(), Material.DIAMOND)).isOne();
        assertThat(a.claimService.tokenOf(owner.getUniqueId(), 1)).isEqualTo(token);
        assertThat(a.claimService.hasKeptWrite(owner.getUniqueId(), 1)).isFalse();
    }

    @Test
    void pendingReopenRemainsLockedAgainstLocalAndOtherServerEditors() {
        Window page = changedPage();
        page.close();
        assertThat(a.lockService.adminOpen(owner.getUniqueId(), 1, admin).isReadOnlyMode()).isTrue();
        assertThat(b.lockService.adminOpen(owner.getUniqueId(), 1, admin).isReadOnlyMode()).isTrue();
    }

    @Test
    void commandNavigationDoesNotReplaceTheUnsavedWindowOrItsClaim() {
        Window page = changedPage();
        String token = a.claimService.tokenOf(owner.getUniqueId(), 1);
        BagCommand command = new BagCommand(a.plugin, a.bagService, a.lockService, servers.config());
        command.openMainPage(owner);
        command.openPage(owner, 1);
        assertThat(InventoryAPI.getInstance().getPlayersCurrentGui(owner)).isSameAs(page.gui);
        assertThat(a.claimService.tokenOf(owner.getUniqueId(), 1)).isEqualTo(token);
        assertThat(count(servers.storedPage(owner.getUniqueId(), 1), Material.EMERALD)).isZero();
    }

    @Test
    void navigationDuringTheOneTickGapIsRefused() {
        Window page = changedPage();
        page.close();
        new BagCommand(a.plugin, a.bagService, a.lockService, servers.config()).openMainPage(owner);
        reopenTick(owner);
        assertThat(InventoryAPI.getInstance().getPlayersCurrentGui(owner)).isSameAs(page.gui);
        assertThat(count(page.gui.getInventory(), Material.EMERALD)).isEqualTo(2);
    }

    @Test
    void explicitCommandCanSaveDuringTheGapAndFinishThePendingClose() throws Exception {
        changedPage().close();
        new BagCommand(a.plugin, a.bagService, a.lockService, servers.config()).saveBag(owner);
        servers.live().getScheduler().performTicks(2L);
        servers.awaitStorageCallsSettled();
        assertThat(InventoryAPI.getInstance().getPlayersCurrentGui(owner)).isNull();
        assertThat(count(servers.storedPage(owner.getUniqueId(), 1), Material.EMERALD)).isEqualTo(2);
        assertThat(servers.claimRow(owner.getUniqueId(), 1).getHolderToken()).isEmpty();
    }

    @Test
    void saveButtonThenCloseIsAllowedWithoutHoldingOrReopening() throws Exception {
        Window page = changedPage();
        page.save();
        page.close();
        servers.live().getScheduler().performTicks(2L);
        servers.awaitStorageCallsSettled();
        assertThat(count(servers.storedPage(owner.getUniqueId(), 1), Material.EMERALD)).isEqualTo(2);
        assertThat(InventoryAPI.getInstance().getPlayersCurrentGui(owner)).isNull();
        assertThat(servers.claimRow(owner.getUniqueId(), 1).getHolderToken()).isEmpty();
    }

    @Test
    void adminMustSaveTheirOwnEditsWithTheButtonNotTheirOwnBagCommand() {
        Window page = a.openAsAdmin(admin, owner.getUniqueId(), 1);
        page.gui.getInventory().setItem(5, new ItemStack(Material.EMERALD));
        page.close();
        new BagCommand(a.plugin, a.bagService, a.lockService, servers.config()).saveBag(admin);
        reopenTick(admin);
        assertThat(InventoryAPI.getInstance().getPlayersCurrentGui(admin)).isSameAs(page.gui);
        assertThat(count(servers.storedPage(owner.getUniqueId(), 1), Material.EMERALD)).isZero();
        page.save();
        page.close();
        assertThat(count(servers.storedPage(owner.getUniqueId(), 1), Material.EMERALD)).isOne();
    }

    @Test
    void quitDuringPendingReopenUsesTheNormalSafetySave() throws Exception {
        changedPage().close();
        a.quit(owner);
        servers.live().getScheduler().performTicks(2L);
        servers.awaitStorageCallsSettled();
        assertThat(InventoryAPI.getInstance().getPlayersCurrentGui(owner)).isNull();
        assertThat(count(servers.storedPage(owner.getUniqueId(), 1), Material.EMERALD)).isEqualTo(2);
        assertThat(servers.claimRow(owner.getUniqueId(), 1).getHolderToken()).isEmpty();
    }

    @Test
    void disableDuringPendingReopenIncludesTheWindowInTheExistingFinalFlush() throws Exception {
        changedPage().close();
        a.shutdown();
        servers.live().getScheduler().performTicks(2L);
        servers.awaitStorageCallsSettled();
        assertThat(count(servers.storedPage(owner.getUniqueId(), 1), Material.EMERALD)).isEqualTo(2);
        assertThat(InventoryAPI.getInstance().getPlayersCurrentGui(owner)).isNull();
    }

    @Test
    void repeatedCloseAttemptsKeepExactlyOneCurrentInventoryAndClaim() {
        Window page = changedPage();
        String token = a.claimService.tokenOf(owner.getUniqueId(), 1);
        for (int i = 0; i < 3; i++) {
            page.close();
            reopenTick(owner);
            assertThat(InventoryAPI.getInstance().getPlayersCurrentGui(owner)).isSameAs(page.gui);
        }
        assertThat(a.claimService.tokenOf(owner.getUniqueId(), 1)).isEqualTo(token);
        assertThat(count(servers.storedPage(owner.getUniqueId(), 1), Material.EMERALD)).isZero();
    }

    @Test
    void backButtonRefusesNavigationWhileUnsaved() {
        Window page = changedPage();
        page.click(45);
        assertThat(InventoryAPI.getInstance().getPlayersCurrentGui(owner)).isSameAs(page.gui);
        assertThat(count(servers.storedPage(owner.getUniqueId(), 1), Material.EMERALD)).isZero();
    }

    @Test
    void lostClaimDuringTheGapReturnsDepositsAndNeverReopensAnEditableWindow() {
        Window page = changedPage();
        String token = a.claimService.tokenOf(owner.getUniqueId(), 1);
        page.close();
        RemoteBagContentGUI.claimLost(owner.getUniqueId(), owner.getUniqueId(), 1, token);
        servers.live().getScheduler().performTicks(2L);
        assertThat(InventoryAPI.getInstance().getPlayersCurrentGui(owner)).isNull();
        assertThat(count(owner.getInventory(), Material.EMERALD)).isEqualTo(2);
        assertThat(count(servers.storedPage(owner.getUniqueId(), 1), Material.EMERALD)).isZero();
    }

    @Test
    void unconfirmedClaimDuringTheGapUsesExistingKeptWriteRecovery() throws Exception {
        Window page = changedPage();
        String token = a.claimService.tokenOf(owner.getUniqueId(), 1);
        page.close();
        RemoteBagContentGUI.claimTroubled(owner.getUniqueId(), owner.getUniqueId(), 1, token);
        assertThat(a.claimService.hasKeptWrite(owner.getUniqueId(), 1)).isTrue();
        servers.live().getScheduler().performTicks(2L);
        a.renewOffMainThread();
        servers.awaitStorageCallsSettled();
        assertThat(InventoryAPI.getInstance().getPlayersCurrentGui(owner)).isNull();
        assertThat(count(servers.storedPage(owner.getUniqueId(), 1), Material.EMERALD)).isEqualTo(2);
        assertThat(count(owner.getInventory(), Material.EMERALD)).isZero();
    }

    @Test
    void reloadingDefaultPolicyDuringTheGapCompletesASafetySave() {
        changedPage().close();
        when(servers.config().isRequireManualSave()).thenReturn(false);
        servers.live().getScheduler().performTicks(2L);
        assertThat(InventoryAPI.getInstance().getPlayersCurrentGui(owner)).isNull();
        assertThat(count(servers.storedPage(owner.getUniqueId(), 1), Material.EMERALD)).isEqualTo(2);
    }

    @Test
    void cancelledReopenSafetySavesInsteadOfLosingTheLiveInventory() throws Exception {
        Window page = changedPage();
        page.close();
        org.bukkit.event.Listener canceller = new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
                event.setCancelled(true);
            }
        };
        org.bukkit.Bukkit.getPluginManager().registerEvents(canceller,
                org.bukkit.Bukkit.getPluginManager().getPlugin("UltiTools"));
        try {
            servers.live().getScheduler().performTicks(2L);
        } finally {
            org.bukkit.event.HandlerList.unregisterAll(canceller);
        }
        servers.awaitStorageCallsSettled();
        assertThat(InventoryAPI.getInstance().getPlayersCurrentGui(owner)).isNull();
        assertThat(count(servers.storedPage(owner.getUniqueId(), 1), Material.EMERALD)).isEqualTo(2);
        assertThat(servers.claimRow(owner.getUniqueId(), 1).getHolderToken()).isEmpty();
    }

    @Test
    void defaultModeStillSavesImmediatelyOnClose() {
        when(servers.config().isRequireManualSave()).thenReturn(false);
        changedPage().close();
        assertThat(count(servers.storedPage(owner.getUniqueId(), 1), Material.EMERALD)).isEqualTo(2);
    }
}
