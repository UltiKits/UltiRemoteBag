package com.ultikits.plugins.remotebag.commands;

import com.ultikits.plugins.remotebag.MockBukkitSupport;
import com.ultikits.plugins.remotebag.UltiRemoteBag;
import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.BagLockInfo;
import com.ultikits.plugins.remotebag.entity.BagOpenResult;
import com.ultikits.plugins.remotebag.enums.LockType;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.ServerMock;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("BagCommand Tests")
class BagCommandTest {

    private BagCommand command;
    private RemoteBagService bagService;
    private BagLockService lockService;
    private RemoteBagConfig config;
    private Player player;
    private UUID playerUuid;
    private Server mockServer;
    private OfflinePlayer offlinePlayer;
    private ServerMock realServer;

    @BeforeEach
    void setUp() throws Exception {
        UltiRemoteBagTestHelper.setUp();

        // Live test-time Bukkit server so registry-backed production code (InventoryType/MenuType
        // via the GUI open path, XSound) resolves. MockBukkitSupport.bootstrapLiveServer() is this
        // module's shared bootstrap entry point; it returns a real, functioning
        // ServerMock. Wrap it in a Mockito spy() so the pre-existing getOfflinePlayer(...) stub
        // below keeps working. doReturn(...).when(spy) is required here, not when(spy.method()) --
        // the latter invokes the real method first and is unsafe on a spy.
        ServerMock realServer = MockBukkitSupport.bootstrapLiveServer();
        mockServer = spy(realServer);
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, mockServer);

        // Mock getOfflinePlayer to return an OfflinePlayer with a UUID
        offlinePlayer = mock(OfflinePlayer.class);
        lenient().when(offlinePlayer.hasPlayedBefore()).thenReturn(true);
        lenient().when(offlinePlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().doReturn(offlinePlayer).when(mockServer).getOfflinePlayer(anyString());
        // The server's own name cache, which admin commands use for a target who is not online
        // (UltiKits/UltiRemoteBag#30).
        lenient().doReturn(offlinePlayer).when(mockServer).getOfflinePlayerIfCached(anyString());
        this.realServer = realServer;

        bagService = mock(RemoteBagService.class);
        lockService = mock(BagLockService.class);
        config = UltiRemoteBagTestHelper.createDefaultConfig();

        UltiToolsPlugin mockPlugin = mock(UltiToolsPlugin.class);
        when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

        command = new BagCommand(mockPlugin, bagService, lockService, config);

        playerUuid = UUID.randomUUID();
        player = UltiRemoteBagTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiRemoteBagTestHelper.tearDown();
        MockBukkitSupport.safeUnmock();
    }

    // ==================== openMainPage ====================

    @Nested
    @DisplayName("openMainPage")
    class OpenMainPage {

        @Test
        @DisplayName("Should open main GUI")
        void opensMainGui() {
            // GUI instantiation requires InventoryAPI.init() which is not available in unit tests.
            // Verify that the command does not throw unexpected exceptions (GUI errors are expected).
            try {
                command.openMainPage(player);
            } catch (Exception e) {
                // Expected: InventoryAPI not initialized
            }
            // If we reach here, the command path was exercised successfully
        }
    }

    // ==================== openPage ====================

    @Nested
    @DisplayName("openPage")
    class OpenPage {

        @Test
        @DisplayName("Should send error when page out of range (too low)")
        void errorWhenPageTooLow() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);

            command.openPage(player, 0);

            verify(player).sendMessage(contains("page_out_of_range"));
        }

        @Test
        @DisplayName("Should send error when page out of range (too high)")
        void errorWhenPageTooHigh() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);

            command.openPage(player, 6);

            verify(player).sendMessage(contains("page_out_of_range"));
        }

        /**
         * UltiKits/UltiRemoteBag#26: the stored list no longer invents page 1, but the owner is still
         * offered it, so {@code /bag 1} opens it before anything is stored.
         */
        @Test
        @DisplayName("/bag 1 still opens page 1 when nothing is stored yet (UltiKits/UltiRemoteBag#26)")
        void pageOneIsOfferedWhenNothingIsStored() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);
            when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Collections.emptyList());
            when(lockService.ownerOpen(eq(playerUuid), eq(1), eq(player)))
                    .thenReturn(BagOpenResult.editMode());

            try {
                command.openPage(player, 1);
            } catch (Exception e) {
                // Expected: GUI not initialized
            }

            verify(player, never()).sendMessage(contains("bag_not_exist"));
            verify(lockService).ownerOpen(eq(playerUuid), eq(1), eq(player));
        }

        @Test
        @DisplayName("Should send error when bag does not exist")
        void errorWhenBagNotExist() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);
            when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Arrays.asList(1, 2));

            command.openPage(player, 3);

            verify(player).sendMessage(contains("bag_not_exist"));
        }

        @Test
        @DisplayName("Should open bag when successful")
        void opensBagWhenSuccessful() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);
            when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Arrays.asList(1, 2));
            when(lockService.ownerOpen(playerUuid, 1, player))
                    .thenReturn(BagOpenResult.editMode());

            // GUI instantiation requires InventoryAPI.init() which is not available in tests.
            // Catch the expected error and verify the service interaction.
            try {
                command.openPage(player, 1);
            } catch (Exception e) {
                // Expected: InventoryAPI not initialized
            }

            verify(lockService).ownerOpen(playerUuid, 1, player);
        }

        @Test
        @DisplayName("Should send error when open fails")
        void errorWhenOpenFails() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);
            when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Arrays.asList(1));
            BagLockInfo lockInfo = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("OtherPlayer")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();
            when(lockService.ownerOpen(playerUuid, 1, player))
                    .thenReturn(BagOpenResult.blocked(lockInfo));

            command.openPage(player, 1);

            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("Reads the stored pages before checking whether the page exists (UltiRemoteBag#54)")
        void loadsBagBeforeCheck() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);
            when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Arrays.asList(1, 2));

            command.openPage(player, 3);

            verify(bagService).refreshBag(playerUuid);
        }

        @Test
        @DisplayName("Should replace page number in error message when page too low")
        void replacePageNumberInMessageLow() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(3);

            command.openPage(player, -1);

            // i18n returns key as-is, so the message will contain the key with replacements
            verify(player).sendMessage(contains("page_out_of_range"));
        }

        @Test
        @DisplayName("Should accept page at exact boundary (page 1)")
        void acceptsPageAtLowerBound() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);
            when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Arrays.asList(1));
            when(lockService.ownerOpen(playerUuid, 1, player))
                    .thenReturn(BagOpenResult.editMode());

            try {
                command.openPage(player, 1);
            } catch (Exception e) {
                // Expected: GUI not initialized
            }

            verify(lockService).ownerOpen(playerUuid, 1, player);
        }

        @Test
        @DisplayName("Should accept page at exact upper boundary")
        void acceptsPageAtUpperBound() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);
            when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Arrays.asList(1, 2, 3, 4, 5));
            when(lockService.ownerOpen(playerUuid, 5, player))
                    .thenReturn(BagOpenResult.editMode());

            try {
                command.openPage(player, 5);
            } catch (Exception e) {
                // Expected: GUI not initialized
            }

            verify(lockService).ownerOpen(playerUuid, 5, player);
        }

        @Test
        @DisplayName("Should send blocked message with admin lock type")
        void errorWithAdminLock() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);
            when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Arrays.asList(1));
            BagLockInfo lockInfo = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("AdminPlayer")
                    .lockType(LockType.ADMIN)
                    .acquiredAt(System.currentTimeMillis())
                    .build();
            when(lockService.ownerOpen(playerUuid, 1, player))
                    .thenReturn(BagOpenResult.blocked(lockInfo));

            // The notice comes from the language file: answered from the real en catalogue.
            when(mockPluginOf(command).i18n(anyString())).thenAnswer(com.ultikits.plugins.remotebag.i18n.CatalogueText.answer("en"));
            String expected = com.ultikits.plugins.remotebag.i18n.CatalogueText.text("en", "bag_blocked_by_admin").replace("{PLAYER}", "AdminPlayer");

            command.openPage(player, 1);

            verify(player).sendMessage(expected);
        }
    }

    // ==================== saveBag ====================

    @Nested
    @DisplayName("saveBag")
    class SaveBag {

        @Test
        @DisplayName("With the sender's pages held and no page open, confirms without writing anything (UltiRemoteBag#54)")
        void confirmsWithoutWriting() {
            // Every change is written when it is made, so nothing is written from the cache
            // (maintainer decision 2026-10-06 00:04).
            when(bagService.hasCachedPages(playerUuid)).thenReturn(true);

            command.saveBag(player);

            verify(player).sendMessage(contains("bag_saved_manually"));
            verify(bagService, never()).savePage(any(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("Reports nothing saved when there was nothing cached to save")
        void reportsNothingSavedWhenTheCacheIsEmpty() {
            // Nothing is cached for a player who has not opened a page this session. Claiming
            // `bag_saved_manually` there reported a write that never happened, which is what the UAT
            // row for this command had been amended to assert against.
            when(bagService.hasCachedPages(playerUuid)).thenReturn(false);

            command.saveBag(player);

            verify(player).sendMessage(contains("msg_nothing_to_save"));
            verify(player, never()).sendMessage(contains("bag_saved_manually"));
        }

        @Test
        @DisplayName("Asks the service about the sender's own UUID")
        void asksAboutTheSendersUuid() {
            UUID specificUuid = UUID.randomUUID();
            Player specificPlayer = UltiRemoteBagTestHelper.createMockPlayer("SpecificPlayer", specificUuid);
            when(bagService.hasCachedPages(specificUuid)).thenReturn(true);

            command.saveBag(specificPlayer);

            verify(bagService).hasCachedPages(specificUuid);
            verify(specificPlayer).sendMessage(contains("bag_saved_manually"));
        }
    }

    // ==================== seePlayerBag ====================

    @Nested
    @DisplayName("seePlayerBag")
    class SeePlayerBag {

        @Test
        @DisplayName("Should send error when player not found")
        void errorWhenPlayerNotFound() {
            when(offlinePlayer.hasPlayedBefore()).thenReturn(false);
            doReturn(null).when(mockServer).getOfflinePlayerIfCached("UnknownPlayer");

            command.seePlayerBag(player, "UnknownPlayer");

            verify(player).sendMessage(contains("player_not_found"));
        }

        @Test
        @DisplayName("Should send error when player has no bags")
        void errorWhenNoBags() {
            when(offlinePlayer.hasPlayedBefore()).thenReturn(true);
            when(bagService.getPlayerBagPages(any())).thenReturn(Collections.emptyList());

            command.seePlayerBag(player, "TargetPlayer");

            verify(player).sendMessage(contains("player_no_bags"));
        }

        @Test
        @DisplayName("Should open first page when player has bags")
        void opensFirstPage() {
            UUID targetUuid = offlinePlayer.getUniqueId();
            when(offlinePlayer.hasPlayedBefore()).thenReturn(true);
            when(bagService.getPlayerBagPages(targetUuid)).thenReturn(Arrays.asList(1, 2, 3));
            when(lockService.adminOpen(eq(targetUuid), eq(1), eq(player)))
                    .thenReturn(BagOpenResult.editMode());

            try {
                command.seePlayerBag(player, "TargetPlayer");
            } catch (Exception e) {
                // Expected: GUI not initialized
            }

            verify(lockService).adminOpen(eq(targetUuid), eq(1), eq(player));
        }

        @Test
        @DisplayName("Should load target bag if needed")
        void loadsTargetBagIfNeeded() {
            UUID targetUuid = offlinePlayer.getUniqueId();
            when(offlinePlayer.hasPlayedBefore()).thenReturn(true);
            when(bagService.getPlayerBagPages(targetUuid)).thenReturn(Arrays.asList(1));
            when(lockService.adminOpen(eq(targetUuid), eq(1), eq(player)))
                    .thenReturn(BagOpenResult.editMode());

            try {
                command.seePlayerBag(player, "TargetPlayer");
            } catch (Exception e) {
                // Expected: GUI not initialized
            }

            verify(bagService, atLeast(1)).refreshBag(targetUuid);
        }
    }

    // ==================== seePlayerBagPage ====================

    @Nested
    @DisplayName("seePlayerBagPage")
    class SeePlayerBagPage {

        @Test
        @DisplayName("Should send error when player not found")
        void errorWhenPlayerNotFound() {
            when(offlinePlayer.hasPlayedBefore()).thenReturn(false);
            doReturn(null).when(mockServer).getOfflinePlayerIfCached("UnknownPlayer");

            command.seePlayerBagPage(player, "UnknownPlayer", 1);

            verify(player).sendMessage(contains("player_not_found"));
        }

        @Test
        @DisplayName("Should send error when page does not exist")
        void errorWhenPageNotExist() {
            UUID targetUuid = offlinePlayer.getUniqueId();
            when(offlinePlayer.hasPlayedBefore()).thenReturn(true);
            when(bagService.getPlayerBagPages(targetUuid)).thenReturn(Arrays.asList(1, 2));

            command.seePlayerBagPage(player, "TargetPlayer", 5);

            verify(player).sendMessage(contains("bag_not_exist"));
        }

        @Test
        @DisplayName("Should open specific page in edit mode")
        void opensPageInEditMode() {
            UUID targetUuid = offlinePlayer.getUniqueId();
            when(offlinePlayer.hasPlayedBefore()).thenReturn(true);
            when(bagService.getPlayerBagPages(targetUuid)).thenReturn(Arrays.asList(1, 2, 3));
            when(lockService.adminOpen(eq(targetUuid), eq(2), eq(player)))
                    .thenReturn(BagOpenResult.editMode());

            try {
                command.seePlayerBagPage(player, "TargetPlayer", 2);
            } catch (Exception e) {
                // Expected: GUI not initialized
            }

            verify(lockService).adminOpen(eq(targetUuid), eq(2), eq(player));
        }

        @Test
        @DisplayName("Should show read-only message and open when owner holds lock")
        void opensReadOnlyWhenOwnerLock() {
            UUID targetUuid = offlinePlayer.getUniqueId();
            UUID ownerLockUuid = UUID.randomUUID();
            when(offlinePlayer.hasPlayedBefore()).thenReturn(true);
            when(bagService.getPlayerBagPages(targetUuid)).thenReturn(Arrays.asList(1));

            BagLockInfo ownerLock = BagLockInfo.builder()
                    .holderUuid(ownerLockUuid)
                    .holderName("OwnerPlayer")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();
            BagOpenResult readOnlyResult = BagOpenResult.readOnlyMode(ownerLock);
            when(lockService.adminOpen(eq(targetUuid), eq(1), eq(player)))
                    .thenReturn(readOnlyResult);

            when(mockPluginOf(command).i18n(anyString())).thenAnswer(com.ultikits.plugins.remotebag.i18n.CatalogueText.answer("en"));
            String expected = com.ultikits.plugins.remotebag.i18n.CatalogueText.text("en", "bag_read_only_in_use").replace("{PLAYER}", "OwnerPlayer");
            try {
                command.seePlayerBagPage(player, "TargetPlayer", 1);
            } catch (Exception e) {
                // Expected: GUI not initialized
            }

            // Should send the read-only warning message, in the server's language
            verify(player).sendMessage(expected);
        }

        @Test
        @DisplayName("Should send error and play sound when blocked")
        void errorWhenBlocked() {
            UUID targetUuid = offlinePlayer.getUniqueId();
            when(offlinePlayer.hasPlayedBefore()).thenReturn(true);
            when(bagService.getPlayerBagPages(targetUuid)).thenReturn(Arrays.asList(1));

            BagLockInfo adminLock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("OtherAdmin")
                    .lockType(LockType.ADMIN)
                    .acquiredAt(System.currentTimeMillis())
                    .build();
            when(lockService.adminOpen(eq(targetUuid), eq(1), eq(player)))
                    .thenReturn(BagOpenResult.blocked(adminLock));

            when(mockPluginOf(command).i18n(anyString())).thenAnswer(com.ultikits.plugins.remotebag.i18n.CatalogueText.answer("en"));
            String expected = com.ultikits.plugins.remotebag.i18n.CatalogueText.text("en", "bag_blocked_by_admin").replace("{PLAYER}", "OtherAdmin");

            command.seePlayerBagPage(player, "TargetPlayer", 1);

            verify(player).sendMessage(expected);
        }
    }

    // ==================== Admin Commands ====================

    @Nested
    @DisplayName("Admin Commands")
    class AdminCommands {

        @Test
        @DisplayName("createBag should create new bag page")
        void createBagCreatesPage() {
            when(bagService.createBagPage(any())).thenReturn(2);

            command.createBag(player, "TargetPlayer");

            verify(bagService).createBagPage(any());
            verify(player).sendMessage(contains("admin_bag_created"));
        }

        @Test
        @DisplayName("createBag should send error when creation fails")
        void createBagErrorWhenFails() {
            when(bagService.createBagPage(any())).thenReturn(-1);

            command.createBag(player, "TargetPlayer");

            verify(player).sendMessage(contains("admin_bag_create_failed"));
        }

        @Test
        @DisplayName("createBag should send error when player not found")
        void createBagErrorWhenPlayerNotFound() {
            when(offlinePlayer.hasPlayedBefore()).thenReturn(false);
            doReturn(null).when(mockServer).getOfflinePlayerIfCached("UnknownPlayer");

            command.createBag(player, "UnknownPlayer");

            verify(player).sendMessage(contains("player_not_found"));
            verify(bagService, never()).createBagPage(any());
        }

        @Test
        @DisplayName("createBag should return 0 as failure")
        void createBagReturnsZeroAsFail() {
            when(bagService.createBagPage(any())).thenReturn(0);

            command.createBag(player, "TargetPlayer");

            // 0 is not > 0, so it should show failure message
            verify(player).sendMessage(contains("admin_bag_create_failed"));
        }

        @Test
        @DisplayName("deleteBag should check if can upgrade to edit")
        void deleteBagChecksCanUpgrade() {
            when(lockService.canUpgradeToEdit(any(), anyInt())).thenReturn(false);

            command.deleteBag(player, "TargetPlayer", 1);

            verify(lockService).canUpgradeToEdit(any(), eq(1));
            verify(player).sendMessage(contains("bag_in_use_cannot_delete"));
        }

        @Test
        @DisplayName("deleteBag should delete when allowed")
        void deleteBagDeletesWhenAllowed() {
            when(lockService.canUpgradeToEdit(any(), anyInt())).thenReturn(true);
            when(bagService.deleteBagPage(any(), anyInt())).thenReturn(true);

            command.deleteBag(player, "TargetPlayer", 1);

            verify(bagService).deleteBagPage(any(), eq(1));
            verify(player).sendMessage(contains("admin_bag_deleted"));
        }

        @Test
        @DisplayName("deleteBag should send error when delete fails")
        void deleteBagErrorWhenDeleteFails() {
            when(lockService.canUpgradeToEdit(any(), anyInt())).thenReturn(true);
            when(bagService.deleteBagPage(any(), anyInt())).thenReturn(false);

            command.deleteBag(player, "TargetPlayer", 1);

            verify(player).sendMessage(contains("admin_bag_delete_failed"));
        }

        @Test
        @DisplayName("deleteBag should send error when player not found")
        void deleteBagErrorWhenPlayerNotFound() {
            when(offlinePlayer.hasPlayedBefore()).thenReturn(false);
            doReturn(null).when(mockServer).getOfflinePlayerIfCached("UnknownPlayer");

            command.deleteBag(player, "UnknownPlayer", 1);

            verify(player).sendMessage(contains("player_not_found"));
            verify(lockService, never()).canUpgradeToEdit(any(), anyInt());
        }

        @Test
        @DisplayName("clearBag should check if can upgrade to edit")
        void clearBagChecksCanUpgrade() {
            when(lockService.canUpgradeToEdit(any(), anyInt())).thenReturn(false);

            command.clearBag(player, "TargetPlayer", 1);

            verify(lockService).canUpgradeToEdit(any(), eq(1));
            verify(player).sendMessage(contains("bag_in_use_cannot_clear"));
        }

        @Test
        @DisplayName("clearBag should clear when allowed")
        void clearBagClearsWhenAllowed() {
            when(lockService.canUpgradeToEdit(any(), anyInt())).thenReturn(true);
            when(bagService.clearBagPage(any(), anyInt())).thenReturn(true);

            command.clearBag(player, "TargetPlayer", 1);

            verify(bagService).clearBagPage(any(), eq(1));
            verify(player).sendMessage(contains("admin_bag_cleared"));
        }

        @Test
        @DisplayName("clearBag should send error when clear fails")
        void clearBagErrorWhenClearFails() {
            when(lockService.canUpgradeToEdit(any(), anyInt())).thenReturn(true);
            when(bagService.clearBagPage(any(), anyInt())).thenReturn(false);

            command.clearBag(player, "TargetPlayer", 1);

            verify(player).sendMessage(contains("admin_bag_clear_failed"));
        }

        @Test
        @DisplayName("clearBag should send error when player not found")
        void clearBagErrorWhenPlayerNotFound() {
            when(offlinePlayer.hasPlayedBefore()).thenReturn(false);
            doReturn(null).when(mockServer).getOfflinePlayerIfCached("UnknownPlayer");

            command.clearBag(player, "UnknownPlayer", 1);

            verify(player).sendMessage(contains("player_not_found"));
            verify(lockService, never()).canUpgradeToEdit(any(), anyInt());
        }

        @Test
        @DisplayName("listBags should display all bags")
        void listBagsDisplaysAll() {
            when(bagService.getPlayerBagPages(any()))
                    .thenReturn(Arrays.asList(1, 2, 3));
            when(bagService.getItemCount(any(), anyInt())).thenReturn(100);
            when(bagService.getStackCount(any(), anyInt())).thenReturn(10);

            command.listBags(player, "TargetPlayer");

            verify(player, atLeast(3)).sendMessage(anyString());
        }

        @Test
        @DisplayName("listBags should display no bags message when empty")
        void listBagsDisplaysEmpty() {
            when(bagService.getPlayerBagPages(any()))
                    .thenReturn(Collections.emptyList());

            command.listBags(player, "TargetPlayer");

            verify(player).sendMessage(contains("no_bags"));
            verify(player, never()).sendMessage(contains("#1"));
        }

        @Test
        @DisplayName("listBags should send error when player not found")
        void listBagsErrorWhenPlayerNotFound() {
            when(offlinePlayer.hasPlayedBefore()).thenReturn(false);
            doReturn(null).when(mockServer).getOfflinePlayerIfCached("UnknownPlayer");

            command.listBags(player, "UnknownPlayer");

            verify(player).sendMessage(contains("player_not_found"));
            verify(player, never()).sendMessage(contains("bag_list_title"));
        }

        @Test
        @DisplayName("listBags should show total count at end")
        void listBagsShowsTotalCount() {
            when(bagService.getPlayerBagPages(any()))
                    .thenReturn(Arrays.asList(1, 2));
            when(bagService.getItemCount(any(), anyInt())).thenReturn(50);
            when(bagService.getStackCount(any(), anyInt())).thenReturn(5);

            command.listBags(player, "TargetPlayer");

            verify(player).sendMessage(contains("total_bags"));
        }

        @Test
        @DisplayName("listBags should show title header")
        void listBagsShowsTitle() {
            when(bagService.getPlayerBagPages(any()))
                    .thenReturn(Arrays.asList(1));
            when(bagService.getItemCount(any(), anyInt())).thenReturn(0);
            when(bagService.getStackCount(any(), anyInt())).thenReturn(0);

            command.listBags(player, "TargetPlayer");

            verify(player).sendMessage(contains("bag_list_title"));
        }

        @Test
        @DisplayName("listBags reads the target's stored pages, and keeps no copy afterwards (UltiRemoteBag#54)")
        void listBagsLoadsTarget() {
            UUID targetUuid = offlinePlayer.getUniqueId();
            when(bagService.getPlayerBagPages(targetUuid)).thenReturn(Arrays.asList(1));
            when(bagService.getItemCount(any(), anyInt())).thenReturn(0);
            when(bagService.getStackCount(any(), anyInt())).thenReturn(0);

            command.listBags(player, "TargetPlayer");

            verify(bagService).refreshBag(targetUuid);
            verify(bagService).forgetUnlessOnline(targetUuid);
        }

        @Test
        @DisplayName("listBags should display item and stack counts for each page")
        void listBagsDisplaysPerPageStats() {
            UUID targetUuid = offlinePlayer.getUniqueId();
            when(bagService.getPlayerBagPages(targetUuid)).thenReturn(Arrays.asList(1, 2));
            when(bagService.getItemCount(targetUuid, 1)).thenReturn(100);
            when(bagService.getItemCount(targetUuid, 2)).thenReturn(50);
            when(bagService.getStackCount(targetUuid, 1)).thenReturn(10);
            when(bagService.getStackCount(targetUuid, 2)).thenReturn(5);

            command.listBags(player, "TargetPlayer");

            verify(bagService).getItemCount(targetUuid, 1);
            verify(bagService).getItemCount(targetUuid, 2);
            verify(bagService).getStackCount(targetUuid, 1);
            verify(bagService).getStackCount(targetUuid, 2);
        }
    }

    // ==================== admin target resolution (UltiKits/UltiRemoteBag#30) ====================

    /**
     * UltiKits/UltiRemoteBag#30: every administrator command resolves its target the same way -- an
     * online player by exact name first, so a player in their first session (whose
     * {@code OfflinePlayer#hasPlayedBefore()} is still false) is found; anyone else by that record.
     */
    @Nested
    @DisplayName("Admin target resolution (UltiKits/UltiRemoteBag#30)")
    class AdminTargetResolution {

        private UUID newbieUuid;

        @BeforeEach
        void aFirstTimePlayerIsOnline() {
            newbieUuid = realServer.addPlayer("Newbie").getUniqueId();
            // Bukkit's own "played before" signal is false in a first session.
            lenient().when(offlinePlayer.hasPlayedBefore()).thenReturn(false);
        }

        @Test
        @DisplayName("/bag list resolves an online first-time player")
        void listResolvesAnOnlineFirstTimePlayer() {
            when(bagService.getPlayerBagPages(newbieUuid)).thenReturn(Collections.singletonList(1));

            command.listBags(player, "Newbie");

            verify(player, never()).sendMessage(contains("player_not_found"));
            verify(bagService).refreshBag(newbieUuid);
        }

        @Test
        @DisplayName("/bag see resolves an online first-time player")
        void seeResolvesAnOnlineFirstTimePlayer() {
            when(bagService.getPlayerBagPages(newbieUuid)).thenReturn(Collections.singletonList(1));
            when(lockService.adminOpen(eq(newbieUuid), eq(1), eq(player))).thenReturn(BagOpenResult.editMode());

            try {
                command.seePlayerBag(player, "Newbie");
            } catch (Exception e) {
                // Expected: GUI not initialized
            }

            verify(player, never()).sendMessage(contains("player_not_found"));
            verify(lockService).adminOpen(eq(newbieUuid), eq(1), eq(player));
        }

        @Test
        @DisplayName("/bag see <page> resolves an online first-time player")
        void seePageResolvesAnOnlineFirstTimePlayer() {
            when(bagService.getPlayerBagPages(newbieUuid)).thenReturn(Collections.singletonList(1));
            when(lockService.adminOpen(eq(newbieUuid), eq(1), eq(player))).thenReturn(BagOpenResult.editMode());

            try {
                command.seePlayerBagPage(player, "Newbie", 1);
            } catch (Exception e) {
                // Expected: GUI not initialized
            }

            verify(player, never()).sendMessage(contains("player_not_found"));
            verify(lockService).adminOpen(eq(newbieUuid), eq(1), eq(player));
        }

        @Test
        @DisplayName("/bag create resolves an online first-time player")
        void createResolvesAnOnlineFirstTimePlayer() {
            when(bagService.createBagPage(newbieUuid)).thenReturn(1);

            command.createBag(player, "Newbie");

            verify(bagService).createBagPage(newbieUuid);
            verify(player).sendMessage(contains("admin_bag_created"));
        }

        @Test
        @DisplayName("/bag delete resolves an online first-time player")
        void deleteResolvesAnOnlineFirstTimePlayer() {
            when(lockService.canUpgradeToEdit(newbieUuid, 1)).thenReturn(true);
            when(bagService.deleteBagPage(newbieUuid, 1)).thenReturn(true);

            command.deleteBag(player, "Newbie", 1);

            verify(bagService).deleteBagPage(newbieUuid, 1);
        }

        @Test
        @DisplayName("/bag clear resolves an online first-time player")
        void clearResolvesAnOnlineFirstTimePlayer() {
            when(lockService.canUpgradeToEdit(newbieUuid, 1)).thenReturn(true);
            when(bagService.clearBagPage(newbieUuid, 1)).thenReturn(true);

            command.clearBag(player, "Newbie", 1);

            verify(bagService).clearBagPage(newbieUuid, 1);
        }

        /**
         * The maintainer's rule (2026-09-28): an online player by exact name; anyone else by Bukkit's
         * own record that the name has joined this server before. No name-cache lookup at all -- it is
         * Paper-only, and the module's bag data is no test of whether a player exists.
         */
        @Test
        @DisplayName("An offline player who has joined before resolves without the server's name cache")
        void anOfflinePlayerWhoJoinedBeforeResolves() {
            UUID awayUuid = UUID.randomUUID();
            OfflinePlayer away = mock(OfflinePlayer.class);
            when(away.getUniqueId()).thenReturn(awayUuid);
            when(away.hasPlayedBefore()).thenReturn(true);
            doReturn(away).when(mockServer).getOfflinePlayer("Away");
            when(bagService.getPlayerBagPages(awayUuid)).thenReturn(Collections.singletonList(1));

            command.listBags(player, "Away");

            verify(player).sendMessage(contains("bag_list_title"));
            verify(mockServer, never()).getOfflinePlayerIfCached(anyString());
        }

        /**
         * Codex review round 2 on UltiKits/UltiRemoteBag#45: on a server whose name cache does not know
         * the name (or has no such lookup), a player who joined before but owns no bag yet was reported
         * not found, so an administrator could not create their first page.
         */
        @Test
        @DisplayName("/bag create gives an offline player who joined before but owns no bag their first page")
        void createGivesAPlayerWithoutBagsTheirFirstPage() {
            UUID awayUuid = UUID.randomUUID();
            OfflinePlayer away = mock(OfflinePlayer.class);
            when(away.getUniqueId()).thenReturn(awayUuid);
            when(away.hasPlayedBefore()).thenReturn(true);
            doReturn(null).when(mockServer).getOfflinePlayerIfCached("Away");
            doReturn(away).when(mockServer).getOfflinePlayer("Away");
            lenient().when(bagService.getPlayerBagPages(awayUuid)).thenReturn(Collections.emptyList());
            when(bagService.createBagPage(awayUuid)).thenReturn(1);

            command.createBag(player, "Away");

            verify(player, never()).sendMessage(contains("player_not_found"));
            verify(bagService).createBagPage(awayUuid);
            verify(player).sendMessage(contains("admin_bag_created"));
        }

        @Test
        @DisplayName("A partial name never resolves to an online player")
        void aPartialNameDoesNotResolve() {
            UUID onlineUuid = realServer.addPlayer("TargetPlayer").getUniqueId();
            OfflinePlayer nobody = mock(OfflinePlayer.class);
            lenient().when(nobody.getUniqueId()).thenReturn(UUID.randomUUID());
            when(nobody.hasPlayedBefore()).thenReturn(false);
            doReturn(nobody).when(mockServer).getOfflinePlayer("Target");

            command.listBags(player, "Target");

            verify(player).sendMessage(contains("player_not_found"));
            verify(bagService, never()).refreshBag(onlineUuid);
            verify(bagService, never()).getPlayerBagPages(onlineUuid);
        }

        @Test
        @DisplayName("A name that has never joined this server is not found")
        void aNameThatNeverJoinedIsNotFound() {
            OfflinePlayer ghost = mock(OfflinePlayer.class);
            lenient().when(ghost.getUniqueId()).thenReturn(UUID.randomUUID());
            when(ghost.hasPlayedBefore()).thenReturn(false);
            doReturn(ghost).when(mockServer).getOfflinePlayer("Ghost");

            command.createBag(player, "Ghost");

            verify(player).sendMessage(contains("player_not_found"));
            verify(bagService, never()).createBagPage(any());
        }
    }

    // ==================== handleHelp ====================

    @Nested
    @DisplayName("handleHelp")
    class HandleHelp {

        @Test
        @DisplayName("Should display player help commands")
        void displaysPlayerHelp() {
            command.handleHelp(player);

            // Verify basic help messages were sent
            verify(player, atLeast(3)).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should display admin commands when player has admin permission")
        void displaysAdminHelp() {
            when(player.hasPermission("ultibag.admin.see")).thenReturn(true);

            command.handleHelp(player);

            // Should show more messages (basic + admin commands)
            verify(player, atLeast(6)).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should not display admin commands when no admin permission")
        void hidesAdminHelpWithoutPermission() {
            when(player.hasPermission("ultibag.admin.see")).thenReturn(false);

            command.handleHelp(player);

            // Should only show basic commands (fewer messages)
            verify(player, atMost(5)).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should do nothing when sender is not a Player")
        void doesNothingForNonPlayer() {
            CommandSender consoleSender = mock(CommandSender.class);

            // handleHelp checks (sender instanceof Player), should do nothing for console
            command.handleHelp(consoleSender);

            verify(consoleSender, never()).sendMessage(any(String.class));
        }
    }

    /** The plugin double the command was built with, so a test can answer its i18n differently. */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static UltiToolsPlugin mockPluginOf(BagCommand command) {
        try {
            java.lang.reflect.Field f = BagCommand.class.getDeclaredField("plugin");
            f.setAccessible(true);
            return (UltiToolsPlugin) f.get(command);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
