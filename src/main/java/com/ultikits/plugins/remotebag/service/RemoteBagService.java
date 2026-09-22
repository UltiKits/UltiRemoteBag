package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.RemoteBagData;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Service;
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

    // Cache for player bags - Map<PlayerUUID, Map<PageNumber, ItemStack[]>>
    private final Map<UUID, Map<Integer, ItemStack[]>> bagCache = new ConcurrentHashMap<>();

    /**
     * Largest page any legal configuration can address, derived from the config key that bounds it
     * rather than restated: {@code rows_per_page} is validated
     * {@code @Range(min = 1, max = }{@link RemoteBagConfig#MAX_ROWS_PER_PAGE}{@code )} and each row is
     * {@link RemoteBagConfig#SLOTS_PER_ROW} slots.
     * <p>
     * It is computed from those two constants, not written as {@code 54}, so the ceiling and the range
     * it comes from cannot drift apart. As a literal in this file it was a hand-copied derivation of a
     * range living in another class with nothing linking them: widening {@code rows_per_page} to allow
     * a double chest would have left every stored index between the old ceiling and the new one legal
     * for the GUI to write and illegal for this loader to read — skipped with a warning and dropped by
     * the next save, which is the item loss UltiKits/UltiRemoteBag#24 was filed for, with a log line
     * instead of an exception. Nothing in the build would have broken to warn about it.
     * <p>
     * A stored slot index is data, and {@link #deserializeItems(String, int)} sizes the page from the
     * highest one it finds, so without this ceiling a corrupt or hand-edited row could turn its own
     * key into an allocation request — {@code items.100000000} asking for an array of hundreds of
     * megabytes, and the resulting {@link OutOfMemoryError} is an {@link Error}, so the
     * {@code catch (Exception)} around the deserializer would not contain it.
     */
    private static final int MAX_PAGE_SLOTS =
            RemoteBagConfig.MAX_ROWS_PER_PAGE * RemoteBagConfig.SLOTS_PER_ROW;

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
     * Set contents of a bag page.
     */
    public void setBagPage(UUID playerUuid, int page, ItemStack[] contents) {
        bagCache.computeIfAbsent(playerUuid, k -> new HashMap<>()).put(page, contents);
    }
    
    /**
     * Save bag to database.
     * <p>
     * Reports whether EVERY cached page reached the database. Two ways it can be false, and a caller
     * that announces a save has to be able to tell both apart from success: nothing is cached at all
     * for a player who has not opened a bag this session, so no row is written; and an update can fail
     * with {@link IllegalAccessException}, which is logged and swallowed here because one bad page
     * must not cost the others. Use {@link #hasCachedPages(UUID)} to distinguish the two.
     *
     * @param playerUuid 玩家 UUID
     * @return true if every cached page was inserted or updated; false if there was nothing to write
     *         or if any page failed
     */
    public boolean saveBag(UUID playerUuid) {
        Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
        if (pages == null || pages.isEmpty()) {
            return false;
        }

        boolean written = true;
        for (Map.Entry<Integer, ItemStack[]> entry : pages.entrySet()) {
            String contents = serializeItems(entry.getValue());

            // Check if exists
            List<RemoteBagData> existing = dataOperator.query()
                    .where("player_uuid").eq(playerUuid.toString())
                    .where("page_number").eq(entry.getKey())
                    .list();

            if (existing.isEmpty()) {
                dataOperator.insert(RemoteBagData.create(playerUuid, entry.getKey(), contents));
            } else {
                RemoteBagData data = existing.get(0);
                data.setContents(contents);
                data.setLastUpdated(System.currentTimeMillis());
                try {
                    dataOperator.update(data);
                } catch (IllegalAccessException e) {
                    plugin.getLogger().error("Failed to update bag data", e);
                    // Keep going -- one unwritable page must not cost the others -- but do not let the
                    // caller report a completed save.
                    written = false;
                }
            }
        }
        return written;
    }

    /**
     * Whether this player has any page in the cache at all.
     * <p>
     * Lets a caller tell {@link #saveBag(UUID)}'s two falses apart: "there was nothing to write" and
     * "a write failed" need different things said to the player, and reporting either as the other is
     * the same defect class as reporting a save that did not happen.
     *
     * @param playerUuid 玩家 UUID
     * @return true if at least one page is cached for this player
     */
    public boolean hasCachedPages(UUID playerUuid) {
        Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
        return pages != null && !pages.isEmpty();
    }
    
    /**
     * Save all bags in cache.
     */
    public void saveAllBags() {
        for (UUID playerUuid : bagCache.keySet()) {
            saveBag(playerUuid);
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
     * exceed {@code rows_per_page * 9}: the content GUI exposes and saves 45 slots whatever
     * {@code rows_per_page} holds, so a server configured below 5 rows stores indices the configured
     * size cannot address. Writing such an index into an array sized from the config used to throw
     * {@link ArrayIndexOutOfBoundsException}, which was caught and turned into an empty page — every
     * item on that page silently destroyed on load (UltiKits/UltiRemoteBag#24).
     * <p>
     * A single unreadable entry — a non-numeric key, a negative index, or an index beyond
     * {@link #MAX_PAGE_SLOTS} — is skipped with a warning naming the page and the key instead of
     * costing the whole page. The ceiling is applied before any array is allocated, so no stored key
     * can size the allocation. Whether a smaller {@code rows_per_page} ought to shrink the displayed
     * page is no longer an open question: the maintainer's 2026-09-22 decision on
     * UltiKits/UltiRemoteBag#24 is that the key governs storage capacity only -- how much fits on a
     * page, not how large the window is -- and that the declaration was corrected rather than the
     * behaviour, so the 45-slot window is deliberate. This method's contract is unchanged and is
     * what makes that safe: loading never loses a stored item, whatever the key holds.
     *
     * @param data       stored YAML, may be null or empty
     * @param pageNumber the page this data belongs to, for the warning messages
     * @return the page's items, indexable for every slot the stored data uses
     */
    private ItemStack[] deserializeItems(String data, int pageNumber) {
        if (data == null || data.isEmpty()) {
            return new ItemStack[config.getRowsPerPage() * 9];
        }

        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(data);

            if (!yaml.isConfigurationSection("items")) {
                return new ItemStack[config.getRowsPerPage() * 9];
            }

            Set<String> keys = yaml.getConfigurationSection("items").getKeys(false);
            Map<Integer, String> slots = new LinkedHashMap<>();
            int highestSlot = -1;
            for (String key : keys) {
                int slot;
                try {
                    slot = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    warnSkippedSlot(pageNumber, key, "not a slot number");
                    continue;
                }
                if (slot < 0) {
                    warnSkippedSlot(pageNumber, key, "negative slot index");
                    continue;
                }
                if (!key.equals(Integer.toString(slot))) {
                    // Integer.parseInt accepts a signed or padded key, so items.'+5', items.'05' and
                    // items.' 5' all parse to 5 and collide with items.'5' on one map entry -- the
                    // second put wins and the first item is gone with no warning at all, the only
                    // path here that discarded an entry silently. Not reachable from serializeItems,
                    // which writes plain decimal indices, so it takes a hand-edited or
                    // foreign-written row to produce.
                    warnSkippedSlot(pageNumber, key, "not a canonical slot number");
                    continue;
                }
                if (slot >= MAX_PAGE_SLOTS) {
                    warnSkippedSlot(pageNumber, key,
                            "slot beyond the largest addressable page of " + MAX_PAGE_SLOTS + " slots");
                    continue;
                }
                slots.put(slot, key);
                highestSlot = Math.max(highestSlot, slot);
            }

            ItemStack[] items = new ItemStack[Math.max(config.getRowsPerPage() * 9, highestSlot + 1)];
            for (Map.Entry<Integer, String> entry : slots.entrySet()) {
                items[entry.getKey()] = yaml.getItemStack("items." + entry.getValue());
            }
            return items;
        } catch (Exception e) {
            // The module's own logger, not an inline java.util.logging one: every other line this
            // module emits carries the framework's [UltiTools] [UltiRemoteBag] prefix, and a checklist
            // row looks for this exact text, so an unprefixed line is a line a tester cannot match.
            plugin.getLogger().warn(e, "Failed to deserialize bag items");
            return new ItemStack[config.getRowsPerPage() * 9];
        }
    }

    private void warnSkippedSlot(int pageNumber, String key, String reason) {
        plugin.getLogger().warn(String.format(
                "Skipping unreadable slot in bag page %d: key '%s' (%s); the rest of the page is kept",
                pageNumber, key, reason));
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
     * 获取玩家拥有的所有背包页码列表
     *
     * @param playerUuid 玩家 UUID
     * @return 背包页码列表（已排序）
     */
    public List<Integer> getPlayerBagPages(UUID playerUuid) {
        loadBagIfNeeded(playerUuid);
        Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
        if (pages == null || pages.isEmpty()) {
            // 如果没有任何背包，返回默认的第一页
            return Collections.singletonList(1);
        }
        return pages.keySet().stream()
                .sorted()
                .collect(Collectors.toList());
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
        if (!config.isEconomyEnabled() || !EconomyUtils.isAvailable()) {
            // 经济系统未启用，直接创建背包
            return createNewBagPage(player);
        }
        
        List<Integer> existingPages = getPlayerBagPages(player.getUniqueId());
        int nextBagNum = existingPages.size() + 1;
        
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
     *
     * @param player 玩家
     * @return 是否成功
     */
    private boolean createNewBagPage(Player player) {
        UUID playerUuid = player.getUniqueId();
        loadBagIfNeeded(playerUuid);
        
        List<Integer> existingPages = getPlayerBagPages(playerUuid);
        int nextPage = existingPages.isEmpty() ? 1 : Collections.max(existingPages) + 1;
        
        // 检查是否超过上限
        int maxPages = getPlayerMaxPages(player);
        if (nextPage > maxPages) {
            return false;
        }
        
        // 创建空的背包页
        ItemStack[] emptyContents = new ItemStack[config.getRowsPerPage() * 9];
        setBagPage(playerUuid, nextPage, emptyContents);

        // 保存到数据库，失败时回滚缓存
        try {
            saveBag(playerUuid);
        } catch (Exception e) {
            Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
            if (pages != null) pages.remove(nextPage);
            throw e;
        }

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
        loadBagIfNeeded(playerUuid);
        
        List<Integer> existingPages = getPlayerBagPages(playerUuid);
        int nextPage = existingPages.isEmpty() || (existingPages.size() == 1 && existingPages.get(0) == 1) 
                ? (existingPages.isEmpty() ? 1 : Collections.max(existingPages) + 1)
                : Collections.max(existingPages) + 1;
        
        // 创建空的背包页
        ItemStack[] emptyContents = new ItemStack[config.getRowsPerPage() * 9];
        setBagPage(playerUuid, nextPage, emptyContents);

        // 保存到数据库，失败时回滚缓存
        try {
            saveBag(playerUuid);
        } catch (Exception e) {
            Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
            if (pages != null) pages.remove(nextPage);
            throw e;
        }

        return nextPage;
    }
    
    /**
     * 删除指定玩家的背包页（管理员操作）
     *
     * @param playerUuid 玩家 UUID
     * @param page       背包页码
     * @return 是否成功
     */
    public boolean deleteBagPage(UUID playerUuid, int page) {
        loadBagIfNeeded(playerUuid);
        
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
        loadBagIfNeeded(playerUuid);
        
        Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
        if (pages == null || !pages.containsKey(page)) {
            return false;
        }
        
        // 创建空的内容
        ItemStack[] emptyContents = new ItemStack[config.getRowsPerPage() * 9];
        ItemStack[] oldContents = pages.get(page);
        setBagPage(playerUuid, page, emptyContents);

        // 保存到数据库，失败时回滚缓存
        try {
            saveBag(playerUuid);
        } catch (Exception e) {
            if (oldContents != null) {
                setBagPage(playerUuid, page, oldContents);
            }
            throw e;
        }

        return true;
    }
}
