package com.ultikits.plugins.remotebag.testsupport;

import com.ultikits.plugins.remotebag.MockBukkitSupport;
import com.ultikits.plugins.remotebag.UltiRemoteBag;
import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.BagOpenResult;
import com.ultikits.plugins.remotebag.entity.RemoteBagData;
import com.ultikits.plugins.remotebag.gui.RemoteBagContentGUI;
import com.ultikits.plugins.remotebag.listener.BagListener;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperator;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import mc.obliviate.inventory.InventoryAPI;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Test support: two servers that share one database, for the shared-database bag tests
 * (UltiKits/UltiRemoteBag#54, Phase 17 plan 17-84).
 *
 * <h2>What is real</h2>
 * One live MockBukkit server hosts the players and the GUI library ({@link InventoryAPI}), so pages are
 * opened, clicked and closed through real events and the library's own listener, as in
 * {@code OwnerPresenceLockHandoverTest}. Each "server" is its own {@link RemoteBagService},
 * {@link BagLockService} and {@link BagListener}, with its own logger, exactly as two server processes
 * have their own beans. Both read and write one store: one instance of the framework's real
 * {@link SimpleJsonDataOperator}, which hands out detached copies of its rows and performs
 * {@code updateIf} as one check-and-write -- the two properties a database shared by two servers has
 * that a per-server cache does not.
 *
 * <h2>What is modelled</h2>
 * The two servers share one player list (a player is on one of them at a time in the scenarios), and the
 * store is in memory. Item counts are read from the stored row itself ({@link #storedPage}), never from a
 * service's cache.
 */
public final class SharedDatabaseServers {

    public static final int PAGE_SIZE = RemoteBagConfig.PAGE_CAPACITY;
    /** Bottom row slot 4 -- the Save icon in edit mode. */
    public static final int TOOLBAR_SAVE_SLOT = 49;

    private final ServerMock server;
    private final InventoryAPI inventoryApi;
    private final RemoteBagConfig config;
    private final DataOperator<RemoteBagData> bags;

    private SharedDatabaseServers(Path storeDir) throws Exception {
        server = MockBukkitSupport.bootstrapLiveServer();
        UltiRemoteBagTestHelper.setUp();
        inventoryApi = new InventoryAPI(MockBukkit.createMockPlugin("ObliviateHost"));
        inventoryApi.init();
        config = UltiRemoteBagTestHelper.createDefaultConfig();
        bags = new SimpleJsonDataOperator<>(storeDir.toString(), RemoteBagData.class);
    }

    /** Boots the live server and an empty shared store in {@code storeDir}. */
    public static SharedDatabaseServers start(Path storeDir) throws Exception {
        return new SharedDatabaseServers(storeDir);
    }

    /** Tears the live server down. */
    public void stop() throws Exception {
        HandlerList.unregisterAll(inventoryApi.getListener());
        UltiRemoteBagTestHelper.tearDown();
        MockBukkitSupport.safeUnmock();
    }

    public ServerMock live() {
        return server;
    }

    public RemoteBagConfig config() {
        return config;
    }

    /** The shared store, as a second server sees it. */
    public DataOperator<RemoteBagData> bagStore() {
        return bags;
    }

    /** A new "server" on the shared store. */
    public Server newServer(String name) throws Exception {
        return new Server(name);
    }

    /** One server's own beans over the shared store. */
    public final class Server {
        public final String name;
        public final UltiRemoteBag plugin;
        public final PluginLogger logger;
        public final RemoteBagService bagService;
        public final BagLockService lockService;
        public final BagListener listener;

        private Server(String name) throws Exception {
            this.name = name;
            plugin = mock(UltiRemoteBag.class);
            logger = mock(PluginLogger.class);
            lenient().when(plugin.getLogger()).thenReturn(logger);
            lenient().when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
            bagService = new RemoteBagService(plugin, config);
            UltiRemoteBagTestHelper.setField(bagService, "dataOperator", bags);
            lockService = new BagLockService();
            UltiRemoteBagTestHelper.setField(lockService, "plugin", plugin);
            UltiRemoteBagTestHelper.setField(lockService, "config", config);
            listener = new BagListener(bagService, lockService);
        }

        /** The owner opens their own page on this server, as {@code /bag <page>} does. */
        public Window openAsOwner(PlayerMock owner, int page) {
            owner.closeInventory();
            BagOpenResult result = lockService.ownerOpen(owner.getUniqueId(), page, owner);
            return open(owner, owner.getUniqueId(), page, result);
        }

        /** An administrator opens another player's page on this server, as {@code /bag see} does. */
        public Window openAsAdmin(PlayerMock admin, UUID ownerUuid, int page) {
            admin.closeInventory();
            bagService.loadBagIfNeeded(ownerUuid);
            BagOpenResult result = lockService.adminOpen(ownerUuid, page, admin);
            return open(admin, ownerUuid, page, result);
        }

        private Window open(PlayerMock viewer, UUID ownerUuid, int page, BagOpenResult result) {
            if (!result.isSuccess()) {
                return new Window(viewer, null, result);
            }
            RemoteBagContentGUI gui = new RemoteBagContentGUI(viewer, plugin, ownerUuid, page,
                    bagService, lockService, config, result.getAccessMode());
            gui.open();
            Bukkit.getPluginManager().callEvent(new InventoryOpenEvent(viewer.getOpenInventory()));
            return new Window(viewer, gui, result);
        }

        /** The player leaves this server: Paper closes their open window, then fires the quit event. */
        public void quit(PlayerMock player) {
            if (InventoryAPI.getInstance().getPlayersCurrentGui(player) != null) {
                Bukkit.getPluginManager().callEvent(new InventoryCloseEvent(player.getOpenInventory()));
            }
            listener.onPlayerQuit(new PlayerQuitEvent(player, "quit"));
        }

        /** This server's module is disabled (server stop, {@code /upm uninstall}). */
        @SuppressWarnings("unchecked")
        public void shutdown() throws Exception {
            UltiRemoteBag module = mock(UltiRemoteBag.class);
            lenient().when(module.getLogger()).thenReturn(logger);
            lenient().when(module.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
            SimpleContainer context = mock(SimpleContainer.class);
            lenient().when(module.getContext()).thenReturn(context);
            lenient().when(context.getBean(any(Class.class))).thenAnswer(inv -> beanOf(inv.getArgument(0)));
            java.lang.reflect.Method onUnregister = UltiRemoteBag.class.getDeclaredMethod("onUnregister");
            onUnregister.setAccessible(true); // NOPMD - test drives the module's own disable hook
            // Protected: stubbed and called through reflection, since this helper is in another package.
            onUnregister.invoke(doCallRealMethod().when(module));
            onUnregister.invoke(module);
        }

        /** This server's bean of {@code type}, as its container would hand it out; {@code null} if it has none. */
        public Object beanOf(Class<?> type) {
            if (type == RemoteBagService.class) {
                return bagService;
            }
            if (type == BagLockService.class) {
                return lockService;
            }
            if (type == RemoteBagConfig.class) {
                return config;
            }
            return null;
        }
    }

    /** A content window one viewer has open, or the refusal they got. */
    public final class Window {
        public final PlayerMock viewer;
        public final RemoteBagContentGUI gui;
        public final BagOpenResult result;

        private Window(PlayerMock viewer, RemoteBagContentGUI gui, BagOpenResult result) {
            this.viewer = viewer;
            this.gui = gui;
            this.result = result;
        }

        public boolean isEdit() {
            return result.isEditMode();
        }

        public boolean isReadOnly() {
            return result.isReadOnlyMode();
        }

        /** Picks up whatever is in a content slot onto the cursor (vanilla, when not refused). */
        public InventoryClickEvent pickUp(int slot) {
            return click(slot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        }

        /** Places the cursor into an empty content slot (vanilla, when not refused). */
        public InventoryClickEvent place(int slot) {
            return click(slot, ClickType.LEFT, InventoryAction.PLACE_ALL);
        }

        /** Clicks the Save icon. */
        public void save() {
            click(TOOLBAR_SAVE_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        }

        public void close() {
            Bukkit.getPluginManager().callEvent(new InventoryCloseEvent(viewer.getOpenInventory()));
            returnCursorToInventory(viewer);
        }

        /** What this window shows in a content slot. */
        public ItemStack shown(int slot) {
            return gui.getInventory().getItem(slot);
        }

        private InventoryClickEvent click(int rawSlot, ClickType type, InventoryAction action) {
            InventoryView view = viewer.getOpenInventory();
            InventoryType.SlotType slotType = rawSlot < gui.getInventory().getSize()
                    ? InventoryType.SlotType.CONTAINER
                    : InventoryType.SlotType.QUICKBAR;
            InventoryClickEvent event = new InventoryClickEvent(view, slotType, rawSlot, type, action);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled() || rawSlot >= PAGE_SIZE) {
                return event;
            }
            if (action == InventoryAction.PLACE_ALL) {
                view.setItem(rawSlot, viewer.getItemOnCursor());
                viewer.setItemOnCursor(null);
            } else if (action == InventoryAction.PICKUP_ALL) {
                viewer.setItemOnCursor(view.getItem(rawSlot));
                view.setItem(rawSlot, null);
            } else {
                throw new IllegalStateException("no vanilla effect modelled for " + action);
            }
            return event;
        }
    }

    // ==================== The shared store ====================

    /** Writes a page row as an earlier server run would have, in the module's own storage format. */
    public RemoteBagData seedPage(UUID ownerUuid, int page, ItemStack[] items) {
        RemoteBagData row = RemoteBagData.create(ownerUuid, page, serialize(items));
        bags.insert(row);
        return row;
    }

    /** Overwrites a stored page as another server (or an operator with {@code sqlite3}) would. */
    public void writePageElsewhere(UUID ownerUuid, int page, ItemStack[] items) {
        RemoteBagData row = storedRow(ownerUuid, page);
        if (row == null) {
            seedPage(ownerUuid, page, items);
            return;
        }
        row.setContents(serialize(items));
        row.setLastUpdated(System.currentTimeMillis());
        bags.updateCounted(row);
    }

    /** The stored row of a page, or {@code null}. */
    public RemoteBagData storedRow(UUID ownerUuid, int page) {
        List<RemoteBagData> rows = bags.getAll(
                WhereCondition.builder().column("player_uuid").value(ownerUuid.toString()).build(),
                WhereCondition.builder().column("page_number").value(page).build());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** How many rows are stored for a page. */
    public int storedRowCount(UUID ownerUuid, int page) {
        return bags.getAll(
                WhereCondition.builder().column("player_uuid").value(ownerUuid.toString()).build(),
                WhereCondition.builder().column("page_number").value(page).build()).size();
    }

    /** The items of a stored page, read from the row itself; an empty page when no row is stored. */
    public ItemStack[] storedPage(UUID ownerUuid, int page) {
        RemoteBagData row = storedRow(ownerUuid, page);
        return row == null ? new ItemStack[PAGE_SIZE] : deserialize(row.getContents());
    }

    public static ItemStack[] pageWith(int slot, ItemStack item) {
        ItemStack[] page = new ItemStack[PAGE_SIZE];
        page[slot] = item;
        return page;
    }

    public static String serialize(ItemStack[] items) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null) {
                yaml.set("items." + i, items[i]);
            }
        }
        return yaml.saveToString();
    }

    public static ItemStack[] deserialize(String contents) {
        ItemStack[] items = new ItemStack[PAGE_SIZE];
        if (contents == null || contents.isEmpty()) {
            return items;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(contents);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException("stored page is not readable", e);
        }
        if (!yaml.isConfigurationSection("items")) {
            return items;
        }
        for (String key : yaml.getConfigurationSection("items").getKeys(false)) {
            items[Integer.parseInt(key)] = yaml.getItemStack("items." + key);
        }
        return items;
    }

    // ==================== Counting ====================

    public static int count(ItemStack[] contents, Material material) {
        int total = 0;
        for (ItemStack item : contents) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    public static int count(Inventory inventory, Material material) {
        return count(inventory.getContents(), material);
    }

    /** Every place an item can be: the owner's stored pages 1-3 and each listed player's inventory and cursor. */
    public int total(Material material, UUID ownerUuid, PlayerMock... players) {
        int total = 0;
        for (int page = 1; page <= 3; page++) {
            total += count(storedPage(ownerUuid, page), material);
        }
        for (PlayerMock player : players) {
            total += count(player.getInventory(), material);
            ItemStack onCursor = player.getItemOnCursor();
            if (onCursor != null && onCursor.getType() == material) {
                total += onCursor.getAmount();
            }
        }
        return total;
    }

    /** A cursor item is still that player's; fold it into their inventory. */
    public static void returnCursorToInventory(PlayerMock player) {
        ItemStack onCursor = player.getItemOnCursor();
        if (onCursor != null && onCursor.getType() != Material.AIR) {
            player.getInventory().addItem(onCursor);
            player.setItemOnCursor(null);
        }
    }

    /** The page numbers stored for a player, read from the store. */
    public List<Integer> storedPages(UUID ownerUuid) {
        List<Integer> pages = new ArrayList<>();
        for (RemoteBagData row : bags.getAll(
                WhereCondition.builder().column("player_uuid").value(ownerUuid.toString()).build())) {
            pages.add(row.getPageNumber());
        }
        return pages;
    }
}
