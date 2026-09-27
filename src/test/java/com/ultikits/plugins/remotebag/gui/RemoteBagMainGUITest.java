package com.ultikits.plugins.remotebag.gui;

import com.ultikits.plugins.remotebag.MockBukkitSupport;
import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.utils.EconomyUtils;
import com.ultikits.ultitools.utils.XVersionUtils;
import mc.obliviate.inventory.Icon;

import com.cryptomorin.xseries.XSound;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryOpenEvent;
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
        // Live test-time Bukkit server: RemoteBagMainGUI extends obliviate-invs' Gui, whose
        // constructor touches InventoryType/MenuType, which needs a live registry to resolve.
        // MockBukkitSupport.bootstrapLiveServer() is this module's shared bootstrap
        // entry point.
        MockBukkitSupport.bootstrapLiveServer();

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
        MockBukkitSupport.safeUnmock();
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
        @DisplayName("With economy disabled, offers a free create icon instead of the purchase icon (UltiKits/UltiRemoteBag#25)")
        void freeCreateIconWhenEconomyDisabled() throws Exception {
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

                // The page icon plus the free create icon; no priced purchase icon
                assertThat(icons).hasSize(2);
                verify(mockPlugin).i18n("create_button");
                verify(mockPlugin, never()).i18n("purchase_button");
            }
        }

        @Test
        @DisplayName("With no economy provider, offers a free create icon instead of the purchase icon (UltiKits/UltiRemoteBag#25)")
        void freeCreateIconWhenEconomyUnavailable() throws Exception {
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

                // The page icon plus the free create icon; no priced purchase icon
                assertThat(icons).hasSize(2);
                verify(mockPlugin).i18n("create_button");
                verify(mockPlugin, never()).i18n("purchase_button");
            }
        }

        @Test
        @DisplayName("With economy disabled and the page limit reached, offers no create icon (UltiKits/UltiRemoteBag#25)")
        void noFreeCreateIconAtThePageLimit() throws Exception {
            RemoteBagConfig noEconConfig = UltiRemoteBagTestHelper.createDefaultConfig();
            when(noEconConfig.isEconomyEnabled()).thenReturn(false);
            when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Arrays.asList(1, 2));
            when(bagService.getPlayerMaxPages(player)).thenReturn(2);
            when(bagService.getItemCount(eq(playerUuid), anyInt())).thenReturn(0);
            when(bagService.getStackCount(eq(playerUuid), anyInt())).thenReturn(0);
            ItemMeta mockMeta = mock(ItemMeta.class);

            try (MockedConstruction<ItemStack> isMock = mockConstruction(ItemStack.class,
                    (mock, context) -> when(mock.getItemMeta()).thenReturn(mockMeta))) {
                RemoteBagMainGUI gui = new RemoteBagMainGUI(
                        player, mockPlugin, bagService, lockService, noEconConfig);

                Method provideItems = RemoteBagMainGUI.class.getDeclaredMethod("provideItems");
                provideItems.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<Icon> icons = (List<Icon>) provideItems.invoke(gui);

                assertThat(icons).hasSize(2);
                verify(mockPlugin, never()).i18n("create_button");
            }
        }

        /**
         * UltiKits/UltiRemoteBag#25: the free icon's click takes the service's free path
         * ({@code purchaseBag}, which creates a page without charging when economy is off) and
         * reports the page it created.
         */
        @Test
        @DisplayName("Clicking the free create icon creates the next page and says so (UltiKits/UltiRemoteBag#25)")
        void clickingTheFreeCreateIconCreatesAPage() throws Exception {
            RemoteBagConfig noEconConfig = UltiRemoteBagTestHelper.createDefaultConfig();
            when(noEconConfig.isEconomyEnabled()).thenReturn(false);
            when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Collections.singletonList(1));
            when(bagService.getPlayerMaxPages(player)).thenReturn(10);
            when(bagService.getItemCount(eq(playerUuid), anyInt())).thenReturn(0);
            when(bagService.getStackCount(eq(playerUuid), anyInt())).thenReturn(0);
            when(bagService.purchaseBag(player)).thenReturn(true);
            ItemMeta mockMeta = mock(ItemMeta.class);

            List<Icon> icons;
            RemoteBagMainGUI gui;
            try (MockedConstruction<ItemStack> isMock = mockConstruction(ItemStack.class,
                    (mock, context) -> when(mock.getItemMeta()).thenReturn(mockMeta))) {
                gui = new RemoteBagMainGUI(player, mockPlugin, bagService, lockService, noEconConfig);
                Method provideItems = RemoteBagMainGUI.class.getDeclaredMethod("provideItems");
                provideItems.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<Icon> provided = (List<Icon>) provideItems.invoke(gui);
                icons = provided;
            }
            assertThat(icons).hasSize(2);

            try {
                icons.get(1).getClickAction().accept(null);
            } catch (RuntimeException e) {
                // Re-opening the refreshed window needs the GUI library, which this test does not start.
            }

            verify(bagService).purchaseBag(player);
            verify(player).sendMessage(contains("create_success"));
        }

        /**
         * UltiKits/UltiRemoteBag#26: with nothing stored the owner's own window still offers page 1,
         * as it did while the stored list invented it.
         */
        @Test
        @DisplayName("Offers page 1 when nothing is stored (UltiKits/UltiRemoteBag#26)")
        void offersPageOneWhenNothingIsStored() throws Exception {
            when(bagService.getPlayerBagPages(playerUuid))
                    .thenReturn(Collections.emptyList());
            when(bagService.getPlayerMaxPages(player)).thenReturn(1);
            when(bagService.getItemCount(eq(playerUuid), anyInt())).thenReturn(0);
            when(bagService.getStackCount(eq(playerUuid), anyInt())).thenReturn(0);

            ItemMeta mockMeta = mock(ItemMeta.class);

            try (MockedConstruction<ItemStack> isMock = mockConstruction(ItemStack.class,
                    (mock, context) -> when(mock.getItemMeta()).thenReturn(mockMeta));
                 MockedStatic<EconomyUtils> econMock = mockStatic(EconomyUtils.class)) {
                econMock.when(EconomyUtils::isAvailable).thenReturn(true);
                econMock.when(() -> EconomyUtils.getBalance(any(Player.class))).thenReturn(0.0);
                econMock.when(() -> EconomyUtils.format(anyDouble())).thenReturn("$0");

                RemoteBagMainGUI gui = new RemoteBagMainGUI(
                        player, mockPlugin, bagService, lockService, config);

                Method provideItems = RemoteBagMainGUI.class.getDeclaredMethod("provideItems");
                provideItems.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<Icon> icons = (List<Icon>) provideItems.invoke(gui);

                // Page 1's icon; at the one-page limit, no purchase icon
                assertThat(icons).hasSize(1);
                verify(bagService).getItemCount(playerUuid, 1);
            }
        }
    }

    // ==================== open sound (UltiKits/UltiRemoteBag#29) ====================

    /**
     * UltiKits/UltiRemoteBag#29: the shared test machine has no audio device, so the checklist's
     * "the configured open sound plays" cannot be observed there. Opening the window (through
     * {@code afterSetup}, the hook the framework's {@code onOpen} runs last) must play exactly the
     * configured, non-default sound.
     */
    @Nested
    @DisplayName("Open sound (UltiKits/UltiRemoteBag#29)")
    class OpenSound {

        @Test
        @DisplayName("Opening the window plays the configured open sound")
        void opensWithConfiguredSound() {
            configureNonDefaultOpenSound(true);
            RemoteBagMainGUI gui = newWindowWithOnePage();

            openThroughAfterSetup(gui);

            verify(player).playSound(any(Location.class), eq(XSound.BLOCK_BARREL_OPEN.get()), eq(0.5f), eq(1.5f));
        }

        @Test
        @DisplayName("With sound.enabled: false, opening the window plays no sound")
        void opensSilentlyWhenSoundDisabled() {
            configureNonDefaultOpenSound(false);
            RemoteBagMainGUI gui = newWindowWithOnePage();

            openThroughAfterSetup(gui);

            verify(player, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        }

        private RemoteBagMainGUI newWindowWithOnePage() {
            lenient().when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Collections.singletonList(1));
            lenient().when(bagService.getPlayerMaxPages(player)).thenReturn(1);
            return new RemoteBagMainGUI(player, mockPlugin, bagService, lockService, config);
        }

        /**
         * Runs the page's {@code afterSetup}, the hook the framework's final
         * {@code BaseInventoryPage#onOpen} calls after {@code setupBottomToolbar} and
         * {@code setupContent} (measured on the shaded framework jar: {@code onOpen} invokes the
         * three at offsets 8, 13 and 18). Those two need the inventory a real {@code open()} creates,
         * which this test's mocked player cannot provide, so the open is driven from the hook that
         * plays the sound.
         */
        private void openThroughAfterSetup(RemoteBagMainGUI gui) {
            try {
                java.lang.reflect.Method afterSetup = RemoteBagMainGUI.class.getDeclaredMethod(
                        "afterSetup", InventoryOpenEvent.class);
                afterSetup.setAccessible(true); // NOPMD - the framework calls this protected hook
                afterSetup.invoke(gui, mock(InventoryOpenEvent.class));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        private void configureNonDefaultOpenSound(boolean enabled) {
            when(config.isSoundEnabled()).thenReturn(enabled);
            lenient().when(config.getOpenSound()).thenReturn("BLOCK_BARREL_OPEN");
            lenient().when(config.getSoundVolume()).thenReturn(0.5);
            lenient().when(config.getSoundPitch()).thenReturn(1.5);
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
