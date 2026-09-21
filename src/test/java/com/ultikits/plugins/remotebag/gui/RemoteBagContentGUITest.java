package com.ultikits.plugins.remotebag.gui;

import com.ultikits.plugins.remotebag.MockBukkitSupport;
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
 * Tests for RemoteBagContentGUI methods that need neither an open inventory nor the GUI library:
 * buildTitle(), the toolbar icon builders, onClose(), and saveCurrentContents() through reflection.
 * <p>
 * Click and drag behaviour is deliberately NOT here. It lives in
 * RemoteBagContentGUIInteractionMatrixTest, which dispatches real events through the GUI library's
 * own listener and asserts both inventories' contents afterwards -- the only way to tell "cancelled"
 * from "allowed", since this class cannot reach the code that turns the return value into
 * event.setCancelled(...). See the note where those cases used to sit.
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
        // Live test-time Bukkit server: RemoteBagContentGUI extends obliviate-invs' Gui, whose
        // constructor touches InventoryType/MenuType, which needs a live registry to resolve.
        // MockBukkitSupport.bootstrapLiveServer() is this module's shared bootstrap
        // entry point.
        MockBukkitSupport.bootstrapLiveServer();

        UltiRemoteBagTestHelper.setUp();

        bagService = mock(RemoteBagService.class);
        lockService = mock(BagLockService.class);
        // What a real BagLockService answers when no lock is held on the page -- the ordinary case,
        // in which a save must go through. The save path asks the LIVE lock rather than the mode the
        // page was constructed with, so a mock left unstubbed would answer false and every save case
        // below would refuse for a reason that has nothing to do with what it tests. The refusal
        // itself is asserted in SaveCurrentContents#refusesWhenTheLockIsNoLongerOurs.
        lenient().when(lockService.mayWrite(any(), anyInt(), any())).thenReturn(true);
        // What a real service answers for a cache it persisted. saveCurrentContents now propagates
        // this, so an unstubbed mock would answer false and every save case would report failure.
        lenient().when(bagService.saveBag(any())).thenReturn(true);
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
        MockBukkitSupport.safeUnmock();
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

    // ==================== onClick / onDrag ====================
    //
    // Deliberately absent here. The cases that used to sit in this position asserted only the
    // boolean onClick returns, reading it through the comment "true = cancel event" -- the inverse
    // of the deployed GUI library's real contract, so they were green while a read-only viewer
    // could genuinely take another player's item (UltiKits/UltiRemoteBag#27). Reading that return
    // value also never runs the code that turns it into event.setCancelled(...), so no assertion on
    // it can tell "cancelled" from "allowed".
    //
    // Click and drag behaviour is now asserted in RemoteBagContentGUIInteractionMatrixTest, which
    // dispatches real events through the library's own listener and asserts both inventories'
    // contents afterwards.

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

        @Test
        @DisplayName("Refuses to write, with a message, when the live lock is no longer this page's")
        void refusesWhenTheLockIsNoLongerOurs() throws Exception {
            // Defence in depth for the lost update in pull request #34's gate-1 review: this page was
            // constructed in EDIT mode, but the live lock now says READ_ONLY, i.e. somebody else holds
            // the page. Writing would overwrite their committed edits with a snapshot taken before
            // they existed. The primary protection is that a lock can no longer expire while its page
            // is open, so this state should be unreachable; this asserts the guard behind it.
            RemoteBagContentGUI gui = createGui(AccessMode.EDIT);
            when(lockService.mayWrite(ownerUuid, 1, playerUuid)).thenReturn(false);

            Inventory mockInventory = mock(Inventory.class);
            setInventory(gui, mockInventory);

            Method saveMethod = RemoteBagContentGUI.class.getDeclaredMethod("saveCurrentContents");
            saveMethod.setAccessible(true);
            Object written = saveMethod.invoke(gui);

            assertThat(written).as("the write must be reported as not performed").isEqualTo(false);
            verify(bagService, never()).setBagPage(any(), anyInt(), any());
            verify(bagService, never()).saveBag(any());
            verify(player).sendMessage(contains("msg_save_refused_lock_taken"));
        }

        @Test
        @DisplayName("Reports failure, with a message, when the persistence write did not land")
        void reportsFailureWhenThePersistenceWriteFails() throws Exception {
            // saveBag returns false when an update throws IllegalAccessException: the edit is in the
            // cache and not in the database, so it is lost on the next restart. Discarding that result
            // and reporting success is the same defect as reporting a save with an empty cache, one
            // layer in -- raised as a P2 on pull request #34's second external review round.
            RemoteBagContentGUI gui = createGui(AccessMode.EDIT);
            when(lockService.mayWrite(ownerUuid, 1, playerUuid)).thenReturn(true);
            when(bagService.saveBag(ownerUuid)).thenReturn(false);

            Inventory mockInventory = mock(Inventory.class);
            setInventory(gui, mockInventory);

            Method saveMethod = RemoteBagContentGUI.class.getDeclaredMethod("saveCurrentContents");
            saveMethod.setAccessible(true);
            Object written = saveMethod.invoke(gui);

            assertThat(written).as("a failed write must not be reported as performed").isEqualTo(false);
            verify(player).sendMessage(contains("msg_save_failed"));
            verify(player, never()).sendMessage(contains("msg_bag_saved"));
        }

        @Test
        @DisplayName("Still writes when no lock is held at all, which is the ordinary case")
        void stillWritesWhenNobodyHoldsTheLock() throws Exception {
            // mayWrite answers true when the map holds no lock for the page -- the lock expired and
            // nobody took it. A guard that refused there would silently stop persisting every normal
            // session, so this is the control that the guard above is not that.
            RemoteBagContentGUI gui = createGui(AccessMode.EDIT);
            when(lockService.mayWrite(ownerUuid, 1, playerUuid)).thenReturn(true);

            Inventory mockInventory = mock(Inventory.class);
            setInventory(gui, mockInventory);

            Method saveMethod = RemoteBagContentGUI.class.getDeclaredMethod("saveCurrentContents");
            saveMethod.setAccessible(true);
            Object written = saveMethod.invoke(gui);

            assertThat(written).isEqualTo(true);
            verify(bagService).saveBag(ownerUuid);
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
