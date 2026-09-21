package com.ultikits.plugins.remotebag.gui;

import com.ultikits.plugins.remotebag.MockBukkitSupport;
import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.enums.AccessMode;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

import mc.obliviate.inventory.InventoryAPI;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.HandlerList;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Interaction matrix for {@link RemoteBagContentGUI}: {@link AccessMode#EDIT} and
 * {@link AccessMode#READ_ONLY} crossed with a normal click, a shift-click, a number-key (hotbar)
 * swap and a drag, plus the toolbar row and the viewer's own inventory as interaction regions of
 * their own.
 *
 * <h2>Why this class exists, and what it does differently</h2>
 * The former {@code RemoteBagContentGUITest.OnClick} cases asserted only the boolean that
 * {@code onClick} returns, annotated with the comment {@code // true = cancel event}. That comment
 * was the inverse of the deployed GUI library's actual contract, so those cases were green on the
 * defective implementation and asserted the defect
 * (<a href="https://github.com/UltiKits/UltiRemoteBag/issues/27">UltiRemoteBag#27</a>): a read-only
 * viewer really could take an item out of another player's bag while being told they could not.
 * Reading a return value also never exercises the code that turns that value into
 * {@code event.setCancelled(...)}, so no assertion on it can distinguish "cancelled" from
 * "allowed" at all.
 *
 * <h2>What makes a vacuous pass impossible here</h2>
 * Every event below is a real {@link InventoryClickEvent}/{@link InventoryDragEvent} over a real
 * {@link InventoryView}, dispatched through {@link Bukkit#getPluginManager()} so that the GUI
 * library's own real listener (registered by {@link InventoryAPI#init()}) is what decides the
 * cancel flag — the production decision path end to end, with nothing about it re-implemented
 * here.
 * <p>
 * MockBukkit does not apply a click's item movement (its own
 * {@code PlayerMock#simulateInventoryClick} only constructs and fires the event), so
 * {@link #applyVanillaEffect(InventoryClickEvent)} below performs the documented vanilla effect,
 * and only when the event survived uncancelled — exactly the condition under which a real server
 * applies it. That harness is the reason every refusal case has an {@link AccessMode#EDIT} twin
 * running the identical harness path and asserting that the item <em>did</em> move: if the harness
 * were inert, or if the event never reached the library, every EDIT twin would fail. A refusal
 * assertion therefore cannot pass because nothing happened.
 * <p>
 * No case below asserts a chat message. Message presence is precisely the evidence that UAT
 * accepted while the item was being duplicated.
 */
@DisplayName("RemoteBagContentGUI interaction matrix (mode x interaction)")
class RemoteBagContentGUIInteractionMatrixTest {

    /** Content area: raw slots 0-44. */
    private static final int CONTENT_SLOT = 0;
    /** Second content slot, used as a drag target. */
    private static final int CONTENT_SLOT_2 = 1;
    /** Toolbar row: raw slots 45-53. Slot 49 is the save button (bottom row, column 4). */
    private static final int TOOLBAR_SAVE_SLOT = 49;
    /** First raw slot of the viewer's own inventory in a 54-slot view. */
    private static final int OWN_INVENTORY_SLOT = 54;
    /** Hotbar index used by the number-key swap cases. */
    private static final int HOTBAR_INDEX = 3;

    private static final int PAGE = 1;

    private ServerMock server;
    private PlayerMock viewer;
    private InventoryAPI inventoryApi;

    private UltiToolsPlugin mockPlugin;
    private RemoteBagService bagService;
    private BagLockService lockService;
    private RemoteBagConfig config;
    private UUID ownerUuid;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkitSupport.bootstrapLiveServer();
        UltiRemoteBagTestHelper.setUp();

        // A real JavaPlugin, so the GUI library's real listener can be registered with the real
        // plugin manager. InventoryAPI's constructor installs itself as the static instance that
        // Gui#open() and the listener both consult.
        inventoryApi = new InventoryAPI(MockBukkit.createMockPlugin("ObliviateHost"));
        inventoryApi.init();

        bagService = mock(RemoteBagService.class);
        lockService = mock(BagLockService.class);
        config = UltiRemoteBagTestHelper.createDefaultConfig();

        mockPlugin = mock(UltiToolsPlugin.class);
        lenient().when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

        ownerUuid = UUID.randomUUID();
        viewer = server.addPlayer("Viewer");
    }

    @AfterEach
    void tearDown() throws Exception {
        // Deliberately NOT InventoryAPI#unload(): it routes through UniversalScheduler, which asks
        // ServerMock for a region scheduler and gets UnimplementedOperationException. Unregistering
        // the listener directly is pure Bukkit and achieves the same isolation.
        HandlerList.unregisterAll(inventoryApi.getListener());
        UltiRemoteBagTestHelper.tearDown();
        MockBukkitSupport.safeUnmock();
    }

    // ==================== Read-only mode: nothing may move ====================

    @Nested
    @DisplayName("READ_ONLY mode")
    class ReadOnly {

        @Test
        @DisplayName("A normal click on a stored item leaves it in the bag and out of the viewer's hands")
        void normalClickCannotTakeItem() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.READ_ONLY);

            InventoryClickEvent event = click(gui, CONTENT_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL);

            assertThat(event.isCancelled()).as("read-only click must be cancelled").isTrue();
            assertBagStillHoldsTheDiamond(gui);
            assertViewerHoldsNoDiamond();
        }

        @Test
        @DisplayName("A shift-click on a stored item does not move it into the viewer's inventory")
        void shiftClickCannotTakeItem() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.READ_ONLY);

            InventoryClickEvent event = click(gui, CONTENT_SLOT, ClickType.SHIFT_LEFT,
                    InventoryAction.MOVE_TO_OTHER_INVENTORY);

            assertThat(event.isCancelled()).as("read-only shift-click must be cancelled").isTrue();
            assertBagStillHoldsTheDiamond(gui);
            assertViewerHoldsNoDiamond();
        }

        @Test
        @DisplayName("A number-key swap does not exchange a stored item for a hotbar item")
        void hotbarSwapCannotTakeItem() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.READ_ONLY);
            viewer.getInventory().setItem(HOTBAR_INDEX, new ItemStack(Material.DIRT));

            InventoryClickEvent event = hotbarSwap(gui, CONTENT_SLOT);

            assertThat(event.isCancelled()).as("read-only number-key swap must be cancelled").isTrue();
            assertBagStillHoldsTheDiamond(gui);
            assertViewerHoldsNoDiamond();
            assertThat(viewer.getInventory().getItem(HOTBAR_INDEX))
                    .as("the viewer's own hotbar item must not have been swapped away")
                    .isNotNull();
        }

        @Test
        @DisplayName("A drag of the viewer's own item over content slots puts nothing into the bag")
        void dragCannotInsertItem() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.READ_ONLY);

            InventoryDragEvent event = dragEmeraldOver(gui, CONTENT_SLOT_2);

            assertThat(event.isCancelled()).as("read-only drag must be cancelled").isTrue();
            assertThat(gui.getInventory().getItem(CONTENT_SLOT_2))
                    .as("nothing may be dragged into a read-only bag")
                    .isNull();
        }

        @Test
        @DisplayName("A shift-click from the viewer's own inventory does not insert into the bag")
        void shiftClickFromOwnInventoryCannotInsert() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.READ_ONLY);
            viewer.getInventory().setItem(0, new ItemStack(Material.EMERALD));

            InventoryClickEvent event = click(gui, OWN_INVENTORY_SLOT, ClickType.SHIFT_LEFT,
                    InventoryAction.MOVE_TO_OTHER_INVENTORY);

            assertThat(event.isCancelled())
                    .as("a shift-click from the player side into a read-only bag must be cancelled")
                    .isTrue();
            assertThat(gui.getInventory().contains(Material.EMERALD))
                    .as("the bag must not have gained the viewer's emerald")
                    .isFalse();
        }

        @Test
        @DisplayName("A double-click collect from the viewer's own inventory does not pull items out of the bag")
        void collectToCursorCannotTakeItem() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.READ_ONLY);

            InventoryClickEvent event = click(gui, OWN_INVENTORY_SLOT, ClickType.DOUBLE_CLICK,
                    InventoryAction.COLLECT_TO_CURSOR);

            assertThat(event.isCancelled())
                    .as("a collect-to-cursor from the player side must be cancelled")
                    .isTrue();
            assertBagStillHoldsTheDiamond(gui);
        }

        @Test
        @DisplayName("The disabled Save icon cannot be picked up")
        void disabledSaveIconCannotBePickedUp() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.READ_ONLY);
            ItemStack disabledSave = gui.getInventory().getItem(TOOLBAR_SAVE_SLOT);
            assertThat(disabledSave)
                    .as("precondition: the read-only toolbar renders a disabled save icon")
                    .isNotNull();

            InventoryClickEvent event = click(gui, TOOLBAR_SAVE_SLOT, ClickType.LEFT,
                    InventoryAction.PICKUP_ALL);

            assertThat(event.isCancelled()).as("a toolbar icon click must be cancelled").isTrue();
            assertThat(gui.getInventory().getItem(TOOLBAR_SAVE_SLOT))
                    .as("the disabled save icon must still be in its slot")
                    .isEqualTo(disabledSave);
            assertThat(viewer.getItemOnCursor().getType())
                    .as("the disabled save icon must not be on the viewer's cursor")
                    .isEqualTo(Material.AIR);
        }

        @Test
        @DisplayName("The viewer may still handle items inside their own inventory")
        void ownInventoryPickupIsNotBlocked() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.READ_ONLY);
            viewer.getInventory().setItem(0, new ItemStack(Material.EMERALD));

            InventoryClickEvent event = click(gui, OWN_INVENTORY_SLOT, ClickType.LEFT,
                    InventoryAction.PICKUP_ALL);

            assertThat(event.isCancelled())
                    .as("read-only guards the bag, not the viewer's own inventory")
                    .isFalse();
            assertThat(viewer.getItemOnCursor().getType())
                    .as("the viewer's own emerald reached their cursor")
                    .isEqualTo(Material.EMERALD);
        }
    }

    // ==================== Edit mode: the bag behaves like a chest ====================

    @Nested
    @DisplayName("EDIT mode")
    class Edit {

        @Test
        @DisplayName("A normal click takes a stored item onto the cursor")
        void normalClickMovesItem() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.EDIT);

            InventoryClickEvent event = click(gui, CONTENT_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL);

            assertThat(event.isCancelled()).as("edit-mode content click must be allowed").isFalse();
            assertThat(gui.getInventory().getItem(CONTENT_SLOT)).isNull();
            assertThat(viewer.getItemOnCursor().getType()).isEqualTo(Material.DIAMOND);
        }

        @Test
        @DisplayName("A shift-click moves a stored item into the viewer's inventory")
        void shiftClickMovesItem() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.EDIT);

            InventoryClickEvent event = click(gui, CONTENT_SLOT, ClickType.SHIFT_LEFT,
                    InventoryAction.MOVE_TO_OTHER_INVENTORY);

            assertThat(event.isCancelled()).as("edit-mode shift-click must be allowed").isFalse();
            assertThat(gui.getInventory().getItem(CONTENT_SLOT)).isNull();
            assertThat(viewer.getInventory().contains(Material.DIAMOND)).isTrue();
        }

        @Test
        @DisplayName("A number-key swap exchanges a stored item with a hotbar item")
        void hotbarSwapMovesItem() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.EDIT);
            viewer.getInventory().setItem(HOTBAR_INDEX, new ItemStack(Material.DIRT));

            InventoryClickEvent event = hotbarSwap(gui, CONTENT_SLOT);

            assertThat(event.isCancelled()).as("edit-mode number-key swap must be allowed").isFalse();
            assertThat(gui.getInventory().getItem(CONTENT_SLOT))
                    .isEqualTo(new ItemStack(Material.DIRT));
            assertThat(viewer.getInventory().getItem(HOTBAR_INDEX))
                    .isEqualTo(new ItemStack(Material.DIAMOND));
        }

        @Test
        @DisplayName("A drag places the viewer's item into a content slot")
        void dragInsertsItem() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.EDIT);

            InventoryDragEvent event = dragEmeraldOver(gui, CONTENT_SLOT_2);

            assertThat(event.isCancelled()).as("edit-mode content drag must be allowed").isFalse();
            assertThat(gui.getInventory().getItem(CONTENT_SLOT_2))
                    .as("the dragged emerald reached the content slot")
                    .isNotNull();
        }

        @Test
        @DisplayName("The Save icon cannot be picked up out of the toolbar")
        void toolbarIconCannotBePickedUp() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.EDIT);
            ItemStack saveIcon = gui.getInventory().getItem(TOOLBAR_SAVE_SLOT);
            assertThat(saveIcon).as("precondition: the edit toolbar renders a save icon").isNotNull();

            InventoryClickEvent event = click(gui, TOOLBAR_SAVE_SLOT, ClickType.LEFT,
                    InventoryAction.PICKUP_ALL);

            assertThat(event.isCancelled()).as("a toolbar icon click must be cancelled").isTrue();
            assertThat(gui.getInventory().getItem(TOOLBAR_SAVE_SLOT)).isEqualTo(saveIcon);
            assertThat(viewer.getItemOnCursor().getType()).isEqualTo(Material.AIR);
        }

        @Test
        @DisplayName("A drag that reaches into the toolbar row is refused outright")
        void dragOverToolbarIsRefused() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.EDIT);
            ItemStack saveIcon = gui.getInventory().getItem(TOOLBAR_SAVE_SLOT);

            InventoryDragEvent event = dragEmeraldOver(gui, CONTENT_SLOT_2, TOOLBAR_SAVE_SLOT);

            assertThat(event.isCancelled())
                    .as("a drag touching the toolbar must be cancelled, not partially applied")
                    .isTrue();
            assertThat(gui.getInventory().getItem(TOOLBAR_SAVE_SLOT))
                    .as("the toolbar icon must survive the drag")
                    .isEqualTo(saveIcon);
            assertThat(gui.getInventory().getItem(CONTENT_SLOT_2))
                    .as("no part of a refused drag may be applied")
                    .isNull();
        }
    }

    // ==================== Harness ====================

    /**
     * Opens a real content GUI for {@link #viewer} whose page 1 holds a single DIAMOND in slot 0,
     * through the production open path: {@code Gui#open()} registers the page with the library and
     * opens the inventory, and the {@link InventoryOpenEvent} drives
     * {@code BaseInventoryPage#onOpen}, so the toolbar and the stored contents are rendered by
     * production code rather than placed here.
     */
    private RemoteBagContentGUI openGuiHoldingDiamond(AccessMode mode) {
        ItemStack[] page = new ItemStack[45];
        page[CONTENT_SLOT] = new ItemStack(Material.DIAMOND);
        lenient().when(bagService.getBagPage(ownerUuid, PAGE)).thenReturn(page);

        RemoteBagContentGUI gui = new RemoteBagContentGUI(viewer, mockPlugin, ownerUuid, PAGE,
                bagService, lockService, config, mode);
        gui.open();
        Bukkit.getPluginManager().callEvent(new InventoryOpenEvent(viewer.getOpenInventory()));

        assertThat(gui.getInventory().getItem(CONTENT_SLOT))
                .as("precondition: the stored diamond is rendered into the open page")
                .isEqualTo(new ItemStack(Material.DIAMOND));
        return gui;
    }

    private InventoryClickEvent click(RemoteBagContentGUI gui, int rawSlot, ClickType clickType,
                                     InventoryAction action) {
        InventoryView view = viewer.getOpenInventory();
        InventoryType.SlotType slotType = rawSlot < gui.getInventory().getSize()
                ? InventoryType.SlotType.CONTAINER
                : InventoryType.SlotType.QUICKBAR;
        InventoryClickEvent event = new InventoryClickEvent(view, slotType, rawSlot, clickType, action);
        Bukkit.getPluginManager().callEvent(event);
        applyVanillaEffect(event);
        return event;
    }

    private InventoryClickEvent hotbarSwap(RemoteBagContentGUI gui, int rawSlot) {
        InventoryView view = viewer.getOpenInventory();
        InventoryType.SlotType slotType = rawSlot < gui.getInventory().getSize()
                ? InventoryType.SlotType.CONTAINER
                : InventoryType.SlotType.QUICKBAR;
        InventoryClickEvent event = new InventoryClickEvent(view, slotType, rawSlot,
                ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, HOTBAR_INDEX);
        Bukkit.getPluginManager().callEvent(event);
        applyVanillaEffect(event);
        return event;
    }

    private InventoryDragEvent dragEmeraldOver(RemoteBagContentGUI gui, int... rawSlots) {
        ItemStack dragged = new ItemStack(Material.EMERALD, rawSlots.length);
        Map<Integer, ItemStack> newItems = new HashMap<>();
        for (int rawSlot : rawSlots) {
            newItems.put(rawSlot, new ItemStack(Material.EMERALD, 1));
        }
        InventoryDragEvent event = new InventoryDragEvent(viewer.getOpenInventory(), null, dragged,
                false, newItems);
        Bukkit.getPluginManager().callEvent(event);
        applyVanillaEffect(event);
        return event;
    }

    /**
     * Performs the vanilla server-side effect of a click that the plugin left uncancelled. Only the
     * four actions this matrix uses are modelled; any other action fails loudly rather than
     * silently doing nothing, so a case added later cannot assert "the item did not move" against
     * an effect this harness never implements.
     */
    private void applyVanillaEffect(InventoryClickEvent event) {
        if (event.isCancelled()) {
            return;
        }
        InventoryView view = event.getView();
        int rawSlot = event.getRawSlot();
        switch (event.getAction()) {
            case PICKUP_ALL: {
                ItemStack picked = view.getItem(rawSlot);
                view.setItem(rawSlot, null);
                viewer.setItemOnCursor(picked);
                break;
            }
            case MOVE_TO_OTHER_INVENTORY: {
                ItemStack moved = view.getItem(rawSlot);
                if (moved == null) {
                    break;
                }
                view.setItem(rawSlot, null);
                if (rawSlot < view.getTopInventory().getSize()) {
                    viewer.getInventory().addItem(moved);
                } else {
                    view.getTopInventory().addItem(moved);
                }
                break;
            }
            case HOTBAR_SWAP: {
                Inventory own = viewer.getInventory();
                ItemStack inSlot = view.getItem(rawSlot);
                ItemStack inHotbar = own.getItem(event.getHotbarButton());
                view.setItem(rawSlot, inHotbar);
                own.setItem(event.getHotbarButton(), inSlot);
                break;
            }
            case COLLECT_TO_CURSOR: {
                // Vanilla sweeps matching stacks from both inventories onto the cursor. Only the
                // bag side matters to these cases, so that is what is modelled.
                Inventory top = view.getTopInventory();
                for (int slot = 0; slot < top.getSize(); slot++) {
                    ItemStack candidate = top.getItem(slot);
                    if (candidate != null && candidate.getType() != Material.AIR) {
                        top.setItem(slot, null);
                        viewer.setItemOnCursor(candidate);
                        break;
                    }
                }
                break;
            }
            default:
                throw new IllegalStateException(
                        "this harness models no vanilla effect for " + event.getAction()
                                + "; add one before asserting against it");
        }
    }

    /** Applies an uncancelled drag the way vanilla does: every computed slot receives its share. */
    private void applyVanillaEffect(InventoryDragEvent event) {
        if (event.isCancelled()) {
            return;
        }
        for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
            event.getView().setItem(entry.getKey(), entry.getValue());
        }
    }

    private void assertBagStillHoldsTheDiamond(RemoteBagContentGUI gui) {
        assertThat(gui.getInventory().getItem(CONTENT_SLOT))
                .as("the stored diamond must still be in the bag")
                .isEqualTo(new ItemStack(Material.DIAMOND));
    }

    private void assertViewerHoldsNoDiamond() {
        assertThat(viewer.getInventory().contains(Material.DIAMOND))
                .as("the viewer's inventory must not have gained the bag's diamond")
                .isFalse();
        assertThat(viewer.getItemOnCursor().getType())
                .as("the bag's diamond must not be on the viewer's cursor")
                .isEqualTo(Material.AIR);
    }
}
