package com.ultikits.plugins.remotebag.gui;

import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.utils.EconomyUtils;
import com.ultikits.ultitools.utils.XVersionUtils;
import mc.obliviate.inventory.Icon;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.*;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for RemoteBagMainGUI non-open methods.
 * Tests constructor and field initialization.
 * Does NOT test open() or methods that require InventoryAPI initialization.
 */
@DisplayName("RemoteBagMainGUI Tests")
class RemoteBagMainGUITest {

    private RemoteBagService bagService;
    private BagLockService lockService;
    private RemoteBagConfig config;
    private UltiToolsPlugin mockPlugin;
    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiRemoteBagTestHelper.setUp();

        bagService = mock(RemoteBagService.class);
        lockService = mock(BagLockService.class);
        config = UltiRemoteBagTestHelper.createDefaultConfig();
        mockPlugin = mock(UltiToolsPlugin.class);
        when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

        playerUuid = UUID.randomUUID();
        player = UltiRemoteBagTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiRemoteBagTestHelper.tearDown();
    }

    // ==================== Constructor ====================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create GUI with player bag pages")
        void createsWithBagPages() {
            when(bagService.getPlayerBagPages(playerUuid))
                    .thenReturn(Arrays.asList(1, 2, 3));

            assertThatCode(() -> new RemoteBagMainGUI(
                    player, mockPlugin, bagService, lockService, config
            )).doesNotThrowAnyException();

            // Verify it queried the player's bag pages
            verify(bagService).getPlayerBagPages(playerUuid);
        }

        @Test
        @DisplayName("Should create GUI with empty bag pages")
        void createsWithEmptyPages() {
            when(bagService.getPlayerBagPages(playerUuid))
                    .thenReturn(Collections.emptyList());

            assertThatCode(() -> new RemoteBagMainGUI(
                    player, mockPlugin, bagService, lockService, config
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should create GUI with single bag page")
        void createsWithSinglePage() {
            when(bagService.getPlayerBagPages(playerUuid))
                    .thenReturn(Collections.singletonList(1));

            assertThatCode(() -> new RemoteBagMainGUI(
                    player, mockPlugin, bagService, lockService, config
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should use player name in title")
        void usesPlayerNameInTitle() {
            when(bagService.getPlayerBagPages(playerUuid))
                    .thenReturn(Collections.singletonList(1));

            // Constructor calls plugin.i18n("gui_main_title") for the title
            RemoteBagMainGUI gui = new RemoteBagMainGUI(
                    player, mockPlugin, bagService, lockService, config);

            verify(mockPlugin).i18n("gui_main_title");
        }

        @Test
        @DisplayName("Should handle many bag pages")
        void handlesManyPages() {
            when(bagService.getPlayerBagPages(playerUuid))
                    .thenReturn(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));

            assertThatCode(() -> new RemoteBagMainGUI(
                    player, mockPlugin, bagService, lockService, config
            )).doesNotThrowAnyException();
        }
    }

    // ==================== provideItems ====================

    @Nested
    @DisplayName("provideItems")
    class ProvideItems {

        @Test
        @DisplayName("Should create bag icons for each page")
        void createsBagIcons() throws Exception {
            when(bagService.getPlayerBagPages(playerUuid))
                    .thenReturn(Arrays.asList(1, 2));
            when(bagService.getPlayerMaxPages(player)).thenReturn(10);
            when(bagService.getItemCount(eq(playerUuid), anyInt())).thenReturn(5);
            when(bagService.getStackCount(eq(playerUuid), anyInt())).thenReturn(3);

            ItemStack mockItem = mock(ItemStack.class);
            ItemMeta mockMeta = mock(ItemMeta.class);
            when(mockItem.getItemMeta()).thenReturn(mockMeta);

            try (MockedConstruction<ItemStack> isMock = mockConstruction(ItemStack.class,
                    (mock, context) -> when(mock.getItemMeta()).thenReturn(mockMeta));
                 MockedStatic<EconomyUtils> econMock = mockStatic(EconomyUtils.class)) {

                econMock.when(EconomyUtils::isAvailable).thenReturn(true);
                econMock.when(() -> EconomyUtils.getBalance(any(Player.class))).thenReturn(100000.0);
                econMock.when(() -> EconomyUtils.format(anyDouble())).thenReturn("$10,000");

                when(bagService.calculatePrice(anyInt())).thenReturn(10000);

                RemoteBagMainGUI gui = new RemoteBagMainGUI(
                        player, mockPlugin, bagService, lockService, config);

                Method provideItems = RemoteBagMainGUI.class.getDeclaredMethod("provideItems");
                provideItems.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<Icon> icons = (List<Icon>) provideItems.invoke(gui);

                // 2 bag icons + 1 purchase icon (economy enabled, under max)
                assertThat(icons).hasSize(3);
            }
        }

        @Test
        @DisplayName("Should not add purchase icon when at max pages")
        void noPurchaseAtMaxPages() throws Exception {
            when(bagService.getPlayerBagPages(playerUuid))
                    .thenReturn(Arrays.asList(1, 2, 3));
            when(bagService.getPlayerMaxPages(player)).thenReturn(3); // at max
            when(bagService.getItemCount(eq(playerUuid), anyInt())).thenReturn(0);
            when(bagService.getStackCount(eq(playerUuid), anyInt())).thenReturn(0);

            ItemStack mockItem = mock(ItemStack.class);
            ItemMeta mockMeta = mock(ItemMeta.class);

            try (MockedConstruction<ItemStack> isMock = mockConstruction(ItemStack.class,
                    (mock, context) -> when(mock.getItemMeta()).thenReturn(mockMeta));
                 MockedStatic<EconomyUtils> econMock = mockStatic(EconomyUtils.class)) {

                econMock.when(EconomyUtils::isAvailable).thenReturn(true);

                RemoteBagMainGUI gui = new RemoteBagMainGUI(
                        player, mockPlugin, bagService, lockService, config);

                Method provideItems = RemoteBagMainGUI.class.getDeclaredMethod("provideItems");
                provideItems.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<Icon> icons = (List<Icon>) provideItems.invoke(gui);

                // Only bag icons, no purchase icon
                assertThat(icons).hasSize(3);
            }
        }

        @Test
        @DisplayName("Should not add purchase icon when economy disabled")
        void noPurchaseWhenEconomyDisabled() throws Exception {
            RemoteBagConfig noEconConfig = UltiRemoteBagTestHelper.createDefaultConfig();
            when(noEconConfig.isEconomyEnabled()).thenReturn(false);

            when(bagService.getPlayerBagPages(playerUuid))
                    .thenReturn(Collections.singletonList(1));
            when(bagService.getPlayerMaxPages(player)).thenReturn(10);
            when(bagService.getItemCount(eq(playerUuid), anyInt())).thenReturn(0);
            when(bagService.getStackCount(eq(playerUuid), anyInt())).thenReturn(0);

            ItemStack mockItem = mock(ItemStack.class);
            ItemMeta mockMeta = mock(ItemMeta.class);

            try (MockedConstruction<ItemStack> isMock = mockConstruction(ItemStack.class,
                    (mock, context) -> when(mock.getItemMeta()).thenReturn(mockMeta));
                 MockedStatic<EconomyUtils> econMock = mockStatic(EconomyUtils.class)) {

                econMock.when(EconomyUtils::isAvailable).thenReturn(true);

                RemoteBagMainGUI gui = new RemoteBagMainGUI(
                        player, mockPlugin, bagService, lockService, noEconConfig);

                Method provideItems = RemoteBagMainGUI.class.getDeclaredMethod("provideItems");
                provideItems.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<Icon> icons = (List<Icon>) provideItems.invoke(gui);

                // Only bag icon, no purchase
                assertThat(icons).hasSize(1);
            }
        }

        @Test
        @DisplayName("Should not add purchase icon when economy not available")
        void noPurchaseWhenEconomyUnavailable() throws Exception {
            when(bagService.getPlayerBagPages(playerUuid))
                    .thenReturn(Collections.singletonList(1));
            when(bagService.getPlayerMaxPages(player)).thenReturn(10);
            when(bagService.getItemCount(eq(playerUuid), anyInt())).thenReturn(0);
            when(bagService.getStackCount(eq(playerUuid), anyInt())).thenReturn(0);

            ItemStack mockItem = mock(ItemStack.class);
            ItemMeta mockMeta = mock(ItemMeta.class);

            try (MockedConstruction<ItemStack> isMock = mockConstruction(ItemStack.class,
                    (mock, context) -> when(mock.getItemMeta()).thenReturn(mockMeta));
                 MockedStatic<EconomyUtils> econMock = mockStatic(EconomyUtils.class)) {

                econMock.when(EconomyUtils::isAvailable).thenReturn(false);

                RemoteBagMainGUI gui = new RemoteBagMainGUI(
                        player, mockPlugin, bagService, lockService, config);

                Method provideItems = RemoteBagMainGUI.class.getDeclaredMethod("provideItems");
                provideItems.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<Icon> icons = (List<Icon>) provideItems.invoke(gui);

                // Only bag icon, no purchase
                assertThat(icons).hasSize(1);
            }
        }

        @Test
        @DisplayName("Should return empty list when no bag pages")
        void emptyWhenNoBags() throws Exception {
            when(bagService.getPlayerBagPages(playerUuid))
                    .thenReturn(Collections.emptyList());
            when(bagService.getPlayerMaxPages(player)).thenReturn(10);

            try (MockedStatic<EconomyUtils> econMock = mockStatic(EconomyUtils.class)) {
                econMock.when(EconomyUtils::isAvailable).thenReturn(false);

                RemoteBagMainGUI gui = new RemoteBagMainGUI(
                        player, mockPlugin, bagService, lockService, config);

                Method provideItems = RemoteBagMainGUI.class.getDeclaredMethod("provideItems");
                provideItems.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<Icon> icons = (List<Icon>) provideItems.invoke(gui);

                // No bags, economy unavailable = empty
                assertThat(icons).isEmpty();
            }
        }
    }

    // ==================== createBagIcon ====================

    @Nested
    @DisplayName("createBagIcon")
    class CreateBagIcon {

        @Test
        @DisplayName("Should create bag icon with item stats lore")
        void createsBagIconWithStats() throws Exception {
            when(bagService.getPlayerBagPages(playerUuid))
                    .thenReturn(Collections.singletonList(1));
            when(bagService.getItemCount(playerUuid, 1)).thenReturn(10);
            when(bagService.getStackCount(playerUuid, 1)).thenReturn(5);

            ItemMeta mockMeta = mock(ItemMeta.class);

            try (MockedConstruction<ItemStack> isMock = mockConstruction(ItemStack.class,
                    (mock, context) -> when(mock.getItemMeta()).thenReturn(mockMeta))) {

                RemoteBagMainGUI gui = new RemoteBagMainGUI(
                        player, mockPlugin, bagService, lockService, config);

                Method createBagIcon = RemoteBagMainGUI.class.getDeclaredMethod("createBagIcon", int.class);
                createBagIcon.setAccessible(true);
                Object icon = createBagIcon.invoke(gui, 1);

                assertThat(icon).isNotNull();
                verify(mockPlugin).i18n("bag_name");
                verify(mockPlugin).i18n("lore_item_count");
                verify(mockPlugin).i18n("lore_slot_usage");
                verify(mockPlugin).i18n("lore_click_open");
                verify(mockMeta).setLore(anyList());
            }
        }
    }

    // ==================== createPurchaseIcon ====================

    @Nested
    @DisplayName("createPurchaseIcon")
    class CreatePurchaseIcon {

        @Test
        @DisplayName("Should create purchase icon when player can afford")
        void createsAffordablePurchaseIcon() throws Exception {
            when(bagService.getPlayerBagPages(playerUuid))
                    .thenReturn(Collections.singletonList(1));
            when(bagService.calculatePrice(2)).thenReturn(10000);

            ItemMeta mockMeta = mock(ItemMeta.class);

            try (MockedConstruction<ItemStack> isMock = mockConstruction(ItemStack.class,
                    (mock, context) -> when(mock.getItemMeta()).thenReturn(mockMeta));
                 MockedStatic<EconomyUtils> econMock = mockStatic(EconomyUtils.class)) {

                econMock.when(() -> EconomyUtils.getBalance(player)).thenReturn(50000.0);
                econMock.when(() -> EconomyUtils.format(anyDouble())).thenReturn("$10,000");

                RemoteBagMainGUI gui = new RemoteBagMainGUI(
                        player, mockPlugin, bagService, lockService, config);

                Method createPurchaseIcon = RemoteBagMainGUI.class.getDeclaredMethod("createPurchaseIcon");
                createPurchaseIcon.setAccessible(true);
                Object icon = createPurchaseIcon.invoke(gui);

                assertThat(icon).isNotNull();
                verify(mockPlugin).i18n("purchase_button");
                verify(mockPlugin).i18n("lore_price");
                verify(mockPlugin).i18n("lore_balance");
                verify(mockPlugin).i18n("lore_click_purchase");
            }
        }

        @Test
        @DisplayName("Should create purchase icon when player cannot afford")
        void createsUnaffordablePurchaseIcon() throws Exception {
            when(bagService.getPlayerBagPages(playerUuid))
                    .thenReturn(Collections.singletonList(1));
            when(bagService.calculatePrice(2)).thenReturn(10000);

            ItemMeta mockMeta = mock(ItemMeta.class);

            try (MockedConstruction<ItemStack> isMock = mockConstruction(ItemStack.class,
                    (mock, context) -> when(mock.getItemMeta()).thenReturn(mockMeta));
                 MockedStatic<EconomyUtils> econMock = mockStatic(EconomyUtils.class)) {

                econMock.when(() -> EconomyUtils.getBalance(player)).thenReturn(100.0); // can't afford
                econMock.when(() -> EconomyUtils.format(anyDouble())).thenReturn("$100");

                RemoteBagMainGUI gui = new RemoteBagMainGUI(
                        player, mockPlugin, bagService, lockService, config);

                Method createPurchaseIcon = RemoteBagMainGUI.class.getDeclaredMethod("createPurchaseIcon");
                createPurchaseIcon.setAccessible(true);
                Object icon = createPurchaseIcon.invoke(gui);

                assertThat(icon).isNotNull();
                verify(mockPlugin).i18n("purchase_button");
                verify(mockPlugin).i18n("lore_insufficient_balance");
            }
        }
    }

    // ==================== afterSetup ====================

    @Nested
    @DisplayName("afterSetup")
    class AfterSetupTests {

        @Test
        @DisplayName("Should not throw when afterSetup is called")
        void afterSetupDoesNotThrow() throws Exception {
            when(bagService.getPlayerBagPages(playerUuid))
                    .thenReturn(Collections.singletonList(1));

            RemoteBagMainGUI gui = new RemoteBagMainGUI(
                    player, mockPlugin, bagService, lockService, config);

            org.bukkit.event.inventory.InventoryOpenEvent event =
                    mock(org.bukkit.event.inventory.InventoryOpenEvent.class);

            Method afterSetup = RemoteBagMainGUI.class.getDeclaredMethod(
                    "afterSetup", org.bukkit.event.inventory.InventoryOpenEvent.class);
            afterSetup.setAccessible(true);

            assertThatCode(() -> afterSetup.invoke(gui, event)).doesNotThrowAnyException();
        }
    }
}
