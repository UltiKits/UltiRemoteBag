package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.RemoteBagData;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.utils.EconomyUtils;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for remote bag operations.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class RemoteBagService {

    private final UltiToolsPlugin plugin;
    private final RemoteBagConfig config;

    private DataOperator<RemoteBagData> dataOperator;

    /**
     * Read cache of players' bag pages, {@code Map<PlayerUUID, Map<PageNumber, ItemStack[]>>}, for the
     * views that only display them (the main window's counts, {@code /bag list}).
     * <p>
     * Nothing is ever written to the database from it (UltiKits/UltiRemoteBag#54; maintainer decision of
     * 2026-10-06 00:04). A page is written only by its own window, from what that window shows, and only if
     * the stored page is still what the window read ({@link #savePage}); a page is created, cleared or deleted
     * from what the database holds at that moment. An entry belongs to the session that read it: the owner's
     * is dropped when they quit this server, and one read for another player is dropped when the view or
     * command that read it is done, unless that player is on this server ({@link #forgetUnlessOnline}).
     */
    private final Map<UUID, Map<Integer, ItemStack[]>> bagCache = new ConcurrentHashMap<>();

    /**
     * How much one bag page holds. Fixed, and the same constant the content window sizes itself from,
     * so the two cannot disagree — they did while {@code rows_per_page} existed, which is why
     * UltiKits/UltiRemoteBag#24 ended in that key being deleted.
     */
    private static final int PAGE_CAPACITY = RemoteBagConfig.PAGE_CAPACITY;

    /**
     * The highest slot index this loader will read, exclusive. See
     * {@link RemoteBagConfig#MAX_ADDRESSABLE_SLOTS} for why the band above {@link #PAGE_CAPACITY} is
     * still read and what happens to an item found in it.
     */
    private static final int MAX_PAGE_SLOTS = RemoteBagConfig.MAX_ADDRESSABLE_SLOTS;

    public RemoteBagService(UltiToolsPlugin plugin, RemoteBagConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Initialize the service.
     */
    public void init() {
        this.dataOperator = plugin.getDataOperator(RemoteBagData.class);
    }

    /**
     * Get number of pages a player has access to.
     */
    public int getPlayerMaxPages(Player player) {
        if (!config.isPermissionBasedPages()) {
            return config.getMaxPages();
        }
        
        for (int i = config.getMaxPages(); i >= 1; i--) {
            if (player.hasPermission(config.getPermissionPrefix() + i)) {
                return i;
            }
        }
        
        return config.getDefaultPages();
    }
    
    /**
     * Load bag from database if not in cache.
     *
     * @param playerUuid 玩家 UUID
     */
    public void loadBagIfNeeded(UUID playerUuid) {
        if (bagCache.containsKey(playerUuid)) {
            return;
        }

        Map<Integer, ItemStack[]> pages = new HashMap<>();

        List<RemoteBagData> data = dataOperator.query()
                .where("player_uuid").eq(playerUuid.toString())
                .list();

        for (RemoteBagData bagData : data) {
            ItemStack[] items = deserializeItems(bagData.getContents(), bagData.getPageNumber());
            pages.put(bagData.getPageNumber(), items);
        }

        bagCache.put(playerUuid, pages);
    }
    
    /**
     * Get a specific bag page.
     */
    public ItemStack[] getBagPage(UUID playerUuid, int page) {
        Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
        if (pages == null) {
            return null;
        }
        return pages.get(page);
    }
    
    /**
     * What one window read of one stored page: its items, and what it must still find stored for its
     * save to be written ({@link #savePage}).
     */
    public static final class PageRead {
        private final ItemStack[] items;
        private final boolean stored;
        private final String contents;
        private final long lastUpdated;

        PageRead(ItemStack[] items, boolean stored, String contents, long lastUpdated) {
            this.items = items;
            this.stored = stored;
            this.contents = contents;
            this.lastUpdated = lastUpdated;
        }

        /** The page's items as read. */
        public ItemStack[] getItems() {
            return items;
        }

        /** Whether a row was stored for the page when it was read. */
        public boolean isStored() {
            return stored;
        }

        /** The stored contents as read, in the storage format; {@code null} when none were stored. */
        public String getContents() {
            return contents;
        }
    }

    /**
     * Reads one page from the database for a window that shows it, and refreshes this page in the read
     * cache if the player's bag is cached. The window keeps the result: its save is written only if the
     * stored page is still this (UltiKits/UltiRemoteBag#54).
     *
     * @param playerUuid the bag's owner
     * @param page       the page number
     * @return the page as stored now; an empty page, not stored, when no row exists
     */
    public PageRead readPage(UUID playerUuid, int page) {
        List<RemoteBagData> rows = storedRows(playerUuid, page);
        PageRead read;
        if (rows.isEmpty()) {
            read = new PageRead(new ItemStack[PAGE_CAPACITY], false, null, 0L);
        } else {
            RemoteBagData row = rows.get(0);
            read = new PageRead(deserializeItems(row.getContents(), page), true, row.getContents(), row.getLastUpdated());
        }
        Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
        if (pages != null && read.isStored()) {
            pages.put(page, com.ultikits.plugins.remotebag.util.ItemReturns.deepCopy(read.getItems()));
        }
        return read;
    }

    /**
     * Saves one page from the window that shows it, so that it can never overwrite a change another server
     * sharing the database made to the page (UltiKits/UltiRemoteBag#54; maintainer decision of 2026-10-06
     * 00:04).
     * <p>
     * The write is {@code DataOperator#updateIf} conditioned on the stored contents {@code read} saw (on its
     * {@code last_updated} for a row whose contents were {@code NULL}), so it applies only if nobody has
     * written the page since the window read it. A page that had no row is inserted only if it still has none.
     * Otherwise nothing is written, {@code log_bag_update_failed} is logged, and the stored page stays as the
     * other writer left it: an item this window put in is then not in the bag, and an item it took out is
     * still stored (two servers cannot have one page open for editing at once, so this takes a writer outside
     * the module, or a server that stalled past {@code lock.timeout_seconds}). Only this page is written; no
     * other page of the player is touched. On MySQL the comparison follows the column's collation, which
     * ignores letter case: a change that only altered the case of an item's text is not detected.
     *
     * @param playerUuid the bag's owner
     * @param page       the page number
     * @param items      what the window shows now
     * @param read       what the window read, or what its last save wrote
     * @return the page as now stored, for the window's next save; {@code null} if nothing was written
     */
    public PageRead savePage(UUID playerUuid, int page, ItemStack[] items, PageRead read) {
        return write(playerUuid, page, items, serializePage(items), read, true);
    }

    /**
     * The page as stored after a write of {@code contents} that is known to have landed, for the window's next save.
     *
     * @param items    the items written
     * @param contents {@code items} in the storage format
     * @return what the window's next save is conditioned on
     */
    public PageRead storedRead(ItemStack[] items, String contents) {
        return new PageRead(com.ultikits.plugins.remotebag.util.ItemReturns.deepCopy(items), true, contents, 0L);
    }

    /**
     * Runs {@code action} in one transaction of the page table's operator (UltiKits/UltiRemoteBag#54, gate 1 round 2).
     * On SQLite and MySQL the framework gives every operator of one module the same transaction manager, whose
     * connection is bound to the calling thread: a claim written through the claims operator inside {@code action}
     * is in the same transaction. On JSON, which belongs to one server, only this table's changes are rolled back.
     *
     * @param action what to run
     * @param <R>    its result
     * @return what {@code action} returned, after the commit
     * @throws Exception what {@code action} or the commit threw, after the rollback
     */
    public <R> R inPageTransaction(java.util.concurrent.Callable<R> action) throws Exception {
        return dataOperator.transaction(action);
    }

    /**
     * The page in the storage format, for {@link #writePage}. Run where the items may be read (the main thread).
     *
     * @param items the page's items
     * @return the stored form
     */
    public String serializePage(ItemStack[] items) {
        return serializeItems(items);
    }

    /**
     * The write of {@link #savePage}, with the contents already in the storage format and without its log line:
     * the caller decides what a write that was not applied means (UltiKits/UltiRemoteBag#54). Safe off the main
     * thread: it touches no Bukkit state.
     *
     * @param playerUuid the bag's owner
     * @param page       the page number
     * @param items      what the window shows (kept as the stored page's items in the result)
     * @param contents   {@code items} in the storage format ({@link #serializePage})
     * @param read       what the window read, or what its last save wrote
     * @return the page as now stored; {@code null} if nothing was written because the stored page is not
     *         {@code read} (or no longer exists)
     * @throws RuntimeException a storage failure, as the data operator reports it
     */
    public PageRead writePage(UUID playerUuid, int page, ItemStack[] items, String contents, PageRead read) {
        return write(playerUuid, page, items, contents, read, false);
    }

    private PageRead write(UUID playerUuid, int page, ItemStack[] items, String contents, PageRead read, boolean logMiss) {
        List<RemoteBagData> rows = storedRows(playerUuid, page);
        boolean written;
        RemoteBagData target = null;
        if (rows.isEmpty()) {
            // The row this window read was deleted since (another server, an administrator): nothing to
            // write it over, and re-creating it would undo the deletion (UltiKits/UltiRemoteBag#50).
            written = !read.isStored() && insertPage(playerUuid, page, contents);
        } else if (!read.isStored()) {
            // A row was created for this page since the window read none: it is somebody else's.
            written = false;
        } else {
            target = rows.get(0);
            WhereCondition asRead = read.contents != null
                    ? WhereCondition.builder().column("contents").value(read.contents).build()
                    : WhereCondition.builder().column("last_updated").value(read.lastUpdated).build();
            target.setContents(contents);
            target.setLastUpdated(System.currentTimeMillis());
            try {
                written = dataOperator.updateIf(target, asRead);
            } catch (DataAccessException e) {
                // updateIf wraps a field-access failure of the write in this exception; any other storage
                // failure propagates exactly as it did.
                if (!(e.getCause() instanceof IllegalAccessException)) {
                    throw e;
                }
                plugin.getLogger().error(plugin.i18n("log_bag_update_failed"), e.getCause());
                return null;
            }
        }
        if (!written) {
            if (logMiss) {
                plugin.getLogger().error(plugin.i18n("log_bag_update_failed"));
            }
            return null;
        }
        // Detached from the window's live stacks: this is the window's next baseline (gate 2 Codex P1).
        ItemStack[] stored = com.ultikits.plugins.remotebag.util.ItemReturns.deepCopy(items);
        if (logMiss) {
            // A plain save: written with its own commit, so the display cache follows now. A fenced save's caller
            // updates it after its transaction committed (rememberWritten).
            rememberWritten(playerUuid, page, stored);
        }
        return new PageRead(stored, true, contents, target != null ? target.getLastUpdated() : 0L);
    }

    /**
     * Shows a page in the display cache as written, if the player's bag is cached. Called only once the write has
     * committed, so the cache never shows a page that a rollback took back (UltiKits/UltiRemoteBag#54, gate 1 R3-3).
     *
     * @param playerUuid the bag's owner
     * @param page       the page number
     * @param items      the page as written
     */
    public void rememberWritten(UUID playerUuid, int page, ItemStack[] items) {
        Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
        if (pages != null) {
            pages.put(page, com.ultikits.plugins.remotebag.util.ItemReturns.deepCopy(items));
        }
    }

    /**
     * Inserts a page row, unless one is already stored for the page. Never overwrites a row.
     *
     * @return true if the row was inserted
     */
    private boolean insertPage(UUID playerUuid, int page, String contents) {
        if (!storedRows(playerUuid, page).isEmpty()) {
            return false;
        }
        dataOperator.insert(RemoteBagData.create(playerUuid, page, contents));
        return true;
    }

    /** The rows stored for one page, read from the database now. */
    private List<RemoteBagData> storedRows(UUID playerUuid, int page) {
        return dataOperator.query()
                .where("player_uuid").eq(playerUuid.toString())
                .where("page_number").eq(page)
                .list();
    }

    /**
     * Whether this player has any page in the cache at all.
     * <p>
     * Lets {@code /bag save} tell "there is nothing of yours on this server" from "everything is stored":
     * every change is written when it is made, so a cached page is always one that is stored.
     *
     * @param playerUuid 玩家 UUID
     * @return true if at least one page is cached for this player
     */
    public boolean hasCachedPages(UUID playerUuid) {
        Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
        return pages != null && !pages.isEmpty();
    }

    /**
     * Re-reads a player's bag into the read cache, so a decision about which pages exist is made on what
     * the database holds now, not on what this server read earlier (another server may have added or
     * deleted pages since).
     *
     * @param playerUuid the player
     */
    public void refreshBag(UUID playerUuid) {
        bagCache.remove(playerUuid);
        loadBagIfNeeded(playerUuid);
    }

    /**
     * Drops a player's read cache entry unless that player is on this server, where their own session
     * keeps it until they quit. Called when an administrator's view or command for another player is done,
     * so no copy of a bag outlives the session that read it (UltiKits/UltiRemoteBag#54).
     *
     * @param playerUuid the bag's owner
     */
    public void forgetUnlessOnline(UUID playerUuid) {
        if (Bukkit.getServer() == null || Bukkit.getPlayer(playerUuid) == null) {
            bagCache.remove(playerUuid);
        }
    }

    /**
     * Serialize items to YAML string.
     */
    private String serializeItems(ItemStack[] items) {
        if (items == null) return "";
        
        YamlConfiguration yaml = new YamlConfiguration();
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null) {
                yaml.set("items." + i, items[i]);
            }
        }
        return yaml.saveToString();
    }
    
    /**
     * Deserialize items from YAML string.
     * <p>
     * The returned array is sized to hold every slot index the stored page actually uses, which can
     * exceed {@link #PAGE_CAPACITY}. That used to happen in normal play: the array was sized from
     * {@code rows_per_page} while the content GUI exposed and saved 45 slots regardless of it, so a
     * server below 5 rows stored indices its own configured size could not address. Writing such an
     * index threw {@link ArrayIndexOutOfBoundsException}, which was caught and turned into an empty
     * page — every item on it silently destroyed on load (UltiKits/UltiRemoteBag#24).
     * <p>
     * {@code rows_per_page} has since been deleted and capacity is fixed, so nothing this module
     * writes can exceed it any more; the grow path remains for the 45-53 band, which only a
     * hand-edited or foreign-written row can hold. An item found there is loaded but is invisible in
     * the 45-slot window and is dropped by the first save of that page, so such a row should be
     * repaired before the page is opened — see {@link RemoteBagConfig#MAX_ADDRESSABLE_SLOTS}.
     * <p>
     * A single unreadable entry — a non-numeric key, a negative index, or an index at or beyond
     * {@link #MAX_PAGE_SLOTS} — is skipped with a warning naming the page and the key instead of
     * costing the whole page. The ceiling is applied before any array is allocated, so no stored key
     * can size the allocation. This method's contract is unchanged: loading never loses a stored
     * item.
     *
     * @param data       stored YAML, may be null or empty
     * @param pageNumber the page this data belongs to, for the warning messages
     * @return the page's items, indexable for every slot the stored data uses
     */
    private ItemStack[] deserializeItems(String data, int pageNumber) {
        if (data == null || data.isEmpty()) {
            return new ItemStack[PAGE_CAPACITY];
        }

        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(data);

            if (!yaml.isConfigurationSection("items")) {
                return new ItemStack[PAGE_CAPACITY];
            }

            Set<String> keys = yaml.getConfigurationSection("items").getKeys(false);
            Map<Integer, String> slots = new LinkedHashMap<>();
            int highestSlot = -1;
            for (String key : keys) {
                int slot;
                try {
                    slot = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    warnSkippedSlot(pageNumber, key, plugin.i18n("log_skipped_slot_not_a_number"));
                    continue;
                }
                if (slot < 0) {
                    warnSkippedSlot(pageNumber, key, plugin.i18n("log_skipped_slot_negative"));
                    continue;
                }
                if (!key.equals(Integer.toString(slot))) {
                    // Integer.parseInt accepts a signed or padded key, so items.'+5', items.'05' and
                    // items.' 5' all parse to 5 and collide with items.'5' on one map entry -- the
                    // second put wins and the first item is gone with no warning at all, the only
                    // path here that discarded an entry silently. Not reachable from serializeItems,
                    // which writes plain decimal indices, so it takes a hand-edited or
                    // foreign-written row to produce.
                    warnSkippedSlot(pageNumber, key, plugin.i18n("log_skipped_slot_not_canonical"));
                    continue;
                }
                if (slot >= MAX_PAGE_SLOTS) {
                    warnSkippedSlot(pageNumber, key, plugin.i18n("log_skipped_slot_beyond_max")
                            .replace("{MAX}", String.valueOf(MAX_PAGE_SLOTS)));
                    continue;
                }
                slots.put(slot, key);
                highestSlot = Math.max(highestSlot, slot);
            }

            ItemStack[] items = new ItemStack[Math.max(PAGE_CAPACITY, highestSlot + 1)];
            for (Map.Entry<Integer, String> entry : slots.entrySet()) {
                items[entry.getKey()] = yaml.getItemStack("items." + entry.getValue());
            }
            return items;
        } catch (Exception e) {
            // The module's own logger, not an inline java.util.logging one: every other line this
            // module emits carries the framework's [UltiTools] [UltiRemoteBag] prefix, and a checklist
            // row looks for this exact text, so an unprefixed line is a line a tester cannot match.
            plugin.getLogger().warn(e, plugin.i18n("log_bag_deserialize_failed"));
            return new ItemStack[PAGE_CAPACITY];
        }
    }

    private void warnSkippedSlot(int pageNumber, String key, String reason) {
        plugin.getLogger().warn(plugin.i18n("log_skipped_slot")
                .replace("{PAGE}", String.valueOf(pageNumber))
                .replace("{REASON}", reason)
                // The stored key last: it is data, so a brace sequence inside it stays as written.
                .replace("{KEY}", key));
    }

    /**
     * Clear cache for a player.
     * 
     * @param playerUuid 玩家 UUID
     */
    public void clearCache(UUID playerUuid) {
        bagCache.remove(playerUuid);
    }
    
    public RemoteBagConfig getConfig() {
        return config;
    }
    
    // ==================== GUI 支持方法 ====================
    
    /**
     * The player's stored bag pages, sorted; empty when nothing is stored.
     * <p>
     * It used to answer page 1 when nothing was stored, so an administrator's {@code /bag list} and
     * {@code /bag see} could never report that a player has no bags, even after deleting every page
     * (UltiKits/UltiRemoteBag#26). The owner's own views still offer page 1 through
     * {@link #pagesOfferedToOwner}.
     *
     * @param playerUuid the player's UUID / 玩家 UUID
     * @return the stored page numbers, sorted; empty when nothing is stored / 已存储的背包页码列表（已排序，可能为空）
     */
    public List<Integer> getPlayerBagPages(UUID playerUuid) {
        loadBagIfNeeded(playerUuid);
        Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
        if (pages == null || pages.isEmpty()) {
            return Collections.emptyList();
        }
        return pages.keySet().stream()
                .sorted()
                .collect(Collectors.toList());
    }
    
    /**
     * The pages a player is offered in their own views ({@code /bag}, {@code /bag <page>}, and the
     * next page a purchase or free creation adds): every stored page plus page 1, which is every
     * player's default page whether or not anything has been stored on it yet. A player who adds
     * page 2 before ever opening page 1 therefore keeps page 1 (UltiKits/UltiRemoteBag#26).
     *
     * @param storedPages the player's stored pages, as {@link #getPlayerBagPages} returns them
     * @return the pages offered to their owner, sorted
     */
    public static List<Integer> pagesOfferedToOwner(List<Integer> storedPages) {
        TreeSet<Integer> offered = new TreeSet<>();
        offered.add(1);
        if (storedPages != null) {
            offered.addAll(storedPages);
        }
        return new ArrayList<>(offered);
    }

    /**
     * The number the next page a purchase or a free creation adds gets: one past the highest page
     * the owner is offered, whatever gaps an administrator's deletions left below it.
     * <p>
     * The owner's window (the icon and its price), {@link #purchaseBag} (the limit check and the
     * price) and the creation itself all take this one number, so what the player is shown, what
     * they are charged for and what they are given are the same page. They used to count pages
     * instead ({@code size + 1}), which disagrees with the created page as soon as the pages are
     * not contiguous: with pages 1 and 3 the icon and the price said page 3 and page 4 was
     * created (UltiKits/UltiRemoteBag#46).
     *
     * @param pagesOffered the pages offered to the owner, as {@link #pagesOfferedToOwner} returns
     *                     them; never empty
     * @return the page number a new page gets
     */
    public static int nextPageNumber(List<Integer> pagesOffered) {
        return Collections.max(pagesOffered) + 1;
    }

    /**
     * 获取指定背包页的物品总数量
     *
     * @param playerUuid 玩家 UUID
     * @param page       背包页码
     * @return 物品总数量（所有堆叠物品的数量总和）
     */
    public int getItemCount(UUID playerUuid, int page) {
        ItemStack[] contents = getBagPage(playerUuid, page);
        if (contents == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR) {
                count += item.getAmount();
            }
        }
        return count;
    }
    
    /**
     * 获取指定背包页占用的槽位数量
     *
     * @param playerUuid 玩家 UUID
     * @param page       背包页码
     * @return 占用槽位数量
     */
    public int getStackCount(UUID playerUuid, int page) {
        ItemStack[] contents = getBagPage(playerUuid, page);
        if (contents == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * 计算购买第 N 个背包的价格
     * <p>
     * 如果启用价格递增，公式为：basePrice * (1 + priceIncreaseRate)^(n-1)
     *
     * @param bagNumber 背包编号（第几个背包）
     * @return 购买价格
     */
    public int calculatePrice(int bagNumber) {
        int basePrice = config.getBasePrice();
        if (!config.isPriceIncreaseEnabled() || bagNumber <= 1) {
            return basePrice;
        }
        double rate = config.getPriceIncreaseRate();
        double multiplier = Math.pow(1 + rate, bagNumber - 1);
        return (int) Math.ceil(basePrice * multiplier);
    }
    
    /**
     * 玩家购买新背包
     *
     * @param player 玩家
     * @return 购买是否成功
     */
    public boolean purchaseBag(Player player) {
        // Which pages exist, the price and the page created are decided on what is stored now: another
        // server may have added a page since this one read the bag (UltiKits/UltiRemoteBag#54).
        refreshBag(player.getUniqueId());
        if (!config.isEconomyEnabled() || !EconomyUtils.isAvailable()) {
            // 经济系统未启用，直接创建背包
            return createNewBagPage(player);
        }
        
        List<Integer> existingPages = pagesOfferedToOwner(getPlayerBagPages(player.getUniqueId()));
        int nextBagNum = nextPageNumber(existingPages);
        
        // 检查是否超过上限
        int maxPages = getPlayerMaxPages(player);
        if (nextBagNum > maxPages) {
            return false;
        }
        
        int price = calculatePrice(nextBagNum);
        
        // 扣款
        if (!EconomyUtils.withdraw(player, price)) {
            return false;
        }
        
        // 创建新背包页
        return createNewBagPage(player);
    }
    
    /**
     * 为玩家创建新的背包页
     * <p>
     * Writes only the new page's row, and only if no row is stored for that page number yet; no other page
     * is written (UltiKits/UltiRemoteBag#54). The cache is updated after the row is stored.
     *
     * @param player 玩家
     * @return 是否成功
     */
    private boolean createNewBagPage(Player player) {
        UUID playerUuid = player.getUniqueId();
        loadBagIfNeeded(playerUuid);
        
        List<Integer> existingPages = pagesOfferedToOwner(getPlayerBagPages(playerUuid));
        int nextPage = nextPageNumber(existingPages);
        
        // 检查是否超过上限
        int maxPages = getPlayerMaxPages(player);
        if (nextPage > maxPages) {
            return false;
        }
        
        return addEmptyPage(playerUuid, nextPage);
    }

    /**
     * Stores a new empty page and adds it to the cache. A storage failure propagates with the cache
     * unchanged; a row already stored for the page number (created on another server since this one read
     * the bag) is left alone and reported as not created.
     */
    private boolean addEmptyPage(UUID playerUuid, int page) {
        ItemStack[] emptyContents = new ItemStack[PAGE_CAPACITY];
        if (!insertPage(playerUuid, page, serializeItems(emptyContents))) {
            return false;
        }
        bagCache.computeIfAbsent(playerUuid, k -> new HashMap<>()).put(page, emptyContents);
        return true;
    }

    // ==================== 管理员命令支持方法 ====================
    
    /**
     * 为指定玩家创建新的背包页（管理员操作）
     *
     * @param playerUuid 玩家 UUID
     * @return 新创建的背包页码，失败返回 -1
     */
    public int createBagPage(UUID playerUuid) {
        // Decided on what is stored now (UltiKits/UltiRemoteBag#54).
        refreshBag(playerUuid);
        
        // One past the highest STORED page; page 1 when nothing is stored (UltiKits/UltiRemoteBag#26).
        List<Integer> existingPages = getPlayerBagPages(playerUuid);
        int nextPage = existingPages.isEmpty() ? 1 : Collections.max(existingPages) + 1;
        
        return addEmptyPage(playerUuid, nextPage) ? nextPage : -1;
    }
    
    /**
     * 删除指定玩家的背包页（管理员操作）
     *
     * @param playerUuid 玩家 UUID
     * @param page       背包页码
     * @return 是否成功
     */
    public boolean deleteBagPage(UUID playerUuid, int page) {
        // Decided on what is stored now (UltiKits/UltiRemoteBag#54).
        refreshBag(playerUuid);
        
        Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
        if (pages == null || !pages.containsKey(page)) {
            return false;
        }
        
        // 从缓存中移除
        pages.remove(page);

        // 从数据库中删除
        List<RemoteBagData> existing = dataOperator.query()
                .where("player_uuid").eq(playerUuid.toString())
                .where("page_number").eq(page)
                .list();

        for (RemoteBagData data : existing) {
            dataOperator.delById(data.getId());
        }

        return true;
    }
    
    /**
     * 清空指定玩家的背包页内容（管理员操作）
     *
     * @param playerUuid 玩家 UUID
     * @param page       背包页码
     * @return 是否成功
     */
    public boolean clearBagPage(UUID playerUuid, int page) {
        // Decided on what is stored now (UltiKits/UltiRemoteBag#54).
        refreshBag(playerUuid);
        
        Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
        if (pages == null || !pages.containsKey(page)) {
            return false;
        }
        
        // Only this page is written, conditioned on the contents it holds at each attempt: a clear empties
        // whatever is stored, but never writes over a row it did not read (UltiKits/UltiRemoteBag#54).
        ItemStack[] emptyContents = new ItemStack[PAGE_CAPACITY];
        String empty = serializeItems(emptyContents);
        for (int attempt = 1; attempt <= MAX_CLEAR_ATTEMPTS; attempt++) {
            List<RemoteBagData> rows = storedRows(playerUuid, page);
            if (rows.isEmpty()) {
                pages.remove(page);
                return false;
            }
            RemoteBagData row = rows.get(0);
            WhereCondition asRead = row.getContents() != null
                    ? WhereCondition.builder().column("contents").value(row.getContents()).build()
                    : WhereCondition.builder().column("last_updated").value(row.getLastUpdated()).build();
            row.setContents(empty);
            row.setLastUpdated(System.currentTimeMillis());
            if (dataOperator.updateIf(row, asRead)) {
                pages.put(page, emptyContents);
                return true;
            }
        }
        plugin.getLogger().error(plugin.i18n("log_bag_update_failed"));
        return false;
    }

    /** At most this many read-and-write attempts for one {@code /bag clear} before it reports failure. */
    private static final int MAX_CLEAR_ATTEMPTS = 3;
}
