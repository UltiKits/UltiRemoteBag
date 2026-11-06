package com.ultikits.plugins.remotebag.gui;

import com.ultikits.plugins.remotebag.MockBukkitSupport;
import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.enums.AccessMode;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.InMemoryRemoteBagStore;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import mc.obliviate.inventory.InventoryAPI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.HandlerList;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.lang.reflect.Method;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Configurable bag capacity and lossless overflow (#38)")
class ConfigurablePageCapacityTest {
    private ServerMock server;
    private InventoryAPI api;
    private PlayerMock viewer;
    private RemoteBagConfig config;
    private RemoteBagService bags;
    private BagLockService locks;
    private UltiToolsPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkitSupport.bootstrapLiveServer();
        UltiRemoteBagTestHelper.setUp();
        api = new InventoryAPI(MockBukkit.createMockPlugin("CapacityHost"));
        api.init();
        viewer = server.addPlayer("CapacityPlayer");
        config = new RemoteBagConfig("unused.yml");
        config.setSoundEnabled(false);
        plugin = mock(UltiToolsPlugin.class);
        when(plugin.i18n(anyString())).thenAnswer(i -> i.getArgument(0));
        bags = new RemoteBagService(plugin, config);
        UltiRemoteBagTestHelper.setField(bags, "dataOperator", new InMemoryRemoteBagStore());
        locks = mock(BagLockService.class);
        when(locks.mayWrite(any(), anyInt(), any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        HandlerList.unregisterAll(api.getListener());
        UltiRemoteBagTestHelper.tearDown();
        MockBukkitSupport.safeUnmock();
    }

    @ParameterizedTest
    @CsvSource({"1,9,18", "2,18,27", "5,45,54", "6,54,54"})
    @DisplayName("Configured capacity controls empty pages and physical window height")
    void capacityAndWindow(int rows, int capacity, int window) throws Exception {
        rows(rows);
        assertThat(bags.readPage(viewer.getUniqueId(), 1).getItems()).hasSize(capacity);
        RemoteBagContentGUI gui = open(AccessMode.EDIT);
        assertThat(gui.getSize()).isEqualTo(window);
        assertThat(gui.getInventory().getItem(window - 5).getType()).isEqualTo(Material.GREEN_STAINED_GLASS_PANE);
    }

    @Test
    @DisplayName("Default remains 45 and old public constants remain compatible")
    void defaultCapacity() {
        assertThat(bags.readPage(viewer.getUniqueId(), 1).getItems()).hasSize(45);
        assertThat(RemoteBagConfig.PAGE_CAPACITY).isEqualTo(45);
        assertThat(RemoteBagConfig.MAX_ADDRESSABLE_SLOTS).isEqualTo(54);
    }

    @Test
    @DisplayName("Both screens are saved together without releasing the session")
    void savesBothScreens() throws Exception {
        rows(6);
        RemoteBagContentGUI gui = open(AccessMode.EDIT);
        gui.getInventory().setItem(44, new ItemStack(Material.DIAMOND, 3));
        click(gui, 52);
        assertThat(viewer.getOpenInventory().getTopInventory()).isEqualTo(gui.getInventory());
        assertThat(gui.getInventory().getItem(0)).isNull();
        gui.getInventory().setItem(8, new ItemStack(Material.EMERALD, 2));
        click(gui, 46);
        assertThat(gui.getInventory().getItem(44)).isEqualTo(new ItemStack(Material.DIAMOND, 3));
        click(gui, 52);
        assertThat(gui.getInventory().getItem(8)).isEqualTo(new ItemStack(Material.EMERALD, 2));
        verify(locks, never()).release(any(), anyInt(), any());
        gui.onClose(mock(InventoryCloseEvent.class));
        ItemStack[] stored = bags.readPage(viewer.getUniqueId(), 1).getItems();
        assertThat(stored[44]).isEqualTo(new ItemStack(Material.DIAMOND, 3));
        assertThat(stored[53]).isEqualTo(new ItemStack(Material.EMERALD, 2));
    }

    @Test
    @DisplayName("Shrinking retains old slot 53, exposes it and shrinks only after it is emptied")
    void shrunkPageRecovery() throws Exception {
        ItemStack[] old = new ItemStack[54];
        old[53] = new ItemStack(Material.EMERALD, 7);
        seed(old);
        rows(1);
        RemoteBagContentGUI gui = open(AccessMode.EDIT);
        for (int screen = 0; screen < 5; screen++) {
            click(gui, 16);
        }
        assertThat(gui.getInventory().getItem(8)).isEqualTo(old[53]);
        assertThat((ItemStack[]) invoke(gui, "currentContents")).hasSize(54);
        assertThat((Boolean) invoke(gui, "saveCurrentContents")).isTrue();
        assertThat(bags.readPage(viewer.getUniqueId(), 1).getItems()[53]).isEqualTo(old[53]);
        gui.getInventory().setItem(8, null);
        gui.onClose(mock(InventoryCloseEvent.class));
        RemoteBagContentGUI next = open(AccessMode.EDIT);
        assertThat((ItemStack[]) invoke(next, "currentContents")).hasSize(9);
        assertThat(next.getSize()).isEqualTo(18);
    }

    @Test
    @DisplayName("Small window toolbar and drag boundaries follow its real size")
    void smallWindowBoundaries() throws Exception {
        rows(2);
        RemoteBagContentGUI gui = open(AccessMode.EDIT);
        assertThat(click(gui, 17).isCancelled()).isFalse();
        assertThat(click(gui, 19).isCancelled()).isTrue();
        Map<Integer, ItemStack> moved = new HashMap<>();
        moved.put(17, new ItemStack(Material.DIAMOND));
        moved.put(18, new ItemStack(Material.DIAMOND));
        InventoryDragEvent drag = new InventoryDragEvent(viewer.getOpenInventory(), null,
                new ItemStack(Material.DIAMOND, 2), false, moved);
        Bukkit.getPluginManager().callEvent(drag);
        assertThat(drag.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("Last screen unused slots cannot accept items or donate filler icons")
    void finalScreenBoundaries() throws Exception {
        rows(6);
        RemoteBagContentGUI gui = open(AccessMode.EDIT);
        click(gui, 52);
        assertThat(click(gui, 8).isCancelled()).isFalse();
        assertThat(click(gui, 9).isCancelled()).isTrue();
        viewer.setItemOnCursor(gui.getInventory().getItem(9).clone());
        InventoryClickEvent collect = new InventoryClickEvent(viewer.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, 0, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR);
        Bukkit.getPluginManager().callEvent(collect);
        assertThat(collect.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("Read-only navigation exposes legacy overflow but still refuses item movement")
    void readOnlyOverflow() throws Exception {
        ItemStack[] old = new ItemStack[54];
        old[53] = new ItemStack(Material.DIAMOND);
        seed(old);
        RemoteBagContentGUI gui = open(AccessMode.READ_ONLY);
        click(gui, 52);
        assertThat(gui.getInventory().getItem(8)).isEqualTo(old[53]);
        assertThat(click(gui, 8).isCancelled()).isTrue();
        gui.onClose(mock(InventoryCloseEvent.class));
        assertThat(bags.readPage(viewer.getUniqueId(), 1).getItems()[53]).isEqualTo(old[53]);
    }

    @Test
    @DisplayName("Reload cannot resize or truncate an open editing window")
    void reloadSnapshot() throws Exception {
        rows(6);
        RemoteBagContentGUI gui = open(AccessMode.EDIT);
        click(gui, 52);
        gui.getInventory().setItem(8, new ItemStack(Material.DIAMOND));
        rows(1);
        gui.onClose(mock(InventoryCloseEvent.class));
        assertThat(bags.readPage(viewer.getUniqueId(), 1).getItems()[53]).isEqualTo(new ItemStack(Material.DIAMOND));
    }

    @Test
    @DisplayName("Main menu denominator follows actual configured and retained capacities")
    void menuDenominator() throws Exception {
        rows(2);
        seed(new ItemStack[18]);
        when(plugin.i18n("lore_slot_usage")).thenReturn("Slots Used: {0}/{1}");
        RemoteBagMainGUI main = new RemoteBagMainGUI(viewer, plugin, bags, locks, config);
        Method icon = RemoteBagMainGUI.class.getDeclaredMethod("createBagIcon", int.class);
        icon.setAccessible(true);
        mc.obliviate.inventory.Icon drawn = (mc.obliviate.inventory.Icon) icon.invoke(main, 1);
        assertThat(drawn.getItem().getItemMeta().getLore().toString()).contains("0/18");
        ItemStack[] old = new ItemStack[54];
        old[53] = new ItemStack(Material.DIAMOND);
        seed(old);
        bags.refreshBag(viewer.getUniqueId());
        drawn = (mc.obliviate.inventory.Icon) icon.invoke(main, 1);
        assertThat(drawn.getItem().getItemMeta().getLore().toString()).contains("1/54");
    }

    private void rows(int rows) throws Exception {
        UltiRemoteBagTestHelper.setField(config, "contentRows", rows);
    }

    private void seed(ItemStack[] contents) {
        assertThat(bags.savePage(viewer.getUniqueId(), 1, contents,
                bags.readPage(viewer.getUniqueId(), 1))).isNotNull();
    }

    private RemoteBagContentGUI open(AccessMode mode) {
        RemoteBagContentGUI gui = new RemoteBagContentGUI(viewer, plugin, viewer.getUniqueId(), 1,
                bags, locks, config, mode);
        gui.open();
        Bukkit.getPluginManager().callEvent(new InventoryOpenEvent(viewer.getOpenInventory()));
        return gui;
    }

    private InventoryClickEvent click(RemoteBagContentGUI gui, int slot) {
        InventoryClickEvent event = new InventoryClickEvent(viewer.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        Bukkit.getPluginManager().callEvent(event);
        return event;
    }

    private Object invoke(RemoteBagContentGUI gui, String name) throws Exception {
        Method method = RemoteBagContentGUI.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(gui);
    }
}
