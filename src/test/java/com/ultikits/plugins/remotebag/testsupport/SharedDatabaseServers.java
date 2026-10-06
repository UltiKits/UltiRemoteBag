package com.ultikits.plugins.remotebag.testsupport;

import com.ultikits.plugins.remotebag.MockBukkitSupport;
import com.ultikits.plugins.remotebag.UltiRemoteBag;
import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.BagOpenResult;
import com.ultikits.plugins.remotebag.entity.RemoteBagEditClaim;
import com.ultikits.plugins.remotebag.entity.RemoteBagData;
import com.ultikits.plugins.remotebag.gui.RemoteBagContentGUI;
import com.ultikits.plugins.remotebag.listener.BagListener;
import com.ultikits.plugins.remotebag.service.BagEditClaimService;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
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
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p>
 * Two backends. {@link #startSqlite}: one real SQLite file that every server opens through its own framework
 * {@link SQLiteDataOperator}s (its own connections), as two servers open one MySQL database. {@link #start}:
 * the framework's JSON operator, one instance shared by every server. The edit claims
 * ({@code remote_bag_claims}, UltiKits/UltiRemoteBag#54) reach each server through a {@link PrimaryKeyStore}
 * whose shared hooks let one server act between another's read and write; on JSON it also gives the table the
 * relational primary key ({@link #startWithoutPrimaryKey} keeps the JSON behaviour).
 * <p>
 * Each server has its own wall clock, its own monotonic clock ({@code System.nanoTime} stand-in) and its own
 * main-thread queue: work the claim service hands to the main thread waits there until the test runs it, so
 * a stalled main thread is a queue nobody drains. Renewals are run on a separate thread
 * ({@link Server#renewOffMainThread}).
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

    /** Which storage the servers share. */
    public enum Backend {
        /** The framework's JSON operator: one instance, shared. */
        JSON,
        /** The framework's JSON operator, with a duplicate insert ignored as JSON does (no primary key). */
        JSON_WITHOUT_PRIMARY_KEY,
        /** One real SQLite file, opened by every server through its own operators. */
        SQLITE
    }

    private final ServerMock server;
    private final InventoryAPI inventoryApi;
    private final RemoteBagConfig config;
    private final Backend backend;
    private final Path sqliteFile;
    private final PrimaryKeyStore.Hooks hooks = new PrimaryKeyStore.Hooks();
    /** The test's own view of the shared tables (JSON: the shared instances). */
    private final DataOperator<RemoteBagData> bags;
    private final DataOperator<RemoteBagEditClaim> claims;

    private SharedDatabaseServers(Path storeDir, Backend backend) throws Exception {
        server = MockBukkitSupport.bootstrapLiveServer();
        UltiRemoteBagTestHelper.setUp();
        inventoryApi = new InventoryAPI(MockBukkit.createMockPlugin("ObliviateHost"));
        inventoryApi.init();
        config = UltiRemoteBagTestHelper.createDefaultConfig();
        this.backend = backend;
        this.sqliteFile = storeDir.resolve("shared.db");
        if (backend == Backend.SQLITE) {
            bags = openSqlite(RemoteBagData.class);
            claims = openSqlite(RemoteBagEditClaim.class);
        } else {
            bags = new SimpleJsonDataOperator<>(storeDir.resolve("remote_bags").toString(), RemoteBagData.class);
            claims = new SimpleJsonDataOperator<>(storeDir.resolve("remote_bag_claims").toString(), RemoteBagEditClaim.class);
        }
    }

    /** Boots the live server and empty shared JSON stores in {@code storeDir}; claims have a primary key. */
    public static SharedDatabaseServers start(Path storeDir) throws Exception {
        return start(storeDir, Backend.JSON);
    }

    /** As {@link #start}, but the claims store ignores a duplicate insert, as the JSON backend does. */
    public static SharedDatabaseServers startWithoutPrimaryKey(Path storeDir) throws Exception {
        return start(storeDir, Backend.JSON_WITHOUT_PRIMARY_KEY);
    }

    /** Boots the live server and one empty SQLite file in {@code storeDir} that every server opens. */
    public static SharedDatabaseServers startSqlite(Path storeDir) throws Exception {
        return start(storeDir, Backend.SQLITE);
    }

    public static SharedDatabaseServers start(Path storeDir, Backend backend) throws Exception {
        return new SharedDatabaseServers(storeDir, backend);
    }

    public Backend backend() {
        return backend;
    }

    /** A new framework SQLite operator (its own connections) on the shared file. */
    public <T extends com.ultikits.ultitools.abstracts.data.BaseDataEntity<String>> DataOperator<T> openSqlite(Class<T> type) {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + sqliteFile.toAbsolutePath());
        return new SQLiteDataOperator<>(source, type);
    }

    /** Whether the shared SQLite file has a table of this name (SQLite backend only). */
    public boolean sqliteTableExists(String table) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath());
             ResultSet rows = connection.createStatement().executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '" + table + "'")) {
            return rows.next();
        }
    }

    /** The shared claims table, as the test reads it (no hooks). */
    public DataOperator<RemoteBagEditClaim> claimStore() {
        return claims;
    }

    /** The hooks shared by every server's claims store. */
    public PrimaryKeyStore.Hooks claimHooks() {
        return hooks;
    }

    /** The stored claim of a page, or {@code null}. */
    public RemoteBagEditClaim claimRow(UUID ownerUuid, int page) {
        return claims.getById(ownerUuid + ":" + page);
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
        public final BagEditClaimService claimService;
        /** This server's wall clock, in epoch milliseconds. */
        public final AtomicLong wallClock = new AtomicLong(1_700_000_000_000L);
        /** This server's monotonic clock, in nanoseconds; its origin is arbitrary, as {@code System.nanoTime}'s is. */
        public final AtomicLong nanos;
        /** Work handed to this server's main thread, run only when the test drains it. */
        public final Queue<Runnable> mainThreadTasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final DataOperator<RemoteBagData> serverBags;

        private Server(String name) throws Exception {
            this.name = name;
            this.nanos = new AtomicLong(Math.abs((long) name.hashCode()) * 1_000_000_007L);
            this.serverBags = backend == Backend.SQLITE ? openSqlite(RemoteBagData.class) : bags;
            plugin = mock(UltiRemoteBag.class);
            logger = mock(PluginLogger.class);
            lenient().when(plugin.getLogger()).thenReturn(logger);
            lenient().when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
            bagService = new RemoteBagService(plugin, config);
            UltiRemoteBagTestHelper.setField(bagService, "dataOperator", serverBags);
            lockService = new BagLockService();
            UltiRemoteBagTestHelper.setField(lockService, "plugin", plugin);
            UltiRemoteBagTestHelper.setField(lockService, "config", config);
            claimService = new BagEditClaimService();
            UltiRemoteBagTestHelper.setField(claimService, "plugin", plugin);
            UltiRemoteBagTestHelper.setField(claimService, "config", config);
            DataOperator<RemoteBagEditClaim> serverClaims = backend == Backend.SQLITE
                    ? new PrimaryKeyStore<>(openSqlite(RemoteBagEditClaim.class), false, hooks)
                    : new PrimaryKeyStore<>(claims, backend == Backend.JSON, hooks);
            UltiRemoteBagTestHelper.setField(claimService, "claims", serverClaims);
            // What the container's plugin hands out, so the claim service's own start (init) works here.
            lenient().when(plugin.getDataOperator(RemoteBagEditClaim.class)).thenReturn(serverClaims);
            UltiRemoteBagTestHelper.setField(claimService, "clock", (java.util.function.LongSupplier) wallClock::get);
            UltiRemoteBagTestHelper.setFieldIfPresent(claimService, "nanoTime", (java.util.function.LongSupplier) nanos::get);
            UltiRemoteBagTestHelper.setFieldIfPresent(claimService, "mainThread", (Executor) mainThreadTasks::add);
            UltiRemoteBagTestHelper.setFieldIfPresent(lockService, "claimService", claimService);
            listener = new BagListener(bagService, lockService);
        }

        /** Moves this server's monotonic clock on (its wall clock is left alone). */
        public void advance(long millis) {
            nanos.addAndGet(millis * 1_000_000L);
        }

        /** Runs one renewal pass of this server's claims on a thread that is not the main (test) thread. */
        public void renewOffMainThread() throws InterruptedException {
            Thread renewer = new Thread(claimService::renewDue, name + "-renewal-test-thread");
            renewer.start();
            renewer.join(10_000L);
        }

        /**
         * Starts this server's claim service the way the module does at enable ({@code init}), with the
         * background renewal ticking every {@code tickMillis} of real time; renewals become due on this server's
         * monotonic clock ({@link #nanos}).
         */
        public void startClaimService(long tickMillis) throws Exception {
            UltiRemoteBagTestHelper.setFieldIfPresent(claimService, "renewTickMillis", tickMillis);
            claimService.init();
        }

        /** The main thread runs the work queued for it (it was stalled until now). */
        public void runMainThread() {
            Runnable task;
            while ((task = mainThreadTasks.poll()) != null) {
                task.run();
            }
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
            if (type == BagEditClaimService.class) {
                return claimService;
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

        /** Clicks a toolbar icon (its action runs; nothing moves). */
        public InventoryClickEvent click(int rawSlot) {
            return click(rawSlot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
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
