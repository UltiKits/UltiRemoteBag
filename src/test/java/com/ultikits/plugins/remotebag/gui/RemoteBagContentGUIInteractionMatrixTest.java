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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * assertion therefore cannot pass because nothing happened. Every case in the {@code ReadOnly} class
 * has that twin, the two player-side effects included — {@code MOVE_TO_OTHER_INVENTORY} from the
 * viewer's own side and {@code COLLECT_TO_CURSOR}.
 * <p>
 * No case below asserts a chat message as its WHOLE verdict; message presence is precisely the
 * evidence UAT accepted while the item was being duplicated. Two drag cases assert a message in
 * ADDITION to the inventory contents, because a cancelled drag runs no icon action and moves nothing,
 * so without one "refused" and "the build is broken" are the same observation.
 * <p>
 * Every drag below spans at least two slots. A one-slot quick-craft is not a drag on a real server:
 * with {@code carried.getCount()} items on the cursor, vanilla registers a hovered slot only while
 * {@code carried.getCount() > quickcraftSlots.size()}, and on completion a single registered slot is
 * re-dispatched as a {@code PICKUP} click with no {@link InventoryDragEvent} constructed at all
 * (measured in {@code AbstractContainerMenu#doClick}, {@code paper-1.21.11.jar}). The dragged stack is
 * therefore sized to the number of slots, and shrinking either back to one would make the case
 * describe a state no server can produce.
 */
@DisplayName("RemoteBagContentGUI interaction matrix (mode x interaction)")
class RemoteBagContentGUIInteractionMatrixTest {

    /** Content area: raw slots 0-44. */
    private static final int CONTENT_SLOT = 0;
    /** Second content slot, used as a drag target. */
    private static final int CONTENT_SLOT_2 = 1;
    /** Toolbar row: raw slots 45-53. Slot 49 is the save button (bottom row, column 4). */
    private static final int TOOLBAR_SAVE_SLOT = 49;
    /** Bottom row, column 3: the Refresh button in read-only mode, a filler in edit mode. */
    private static final int REFRESH_SLOT = 48;
    /** First raw slot of the viewer's own inventory in a 54-slot view. */
    private static final int OWN_INVENTORY_SLOT = 54;
    /**
     * The viewer's own inventory index that {@link #OWN_INVENTORY_SLOT} addresses.
     * <p>
     * {@code InventoryView#convertSlot(54)} is 9 on both MockBukkit's {@code InventoryViewMock} and
     * CraftBukkit, so this is the index a real server would touch. It is spelled out because
     * {@code InventoryViewMock#getItem(54)} does NOT go through {@code convertSlot} — it subtracts
     * the top size and indexes the bottom inventory directly, giving slot 0 — so a harness that
     * addressed the player side through the VIEW would line up with MockBukkit and miss on a real
     * server. {@link #applyVanillaEffect(InventoryClickEvent)} therefore addresses player-side slots
     * through {@code event.getSlot()} against the player's own inventory. No raw slot in a 54-slot
     * view makes the two mappings agree, so there is no constant to pick instead.
     */
    private static final int OWN_INVENTORY_INDEX = 9;
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
                    .isEqualTo(new ItemStack(Material.DIRT));
        }

        @Test
        @DisplayName("A drag of the viewer's own item over content slots puts nothing into the bag, and says why")
        void dragCannotInsertItem() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.READ_ONLY);

            InventoryDragEvent event = dragEmeraldOver(gui, CONTENT_SLOT, CONTENT_SLOT_2);

            assertThat(event.isCancelled()).as("read-only drag must be cancelled").isTrue();
            assertBagUnchangedByADrag(gui);
            // In addition to the contents, not instead of them: a cancelled drag produces no other
            // feedback whatsoever, so silence would be indistinguishable from a broken build.
            assertThat(messagesSentToViewer())
                    .as("a refused drag has to tell the viewer why")
                    .contains("msg_readonly_no_move");
        }

        @Test
        @DisplayName("A drag confined to the viewer's own inventory is allowed, as clicks there are")
        void dragWithinOwnInventoryIsNotBlocked() {
            // Read-only guards the bag, not the viewer's own inventory -- the same policy the click
            // path implements in ownInventoryPickupIsNotBlocked. Refusing the whole event on mode
            // alone stopped an administrator rearranging their OWN inventory while looking at somebody
            // else's bag, and stopped it silently.
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.READ_ONLY);

            InventoryDragEvent event = dragEmeraldOver(gui, OWN_INVENTORY_SLOT, OWN_INVENTORY_SLOT + 1);

            // The library sets the cancel flag on EVERY drag from this method's return value, and the
            // library's own default returns "cancel", so an uncancelled drag can only mean onDrag
            // answered ALLOW -- this is a positive observation, not an absence.
            assertThat(event.isCancelled())
                    .as("a drag that never touches the bag window must not be cancelled")
                    .isFalse();
            assertBagUnchangedByADrag(gui);
            assertThat(messagesSentToViewer())
                    .as("and nothing was refused, so nothing is announced")
                    .isEmpty();
        }

        @Test
        @DisplayName("A shift-click from the viewer's own inventory does not insert into the bag")
        void shiftClickFromOwnInventoryCannotInsert() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.READ_ONLY);
            viewer.getInventory().setItem(OWN_INVENTORY_INDEX, new ItemStack(Material.EMERALD));

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
            viewer.getInventory().setItem(OWN_INVENTORY_INDEX, new ItemStack(Material.EMERALD));

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
        @DisplayName("A drag spreads the viewer's stack across two content slots")
        void dragInsertsItem() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.EDIT);
            // Slot 0 holds the stored diamond, so drag across two EMPTY content slots: a real drag
            // never includes a slot whose contents cannot quick-replace the carried stack.
            gui.getInventory().setItem(CONTENT_SLOT_2, null);

            InventoryDragEvent event = dragEmeraldOver(gui, CONTENT_SLOT_2, CONTENT_SLOT_2 + 1);

            assertThat(event.isCancelled()).as("edit-mode content drag must be allowed").isFalse();
            assertThat(gui.getInventory().getItem(CONTENT_SLOT_2))
                    .as("the dragged stack reached the first content slot")
                    .isEqualTo(new ItemStack(Material.EMERALD));
            assertThat(gui.getInventory().getItem(CONTENT_SLOT_2 + 1))
                    .as("and the second -- which is what distinguishes a drag from a click")
                    .isEqualTo(new ItemStack(Material.EMERALD));
        }

        @Test
        @DisplayName("A shift-click from the viewer's own inventory inserts into the bag")
        void shiftClickFromOwnInventoryInserts() {
            // The EDIT twin of shiftClickFromOwnInventoryCannotInsert: without it, the harness's
            // player-side MOVE_TO_OTHER_INVENTORY effect is never proven to move anything, so that
            // refusal case could pass against an inert harness.
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.EDIT);
            viewer.getInventory().setItem(OWN_INVENTORY_INDEX, new ItemStack(Material.EMERALD));

            InventoryClickEvent event = click(gui, OWN_INVENTORY_SLOT, ClickType.SHIFT_LEFT,
                    InventoryAction.MOVE_TO_OTHER_INVENTORY);

            assertThat(event.isCancelled())
                    .as("an edit-mode shift-click from the player side must be allowed")
                    .isFalse();
            assertThat(gui.getInventory().contains(Material.EMERALD))
                    .as("the bag gained the viewer's emerald")
                    .isTrue();
            assertThat(viewer.getInventory().getItem(OWN_INVENTORY_INDEX))
                    .as("and it left the viewer's own inventory")
                    .isNull();
        }

        @Test
        @DisplayName("A double-click collect pulls a stored item onto the cursor")
        void collectToCursorTakesItem() {
            // The EDIT twin of collectToCursorCannotTakeItem, for the same reason: it proves the
            // harness's COLLECT_TO_CURSOR effect really sweeps the bag.
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.EDIT);

            InventoryClickEvent event = click(gui, OWN_INVENTORY_SLOT, ClickType.DOUBLE_CLICK,
                    InventoryAction.COLLECT_TO_CURSOR);

            assertThat(event.isCancelled())
                    .as("an edit-mode collect-to-cursor must be allowed")
                    .isFalse();
            assertThat(viewer.getItemOnCursor().getType())
                    .as("the bag's diamond was swept onto the cursor")
                    .isEqualTo(Material.DIAMOND);
            assertThat(gui.getInventory().getItem(CONTENT_SLOT))
                    .as("and left the bag")
                    .isNull();
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
        @DisplayName("A drag that reaches into the toolbar row is refused outright, and says why")
        void dragOverToolbarIsRefused() {
            // The constructed event is deliberately broader than vanilla: every toolbar slot holds a
            // named glass pane or a sunflower, none of which stacks with an EMERALD, so
            // canItemQuickReplace/mayPlace exclude slot 49 from a real drag's getRawSlots(). The guard
            // is still load-bearing -- a player carrying an anvil-renamed pane whose components match a
            // filler exactly would satisfy isSameItemSameComponents -- so this case proves the branch,
            // not a production scenario, and must not be deleted as unreachable.
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.EDIT);
            gui.getInventory().setItem(CONTENT_SLOT_2, null);
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
            assertThat(messagesSentToViewer())
                    .as("in addition to the contents: a cancelled drag has no other feedback")
                    .contains("msg_cannot_drag_toolbar");
        }

        @Test
        @DisplayName("A drag confined to the viewer's own inventory is allowed in edit mode too")
        void dragWithinOwnInventoryIsNotBlocked() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.EDIT);

            InventoryDragEvent event = dragEmeraldOver(gui, OWN_INVENTORY_SLOT, OWN_INVENTORY_SLOT + 1);

            assertThat(event.isCancelled())
                    .as("a drag that never touches the bag window must not be cancelled")
                    .isFalse();
            assertThat(messagesSentToViewer()).isEmpty();
        }
    }

    // ==================== Cross-cutting: other plugins, and the Refresh button ====================

    @Nested
    @DisplayName("Interaction with other plugins and with the Refresh button")
    class CrossCutting {

        @Test
        @DisplayName("An edit-mode click another plugin cancelled stays cancelled")
        void editModeDoesNotUnCancelAnotherPluginsDecision() {
            // The library turns this page's ALLOW into event.setCancelled(false), which CLEARS a
            // cancellation rather than declining to add one, and its listener is NORMAL priority with
            // ignoreCancelled = false. Answering ALLOW unconditionally therefore overrode an anti-cheat
            // or region plugin (pull request #34 gate-1 review, IN-12).
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.EDIT);
            Listener otherPlugin = registerCancellingListenerAtLowest();
            try {
                InventoryClickEvent event = click(gui, CONTENT_SLOT, ClickType.LEFT,
                        InventoryAction.PICKUP_ALL);

                assertThat(event.isCancelled())
                        .as("another plugin's cancellation must survive this page's edit-mode answer")
                        .isTrue();
                assertBagStillHoldsTheDiamond(gui);
                assertViewerHoldsNoDiamond();
            } finally {
                HandlerList.unregisterAll(otherPlugin);
            }
        }

        @Test
        @DisplayName("Control: with no other plugin involved the identical click is allowed")
        void theSameClickIsAllowedWithoutTheOtherPlugin() {
            // Without this, the case above could pass because the click was refused for some unrelated
            // reason rather than because the cancellation was respected.
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.EDIT);

            InventoryClickEvent event = click(gui, CONTENT_SLOT, ClickType.LEFT,
                    InventoryAction.PICKUP_ALL);

            assertThat(event.isCancelled()).isFalse();
            assertThat(viewer.getItemOnCursor().getType()).isEqualTo(Material.DIAMOND);
        }

        @Test
        @DisplayName("An edit-mode drag another plugin cancelled stays cancelled")
        void editModeDragDoesNotUnCancelAnotherPluginsDecision() {
            // Same defect class through the other entry point: the library applies
            // setCancelled(!onDrag(...)) unconditionally, so a drag was un-cancelled the same way.
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.EDIT);
            gui.getInventory().setItem(CONTENT_SLOT_2, null);
            Listener otherPlugin = registerCancellingDragListenerAtLowest();
            try {
                InventoryDragEvent event = dragEmeraldOver(gui, CONTENT_SLOT_2, CONTENT_SLOT_2 + 1);

                assertThat(event.isCancelled())
                        .as("another plugin's cancellation of a drag must survive too")
                        .isTrue();
                assertThat(gui.getInventory().getItem(CONTENT_SLOT_2)).isNull();
            } finally {
                HandlerList.unregisterAll(otherPlugin);
            }
        }

        @Test
        @DisplayName("Refresh clears a slot the owner has emptied since the page opened")
        void refreshClearsAVacatedSlot() {
            // The read-only Refresh button is loadBagContents()'s only other caller, and it wrote only
            // the non-null entries -- so a refreshed view showed the union of what was displayed before
            // and what is stored now, i.e. items the owner had already taken out (pull request #34
            // gate-1 review, IN-11).
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.READ_ONLY);
            // The owner empties the page elsewhere while this read-only view stays open.
            lenient().when(bagService.getBagPage(ownerUuid, PAGE)).thenReturn(new ItemStack[45]);
            // The owner still holds the page, so Refresh re-reads rather than upgrading to edit mode.
            lenient().when(lockService.canUpgradeToEdit(ownerUuid, PAGE)).thenReturn(false);

            click(gui, REFRESH_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL);

            assertThat(gui.getInventory().getItem(CONTENT_SLOT))
                    .as("a refreshed read-only view must not still show an item that is gone")
                    .isNull();
        }

        @Test
        @DisplayName("Control: Refresh still shows an item that is still stored")
        void refreshKeepsAnItemThatIsStillThere() {
            RemoteBagContentGUI gui = openGuiHoldingDiamond(AccessMode.READ_ONLY);
            lenient().when(lockService.canUpgradeToEdit(ownerUuid, PAGE)).thenReturn(false);

            click(gui, REFRESH_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL);

            assertThat(gui.getInventory().getItem(CONTENT_SLOT))
                    .as("control: clearing first must not wipe the page it is meant to redraw")
                    .isEqualTo(new ItemStack(Material.DIAMOND));
        }
    }

    // ==================== Harness ====================

    /** A stand-in for an anti-cheat or region plugin that cancels the click before the library sees it. */
    private Listener registerCancellingListenerAtLowest() {
        Listener listener = new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void onClick(InventoryClickEvent event) {
                event.setCancelled(true);
            }
        };
        Bukkit.getPluginManager().registerEvents(listener, MockBukkit.createMockPlugin("OtherPlugin"));
        return listener;
    }

    private Listener registerCancellingDragListenerAtLowest() {
        Listener listener = new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void onDrag(InventoryDragEvent event) {
                event.setCancelled(true);
            }
        };
        Bukkit.getPluginManager().registerEvents(listener, MockBukkit.createMockPlugin("OtherDragPlugin"));
        return listener;
    }

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
                if (rawSlot < view.getTopInventory().getSize()) {
                    viewer.setItemOnCursor(view.getItem(rawSlot));
                    view.setItem(rawSlot, null);
                } else {
                    viewer.setItemOnCursor(viewer.getInventory().getItem(event.getSlot()));
                    viewer.getInventory().setItem(event.getSlot(), null);
                }
                break;
            }
            case MOVE_TO_OTHER_INVENTORY: {
                boolean fromBag = rawSlot < view.getTopInventory().getSize();
                ItemStack moved = fromBag
                        ? view.getItem(rawSlot)
                        : viewer.getInventory().getItem(event.getSlot());
                if (moved == null) {
                    break;
                }
                if (fromBag) {
                    view.setItem(rawSlot, null);
                    viewer.getInventory().addItem(moved);
                } else {
                    // Player-side slots are addressed through getSlot(), not through the view: see
                    // OWN_INVENTORY_INDEX for why the view's raw-slot mapping differs between
                    // MockBukkit and a real server.
                    viewer.getInventory().setItem(event.getSlot(), null);
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

    /**
     * Every chat line the page sent since the last read, drained from MockBukkit's message queue and
     * joined. {@code mockPlugin.i18n} echoes its key, so a line is identified by the key that produced
     * it; the lines also carry a {@link org.bukkit.ChatColor} prefix, which is why this is a string to
     * search rather than a list to match elements against.
     */
    private String messagesSentToViewer() {
        List<String> messages = new ArrayList<>();
        String next;
        while ((next = viewer.nextMessage()) != null) {
            messages.add(next);
        }
        return String.join("\n", messages);
    }

    /** No content slot may have changed: slot 0 keeps its diamond and nothing else holds anything. */
    private void assertBagUnchangedByADrag(RemoteBagContentGUI gui) {
        assertBagStillHoldsTheDiamond(gui);
        for (int slot = 1; slot < 45; slot++) {
            assertThat(gui.getInventory().getItem(slot))
                    .as("content slot %d must be untouched by a refused drag", slot)
                    .isNull();
        }
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
