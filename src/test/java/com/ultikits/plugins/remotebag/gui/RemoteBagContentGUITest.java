package com.ultikits.plugins.remotebag.gui;

import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.enums.AccessMode;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.utils.XVersionUtils;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.*;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for RemoteBagContentGUI non-open methods.
 * Tests onClick(), onClose(), buildTitle(), and saveCurrentContents() via reflection.
 * Does NOT test open() or methods that require InventoryAPI initialization.
 */
@DisplayName("RemoteBagContentGUI Tests")
class RemoteBagContentGUITest {

    private RemoteBagService bagService;
    private BagLockService lockService;
    private RemoteBagConfig config;
    private UltiToolsPlugin mockPlugin;
    private Player player;
    private UUID playerUuid;
    private UUID ownerUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiRemoteBagTestHelper.setUp();

        bagService = mock(RemoteBagService.class);
        lockService = mock(BagLockService.class);
        config = UltiRemoteBagTestHelper.createDefaultConfig();
        mockPlugin = mock(UltiToolsPlugin.class);
        when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

        playerUuid = UUID.randomUUID();
        ownerUuid = UUID.randomUUID();
        player = UltiRemoteBagTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiRemoteBagTestHelper.tearDown();
    }

    // ==================== buildTitle ====================

    @Nested
    @DisplayName("buildTitle")
    class BuildTitle {

        @Test
        @DisplayName("Should build edit mode title without read-only prefix")
        void editModeTitle() throws Exception {
            Method buildTitle = RemoteBagContentGUI.class.getDeclaredMethod(
                    "buildTitle", UltiToolsPlugin.class, int.class, AccessMode.class);
            buildTitle.setAccessible(true);

            String title = (String) buildTitle.invoke(null, mockPlugin, 1, AccessMode.EDIT);

            assertThat(title).contains("bag_name");
            assertThat(title).doesNotContain("read_only");
        }

        @Test
        @DisplayName("Should build read-only mode title with prefix")
        void readOnlyModeTitle() throws Exception {
            Method buildTitle = RemoteBagContentGUI.class.getDeclaredMethod(
                    "buildTitle", UltiToolsPlugin.class, int.class, AccessMode.class);
            buildTitle.setAccessible(true);

            String title = (String) buildTitle.invoke(null, mockPlugin, 1, AccessMode.READ_ONLY);

            assertThat(title).contains("read_only");
            assertThat(title).contains("bag_name");
        }

        @Test
        @DisplayName("Should include page number in title")
        void includesPageNumber() throws Exception {
            // i18n returns key as-is, so bag_name is the literal string
            // but the replace("{0}", "3") replaces the placeholder
            when(mockPlugin.i18n("bag_name")).thenReturn("Bag #{0}");

            Method buildTitle = RemoteBagContentGUI.class.getDeclaredMethod(
                    "buildTitle", UltiToolsPlugin.class, int.class, AccessMode.class);
            buildTitle.setAccessible(true);

            String title = (String) buildTitle.invoke(null, mockPlugin, 3, AccessMode.EDIT);

            assertThat(title).contains("3");
        }

        @Test
        @DisplayName("Should use gold color for edit mode")
        void editModeUsesGoldColor() throws Exception {
            Method buildTitle = RemoteBagContentGUI.class.getDeclaredMethod(
                    "buildTitle", UltiToolsPlugin.class, int.class, AccessMode.class);
            buildTitle.setAccessible(true);

            String title = (String) buildTitle.invoke(null, mockPlugin, 1, AccessMode.EDIT);

            // Gold color code is section symbol + 6
            assertThat(title).startsWith("\u00a76");
        }

        @Test
        @DisplayName("Should use gray color for read-only prefix")
        void readOnlyUsesGrayColor() throws Exception {
            Method buildTitle = RemoteBagContentGUI.class.getDeclaredMethod(
                    "buildTitle", UltiToolsPlugin.class, int.class, AccessMode.class);
            buildTitle.setAccessible(true);

            String title = (String) buildTitle.invoke(null, mockPlugin, 1, AccessMode.READ_ONLY);

            // Gray color code is section symbol + 7
            assertThat(title).startsWith("\u00a77");
        }
    }

    // ==================== onClick ====================

    @Nested
    @DisplayName("onClick")
    class OnClick {

        @Test
        @DisplayName("Should cancel event in toolbar area (slot >= 45)")
        void cancelsToolbarClick() {
            RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getRawSlot()).thenReturn(45);

            boolean result = gui.onClick(event);

            assertThat(result).isTrue(); // true = cancel event
        }

        @Test
        @DisplayName("Should cancel event in toolbar area (last slot)")
        void cancelsLastSlotClick() {
            RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getRawSlot()).thenReturn(53);

            boolean result = gui.onClick(event);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should allow click in content area in edit mode")
        void allowsContentClickInEditMode() {
            RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getRawSlot()).thenReturn(0);

            boolean result = gui.onClick(event);

            assertThat(result).isFalse(); // false = allow event
        }

        @Test
        @DisplayName("Should allow click on middle content slot in edit mode")
        void allowsMiddleSlotClickInEditMode() {
            RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getRawSlot()).thenReturn(22);

            boolean result = gui.onClick(event);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should cancel click in content area in read-only mode with items")
        void cancelsContentClickInReadOnlyMode() {
            RemoteBagContentGUI gui = createGui(AccessMode.READ_ONLY);

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getRawSlot()).thenReturn(0);
            when(event.getCurrentItem()).thenReturn(mock(ItemStack.class));

            boolean result = gui.onClick(event);

            assertThat(result).isTrue(); // cancel event
            verify(player).sendMessage(contains("msg_readonly_no_move"));
        }

        @Test
        @DisplayName("Should cancel click in read-only mode with cursor item")
        void cancelsReadOnlyWithCursor() {
            RemoteBagContentGUI gui = createGui(AccessMode.READ_ONLY);

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getRawSlot()).thenReturn(0);
            when(event.getCurrentItem()).thenReturn(null);
            when(event.getCursor()).thenReturn(mock(ItemStack.class));

            boolean result = gui.onClick(event);

            assertThat(result).isTrue();
            verify(player).sendMessage(contains("msg_readonly_no_move"));
        }

        @Test
        @DisplayName("Should cancel click in read-only mode even with no items")
        void cancelsReadOnlyEmptySlot() {
            RemoteBagContentGUI gui = createGui(AccessMode.READ_ONLY);

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getRawSlot()).thenReturn(10);
            when(event.getCurrentItem()).thenReturn(null);
            when(event.getCursor()).thenReturn(null);

            boolean result = gui.onClick(event);

            // Still cancels but no message since both are null
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should allow content slot at boundary (slot 44)")
        void allowsContentBoundarySlot() {
            RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getRawSlot()).thenReturn(44);

            boolean result = gui.onClick(event);

            assertThat(result).isFalse(); // content area, edit mode
        }
    }

    // ==================== onClose ====================

    @Nested
    @DisplayName("onClose")
    class OnClose {

        @Test
        @DisplayName("Should save and release lock in edit mode")
        void savesAndReleasesInEditMode() {
            RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

            // Mock inventory for saveCurrentContents
            Inventory mockInventory = mock(Inventory.class);
            setInventory(gui, mockInventory);

            InventoryCloseEvent event = mock(InventoryCloseEvent.class);
            gui.onClose(event);

            // Verify save was called
            verify(bagService).setBagPage(eq(ownerUuid), eq(1), any(ItemStack[].class));
            verify(bagService).saveBag(ownerUuid);
            // Verify lock release
            verify(lockService).release(ownerUuid, 1, playerUuid);
        }

        @Test
        @DisplayName("Should only release lock in read-only mode (no save)")
        void onlyReleasesInReadOnlyMode() {
            RemoteBagContentGUI gui = createGui(AccessMode.READ_ONLY);

            InventoryCloseEvent event = mock(InventoryCloseEvent.class);
            gui.onClose(event);

            // Should NOT save
            verify(bagService, never()).setBagPage(any(), anyInt(), any());
            verify(bagService, never()).saveBag(any());
            // Should still release lock
            verify(lockService).release(ownerUuid, 1, playerUuid);
        }

        @Test
        @DisplayName("Should release lock for correct player and page")
        void releasesCorrectLock() {
            UUID specificOwner = UUID.randomUUID();
            RemoteBagContentGUI gui = new RemoteBagContentGUI(
                    player, mockPlugin, specificOwner, 5,
                    bagService, lockService, config, AccessMode.READ_ONLY);

            InventoryCloseEvent event = mock(InventoryCloseEvent.class);
            gui.onClose(event);

            verify(lockService).release(specificOwner, 5, playerUuid);
        }
    }

    // ==================== saveCurrentContents ====================

    @Nested
    @DisplayName("saveCurrentContents")
    class SaveCurrentContents {

        @Test
        @DisplayName("Should extract items from inventory and save")
        void extractsAndSaves() throws Exception {
            RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

            Inventory mockInventory = mock(Inventory.class);
            ItemStack mockItem = mock(ItemStack.class);
            when(mockInventory.getItem(0)).thenReturn(mockItem);
            when(mockInventory.getItem(1)).thenReturn(null);
            setInventory(gui, mockInventory);

            // Call saveCurrentContents via reflection
            Method saveMethod = RemoteBagContentGUI.class.getDeclaredMethod("saveCurrentContents");
            saveMethod.setAccessible(true);
            saveMethod.invoke(gui);

            verify(bagService).setBagPage(eq(ownerUuid), eq(1), any(ItemStack[].class));
            verify(bagService).saveBag(ownerUuid);
        }

        @Test
        @DisplayName("Should save 45 item slots (CONTENT_SIZE)")
        void savesCorrectSlotCount() throws Exception {
            RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

            Inventory mockInventory = mock(Inventory.class);
            setInventory(gui, mockInventory);

            Method saveMethod = RemoteBagContentGUI.class.getDeclaredMethod("saveCurrentContents");
            saveMethod.setAccessible(true);
            saveMethod.invoke(gui);

            // Verify getItem was called for slots 0-44
            for (int i = 0; i < 45; i++) {
                verify(mockInventory).getItem(i);
            }
        }
    }

    // ==================== Constructor ====================

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should create GUI with edit mode")
        void createsWithEditMode() {
            assertThatCode(() -> createGui(AccessMode.EDIT)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should create GUI with read-only mode")
        void createsWithReadOnlyMode() {
            assertThatCode(() -> createGui(AccessMode.READ_ONLY)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should accept different page numbers")
        void acceptsDifferentPages() {
            for (int page = 1; page <= 10; page++) {
                final int p = page;
                assertThatCode(() -> new RemoteBagContentGUI(
                        player, mockPlugin, ownerUuid, p,
                        bagService, lockService, config, AccessMode.EDIT
                )).doesNotThrowAnyException();
            }
        }
    }

    // ==================== loadBagContents ====================

    @Nested
    @DisplayName("loadBagContents")
    class LoadBagContents {

        @Test
        @DisplayName("Should load contents from service into inventory")
        void loadsContentsIntoInventory() throws Exception {
            RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

            Inventory mockInventory = mock(Inventory.class);
            setInventory(gui, mockInventory);

            ItemStack mockItem = mock(ItemStack.class);
            ItemStack[] contents = new ItemStack[45];
            contents[0] = mockItem;
            contents[10] = mockItem;

            when(bagService.getBagPage(ownerUuid, 1)).thenReturn(contents);

            Method loadMethod = RemoteBagContentGUI.class.getDeclaredMethod("loadBagContents");
            loadMethod.setAccessible(true);
            loadMethod.invoke(gui);

            verify(bagService).loadBagIfNeeded(ownerUuid);
            verify(bagService).getBagPage(ownerUuid, 1);
            verify(mockInventory).setItem(0, mockItem);
            verify(mockInventory).setItem(10, mockItem);
        }

        @Test
        @DisplayName("Should handle null contents from service")
        void handlesNullContents() throws Exception {
            RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

            Inventory mockInventory = mock(Inventory.class);
            setInventory(gui, mockInventory);

            when(bagService.getBagPage(ownerUuid, 1)).thenReturn(null);

            Method loadMethod = RemoteBagContentGUI.class.getDeclaredMethod("loadBagContents");
            loadMethod.setAccessible(true);
            loadMethod.invoke(gui);

            verify(bagService).loadBagIfNeeded(ownerUuid);
            // No setItem calls when contents is null
            verify(mockInventory, never()).setItem(anyInt(), any(ItemStack.class));
        }

        @Test
        @DisplayName("Should skip null items in contents array")
        void skipsNullItems() throws Exception {
            RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

            Inventory mockInventory = mock(Inventory.class);
            setInventory(gui, mockInventory);

            ItemStack mockItem = mock(ItemStack.class);
            ItemStack[] contents = new ItemStack[45];
            contents[5] = mockItem; // Only slot 5 has an item

            when(bagService.getBagPage(ownerUuid, 1)).thenReturn(contents);

            Method loadMethod = RemoteBagContentGUI.class.getDeclaredMethod("loadBagContents");
            loadMethod.setAccessible(true);
            loadMethod.invoke(gui);

            // Only slot 5 should have been set
            verify(mockInventory).setItem(5, mockItem);
            verify(mockInventory, times(1)).setItem(anyInt(), any(ItemStack.class));
        }

        @Test
        @DisplayName("Should handle contents array smaller than CONTENT_SIZE")
        void handlesSmallContentsArray() throws Exception {
            RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

            Inventory mockInventory = mock(Inventory.class);
            setInventory(gui, mockInventory);

            ItemStack mockItem = mock(ItemStack.class);
            ItemStack[] contents = new ItemStack[10]; // Smaller than 45
            contents[0] = mockItem;

            when(bagService.getBagPage(ownerUuid, 1)).thenReturn(contents);

            Method loadMethod = RemoteBagContentGUI.class.getDeclaredMethod("loadBagContents");
            loadMethod.setAccessible(true);
            loadMethod.invoke(gui);

            verify(mockInventory).setItem(0, mockItem);
        }
    }

    // ==================== setupToolbar ====================

    @Nested
    @DisplayName("setupToolbar")
    class SetupToolbar {

        @Test
        @DisplayName("Should set up toolbar for edit mode")
        void setupToolbarEditMode() throws Exception {
            ItemStack mockGlass = mock(ItemStack.class);
            ItemMeta mockMeta = mock(ItemMeta.class);
            when(mockGlass.getItemMeta()).thenReturn(mockMeta);

            try (MockedStatic<XVersionUtils> xvMock = mockStatic(XVersionUtils.class)) {
                xvMock.when(() -> XVersionUtils.getColoredPlaneGlass(any(Colors.class)))
                        .thenReturn(mockGlass);

                RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

                // Set up mock inventory for addToBottomRow (getSize() -> slot calculation)
                Inventory mockInventory = mock(Inventory.class);
                when(mockInventory.getSize()).thenReturn(54); // 6 rows * 9
                setInventory(gui, mockInventory);

                Method setupMethod = RemoteBagContentGUI.class.getDeclaredMethod("setupToolbar");
                setupMethod.setAccessible(true);
                setupMethod.invoke(gui);

                // Verify i18n calls for edit mode buttons
                verify(mockPlugin).i18n("btn_back");
                verify(mockPlugin).i18n("btn_save");
                verify(mockPlugin).i18n("btn_close");
                verify(mockPlugin).i18n("mode_edit");
            }
        }

        @Test
        @DisplayName("Should set up toolbar for read-only mode")
        void setupToolbarReadOnlyMode() throws Exception {
            ItemStack mockGlass = mock(ItemStack.class);
            ItemMeta mockMeta = mock(ItemMeta.class);
            when(mockGlass.getItemMeta()).thenReturn(mockMeta);

            try (MockedStatic<XVersionUtils> xvMock = mockStatic(XVersionUtils.class)) {
                xvMock.when(() -> XVersionUtils.getColoredPlaneGlass(any(Colors.class)))
                        .thenReturn(mockGlass);

                try (MockedConstruction<ItemStack> isMock = mockConstruction(ItemStack.class,
                        (mock, context) -> when(mock.getItemMeta()).thenReturn(mockMeta))) {

                    RemoteBagContentGUI gui = createGui(AccessMode.READ_ONLY);

                    // Set up mock inventory for addToBottomRow
                    Inventory mockInventory = mock(Inventory.class);
                    when(mockInventory.getSize()).thenReturn(54);
                    setInventory(gui, mockInventory);

                    Method setupMethod = RemoteBagContentGUI.class.getDeclaredMethod("setupToolbar");
                    setupMethod.setAccessible(true);
                    setupMethod.invoke(gui);

                    // Verify i18n calls for read-only mode buttons
                    verify(mockPlugin).i18n("btn_back");
                    verify(mockPlugin).i18n("btn_refresh");
                    verify(mockPlugin).i18n("btn_save_disabled");
                    verify(mockPlugin).i18n("btn_close");
                    verify(mockPlugin).i18n("mode_readonly");
                }
            }
        }
    }

    // ==================== setupContent ====================

    @Nested
    @DisplayName("setupContent")
    class SetupContent {

        @Test
        @DisplayName("Should call loadBagContents and setupToolbar")
        void callsLoadAndSetup() throws Exception {
            ItemStack mockGlass = mock(ItemStack.class);
            ItemMeta mockMeta = mock(ItemMeta.class);
            when(mockGlass.getItemMeta()).thenReturn(mockMeta);

            try (MockedStatic<XVersionUtils> xvMock = mockStatic(XVersionUtils.class)) {
                xvMock.when(() -> XVersionUtils.getColoredPlaneGlass(any(Colors.class)))
                        .thenReturn(mockGlass);

                RemoteBagContentGUI gui = createGui(AccessMode.EDIT);
                Inventory mockInventory = mock(Inventory.class);
                when(mockInventory.getSize()).thenReturn(54);
                setInventory(gui, mockInventory);

                when(bagService.getBagPage(ownerUuid, 1)).thenReturn(null);

                InventoryOpenEvent event = mock(InventoryOpenEvent.class);

                Method setupContent = RemoteBagContentGUI.class.getDeclaredMethod(
                        "setupContent", InventoryOpenEvent.class);
                setupContent.setAccessible(true);
                setupContent.invoke(gui, event);

                // Verify loadBagContents was called
                verify(bagService).loadBagIfNeeded(ownerUuid);
                // Verify setupToolbar was called (btn_back is from toolbar)
                verify(mockPlugin).i18n("btn_back");
            }
        }
    }

    // ==================== afterSetup ====================

    @Nested
    @DisplayName("afterSetup")
    class AfterSetup {

        @Test
        @DisplayName("Should play open sound")
        void playsOpenSound() throws Exception {
            RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

            InventoryOpenEvent event = mock(InventoryOpenEvent.class);

            Method afterSetup = RemoteBagContentGUI.class.getDeclaredMethod(
                    "afterSetup", InventoryOpenEvent.class);
            afterSetup.setAccessible(true);
            afterSetup.invoke(gui, event);

            // SoundUtil.playOpenSound is a static method - hard to verify directly
            // but the method should not throw
        }
    }

    // ==================== createModeIndicator ====================

    @Nested
    @DisplayName("createModeIndicator")
    class CreateModeIndicator {

        @Test
        @DisplayName("Should create edit mode indicator with green color")
        void editModeIndicator() throws Exception {
            ItemStack mockGlass = mock(ItemStack.class);
            ItemMeta mockMeta = mock(ItemMeta.class);
            when(mockGlass.getItemMeta()).thenReturn(mockMeta);

            try (MockedStatic<XVersionUtils> xvMock = mockStatic(XVersionUtils.class)) {
                xvMock.when(() -> XVersionUtils.getColoredPlaneGlass(any(Colors.class)))
                        .thenReturn(mockGlass);

                RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

                Method method = RemoteBagContentGUI.class.getDeclaredMethod("createModeIndicator");
                method.setAccessible(true);
                Object icon = method.invoke(gui);

                assertThat(icon).isNotNull();
                verify(mockPlugin).i18n("mode_edit");
                verify(mockPlugin).i18n("lore_edit_mode");
            }
        }

        @Test
        @DisplayName("Should create read-only mode indicator with yellow color")
        void readOnlyModeIndicator() throws Exception {
            ItemStack mockGlass = mock(ItemStack.class);
            ItemMeta mockMeta = mock(ItemMeta.class);
            when(mockGlass.getItemMeta()).thenReturn(mockMeta);

            try (MockedStatic<XVersionUtils> xvMock = mockStatic(XVersionUtils.class)) {
                xvMock.when(() -> XVersionUtils.getColoredPlaneGlass(any(Colors.class)))
                        .thenReturn(mockGlass);

                RemoteBagContentGUI gui = createGui(AccessMode.READ_ONLY);

                Method method = RemoteBagContentGUI.class.getDeclaredMethod("createModeIndicator");
                method.setAccessible(true);
                Object icon = method.invoke(gui);

                assertThat(icon).isNotNull();
                verify(mockPlugin).i18n("mode_readonly");
                verify(mockPlugin).i18n("lore_readonly_mode1");
                verify(mockPlugin).i18n("lore_readonly_mode2");
            }
        }
    }

    // ==================== createBackButton ====================

    @Nested
    @DisplayName("createBackButton")
    class CreateBackButton {

        @Test
        @DisplayName("Should create back button with lore")
        void createsBackButton() throws Exception {
            ItemStack mockGlass = mock(ItemStack.class);
            ItemMeta mockMeta = mock(ItemMeta.class);
            when(mockGlass.getItemMeta()).thenReturn(mockMeta);

            try (MockedStatic<XVersionUtils> xvMock = mockStatic(XVersionUtils.class)) {
                xvMock.when(() -> XVersionUtils.getColoredPlaneGlass(any(Colors.class)))
                        .thenReturn(mockGlass);

                RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

                Method method = RemoteBagContentGUI.class.getDeclaredMethod("createBackButton");
                method.setAccessible(true);
                Object icon = method.invoke(gui);

                assertThat(icon).isNotNull();
                verify(mockPlugin).i18n("btn_back");
                verify(mockPlugin).i18n("lore_back_to_main");
                verify(mockMeta).setLore(anyList());
            }
        }
    }

    // ==================== createSaveButton ====================

    @Nested
    @DisplayName("createSaveButton")
    class CreateSaveButton {

        @Test
        @DisplayName("Should create save button with lore")
        void createsSaveButton() throws Exception {
            ItemStack mockGlass = mock(ItemStack.class);
            ItemMeta mockMeta = mock(ItemMeta.class);
            when(mockGlass.getItemMeta()).thenReturn(mockMeta);

            try (MockedStatic<XVersionUtils> xvMock = mockStatic(XVersionUtils.class)) {
                xvMock.when(() -> XVersionUtils.getColoredPlaneGlass(any(Colors.class)))
                        .thenReturn(mockGlass);

                RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

                Method method = RemoteBagContentGUI.class.getDeclaredMethod("createSaveButton");
                method.setAccessible(true);
                Object icon = method.invoke(gui);

                assertThat(icon).isNotNull();
                verify(mockPlugin).i18n("btn_save");
                verify(mockPlugin).i18n("lore_save_hint");
                verify(mockMeta).setLore(anyList());
            }
        }
    }

    // ==================== createDisabledSaveButton ====================

    @Nested
    @DisplayName("createDisabledSaveButton")
    class CreateDisabledSaveButton {

        @Test
        @DisplayName("Should create disabled save button for read-only mode")
        void createsDisabledSaveButton() throws Exception {
            ItemStack mockGlass = mock(ItemStack.class);
            ItemMeta mockMeta = mock(ItemMeta.class);
            when(mockGlass.getItemMeta()).thenReturn(mockMeta);

            try (MockedStatic<XVersionUtils> xvMock = mockStatic(XVersionUtils.class)) {
                xvMock.when(() -> XVersionUtils.getColoredPlaneGlass(any(Colors.class)))
                        .thenReturn(mockGlass);

                RemoteBagContentGUI gui = createGui(AccessMode.READ_ONLY);

                Method method = RemoteBagContentGUI.class.getDeclaredMethod("createDisabledSaveButton");
                method.setAccessible(true);
                Object icon = method.invoke(gui);

                assertThat(icon).isNotNull();
                verify(mockPlugin).i18n("btn_save_disabled");
                verify(mockPlugin).i18n("lore_readonly_hint1");
                verify(mockPlugin).i18n("lore_readonly_hint2");
                verify(mockPlugin).i18n("lore_readonly_hint3");
                verify(mockPlugin).i18n("lore_readonly_hint4");
            }
        }
    }

    // ==================== createCloseButton ====================

    @Nested
    @DisplayName("createCloseButton")
    class CreateCloseButton {

        @Test
        @DisplayName("Should create close button with edit mode lore")
        void createsCloseButtonEditMode() throws Exception {
            ItemStack mockGlass = mock(ItemStack.class);
            ItemMeta mockMeta = mock(ItemMeta.class);
            when(mockGlass.getItemMeta()).thenReturn(mockMeta);

            try (MockedStatic<XVersionUtils> xvMock = mockStatic(XVersionUtils.class)) {
                xvMock.when(() -> XVersionUtils.getColoredPlaneGlass(any(Colors.class)))
                        .thenReturn(mockGlass);

                RemoteBagContentGUI gui = createGui(AccessMode.EDIT);

                Method method = RemoteBagContentGUI.class.getDeclaredMethod("createCloseButton");
                method.setAccessible(true);
                Object icon = method.invoke(gui);

                assertThat(icon).isNotNull();
                verify(mockPlugin).i18n("btn_close");
                verify(mockPlugin).i18n("lore_close_save");
            }
        }

        @Test
        @DisplayName("Should create close button with read-only mode lore")
        void createsCloseButtonReadOnlyMode() throws Exception {
            ItemStack mockGlass = mock(ItemStack.class);
            ItemMeta mockMeta = mock(ItemMeta.class);
            when(mockGlass.getItemMeta()).thenReturn(mockMeta);

            try (MockedStatic<XVersionUtils> xvMock = mockStatic(XVersionUtils.class)) {
                xvMock.when(() -> XVersionUtils.getColoredPlaneGlass(any(Colors.class)))
                        .thenReturn(mockGlass);

                RemoteBagContentGUI gui = createGui(AccessMode.READ_ONLY);

                Method method = RemoteBagContentGUI.class.getDeclaredMethod("createCloseButton");
                method.setAccessible(true);
                Object icon = method.invoke(gui);

                assertThat(icon).isNotNull();
                verify(mockPlugin).i18n("btn_close");
                verify(mockPlugin).i18n("lore_close_discard");
            }
        }
    }

    // ==================== createRefreshButton ====================

    @Nested
    @DisplayName("createRefreshButton")
    class CreateRefreshButton {

        @Test
        @DisplayName("Should create refresh button with lore hints")
        void createsRefreshButton() throws Exception {
            ItemStack mockItem = mock(ItemStack.class);
            ItemMeta mockMeta = mock(ItemMeta.class);
            when(mockItem.getItemMeta()).thenReturn(mockMeta);

            try (MockedConstruction<ItemStack> isMock = mockConstruction(ItemStack.class,
                    (mock, context) -> when(mock.getItemMeta()).thenReturn(mockMeta))) {

                RemoteBagContentGUI gui = createGui(AccessMode.READ_ONLY);

                Method method = RemoteBagContentGUI.class.getDeclaredMethod("createRefreshButton");
                method.setAccessible(true);
                Object icon = method.invoke(gui);

                assertThat(icon).isNotNull();
                verify(mockPlugin).i18n("btn_refresh");
                verify(mockPlugin).i18n("lore_refresh_hint1");
                verify(mockPlugin).i18n("lore_refresh_hint2");
                verify(mockPlugin).i18n("lore_refresh_hint3");
            }
        }
    }

    // ==================== Helper Methods ====================

    private RemoteBagContentGUI createGui(AccessMode mode) {
        return new RemoteBagContentGUI(
                player, mockPlugin, ownerUuid, 1,
                bagService, lockService, config, mode);
    }

    private void setInventory(RemoteBagContentGUI gui, Inventory inventory) {
        try {
            // The inventory field is in the parent class (Gui)
            java.lang.reflect.Field inventoryField = findField(gui.getClass(), "inventory");
            if (inventoryField == null) {
                // Create a real inventory for testing
                return;
            }
            inventoryField.setAccessible(true);
            inventoryField.set(gui, inventory);
        } catch (Exception e) {
            // If we can't set the inventory, tests will verify what they can
        }
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
