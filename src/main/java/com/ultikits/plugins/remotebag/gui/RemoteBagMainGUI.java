package com.ultikits.plugins.remotebag.gui;

import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.BagOpenResult;
import com.ultikits.plugins.remotebag.enums.AccessMode;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.plugins.remotebag.util.SoundUtil;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.gui.BasePaginationPage;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.utils.EconomyUtils;
import mc.obliviate.inventory.Icon;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 远程背包主页 GUI
 * <p>
 * 显示玩家拥有的所有背包缩略图，支持购买新背包。
 * 每个背包显示物品数量和占用槽位信息。
 * <p>
 * Features:
 * <ul>
 *   <li>分页显示玩家的所有背包</li>
 *   <li>每个背包显示名称、物品数量、槽位占用</li>
 *   <li>点击打开对应的背包内容页</li>
 *   <li>支持购买新背包（经济系统集成）</li>
 *   <li>打开/购买时播放音效</li>
 * </ul>
 *
 * @author wisdomme
 * @version 1.0.0
 * @see BasePaginationPage
 * @see RemoteBagService
 */
public class RemoteBagMainGUI extends BasePaginationPage {

    private final UltiToolsPlugin plugin;
    private final RemoteBagService bagService;
    private final BagLockService lockService;
    private final RemoteBagConfig config;
    private final List<Integer> bagPages;

    /**
     * 创建远程背包主页 GUI
     *
     * @param player      玩家
     * @param plugin      插件实例
     * @param bagService  背包服务
     * @param lockService 锁定服务
     * @param config      背包配置
     */
    public RemoteBagMainGUI(@NotNull Player player, UltiToolsPlugin plugin, RemoteBagService bagService,
                            BagLockService lockService, RemoteBagConfig config) {
        super(player, "remotebag-main",
              ChatColor.GOLD + player.getName() + " " + plugin.i18n("gui_main_title"),
              6);
        this.plugin = plugin;
        this.bagService = bagService;
        this.lockService = lockService;
        this.config = config;
        // The owner is offered page 1 even before anything is stored (UltiKits/UltiRemoteBag#26).
        this.bagPages = RemoteBagService.pagesOfferedToOwner(bagService.getPlayerBagPages(player.getUniqueId()));
    }
    
    /**
     * 打开 GUI 后的回调
     * 用于播放打开音效
     *
     * @param event 背包打开事件
     */
    @Override
    protected void afterSetup(InventoryOpenEvent event) {
        // 播放打开音效
        SoundUtil.playOpenSound(player, config);
    }
    
    /**
     * 提供分页内容
     * 包含所有背包图标和购买按钮
     *
     * @return 图标列表
     */
    @Override
    protected List<Icon> provideItems() {
        List<Icon> icons = new ArrayList<>();
        
        // 添加现有背包图标
        for (int pageNum : bagPages) {
            icons.add(createBagIcon(pageNum));
        }
        
        // Below the page limit, offer the next page: bought when the economy is on and a provider is
        // present, free otherwise. The free icon is the only way to reach the service's free path;
        // before it existed, a server with economy off had no way to add a page at all
        // (UltiKits/UltiRemoteBag#25).
        int maxPages = bagService.getPlayerMaxPages(player);
        if (bagPages.size() < maxPages) {
            if (pagesArePaid()) {
                icons.add(createPurchaseIcon());
            } else {
                icons.add(createFreeCreateIcon());
            }
        }
        
        return icons;
    }
    
    /**
     * 创建背包图标
     * <p>
     * 显示背包编号、物品数量和槽位占用信息
     *
     * @param pageNum 背包页码
     * @return 背包图标
     */
    private Icon createBagIcon(int pageNum) {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        
        if (meta != null) {
            // 获取物品统计
            int itemCount = bagService.getItemCount(player.getUniqueId(), pageNum);
            int stackCount = bagService.getStackCount(player.getUniqueId(), pageNum);
            // The real capacity, not a setting. This line used to read config.getRowsPerPage() * 9,
            // which at the shipped default rendered "Slots Used: 45/54" on a page that holds 45 --
            // nine slots a player could never fill (UltiKits/UltiRemoteBag#24).
            int maxSlots = RemoteBagConfig.PAGE_CAPACITY;
            
            meta.setDisplayName(ChatColor.YELLOW + plugin.i18n("bag_name").replace("{0}", String.valueOf(pageNum)));

            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GRAY + plugin.i18n("lore_item_count").replace("{0}", String.valueOf(itemCount)));
            lore.add(ChatColor.GRAY + plugin.i18n("lore_slot_usage").replace("{0}", String.valueOf(stackCount)).replace("{1}", String.valueOf(maxSlots)));
            lore.add("");
            lore.add(ChatColor.GREEN + "▶ " + plugin.i18n("lore_click_open"));
            
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        Icon icon = new Icon(item);
        final int targetPage = pageNum;
        icon.onClick(e -> {
            player.closeInventory();
            
            // 尝试以所有者身份打开背包
            BagOpenResult result = lockService.ownerOpen(player.getUniqueId(), targetPage, player);
            
            if (result.isSuccess()) {
                // 打开背包内容 GUI
                new RemoteBagContentGUI(player, plugin, player.getUniqueId(), targetPage,
                        bagService, lockService, config, result.getAccessMode()).open();
            } else {
                // 被阻止
                SoundUtil.playErrorSound(player, config);
                player.sendMessage(result.renderMessage(plugin));
            }
        });
        
        return icon;
    }
    
    /**
     * 创建购买按钮
     * <p>
     * 根据玩家余额显示不同颜色：
     * - 余额充足：绿色，使用矿车图标
     * - 余额不足：红色，使用屏障图标
     *
     * @return 购买按钮图标
     */
    private Icon createPurchaseIcon() {
        int nextBagNum = bagPages.size() + 1;
        int price = bagService.calculatePrice(nextBagNum);
        double balance = EconomyUtils.getBalance(player);
        boolean canAfford = balance >= price;
        
        Material material = canAfford ? Material.MINECART : Material.BARRIER;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        
        if (meta != null) {
            meta.setDisplayName(canAfford ?
                ChatColor.GREEN + plugin.i18n("purchase_button") :
                ChatColor.RED + plugin.i18n("purchase_button"));

            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GRAY + plugin.i18n("lore_price").replace("{0}", EconomyUtils.format(price)));
            lore.add(ChatColor.GRAY + plugin.i18n("lore_balance").replace("{0}", EconomyUtils.format(balance)));
            lore.add("");
            if (canAfford) {
                lore.add(ChatColor.GREEN + "▶ " + plugin.i18n("lore_click_purchase"));
            } else {
                lore.add(ChatColor.RED + "✖ " + plugin.i18n("lore_insufficient_balance"));
            }
            
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        final int finalPrice = price;
        Icon icon = new Icon(item);
        icon.onClick(e -> {
            if (!pagesArePaid()) {
                refreshAfterPriceChange();
                return;
            }
            if (bagService.purchaseBag(player)) {
                SoundUtil.playPurchaseSound(player, config);
                player.sendMessage(ChatColor.GREEN + plugin.i18n("purchase_success").replace("{0}", String.valueOf(nextBagNum)));
                // 刷新 GUI
                new RemoteBagMainGUI(player, plugin, bagService, lockService, config).open();
            } else {
                SoundUtil.playErrorSound(player, config);
                player.sendMessage(ChatColor.RED + plugin.i18n("purchase_failed").replace("{0}", EconomyUtils.format(finalPrice)));
            }
        });
        
        return icon;
    }
    
    /** Whether a new page costs money right now: the economy is enabled and a provider is present. */
    private boolean pagesArePaid() {
        return config.isEconomyEnabled() && EconomyUtils.isAvailable();
    }

    /**
     * Answers a click on a next-page icon drawn under the other pricing mode -- the economy came on or
     * went off since the window was drawn. The click must not act in a mode the icon did not show (a
     * "free" icon that charges, or a priced one that does not), so it does nothing, says why and redraws.
     */
    private void refreshAfterPriceChange() {
        SoundUtil.playErrorSound(player, config);
        player.sendMessage(ChatColor.YELLOW + plugin.i18n("bag_price_changed"));
        new RemoteBagMainGUI(player, plugin, bagService, lockService, config).open();
    }

    /**
     * The icon that adds the next page for free, shown instead of the purchase icon when the economy
     * is disabled or no economy provider is present (UltiKits/UltiRemoteBag#25).
     * <p>
     * Its click acts only while pages are still free: if the economy came on since the window was
     * drawn (a reload, or a provider registering), the click charges nothing and redraws the window
     * with the price instead. Otherwise it goes through {@link RemoteBagService#purchaseBag}, which
     * refuses past the player's page limit.
     *
     * @return the free create icon
     */
    private Icon createFreeCreateIcon() {
        // The page the free path creates: one past the highest page offered (RemoteBagService#createNewBagPage).
        int nextBagNum = Collections.max(bagPages) + 1;
        ItemStack item = new ItemStack(Material.MINECART);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + plugin.i18n("create_button"));
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GREEN + "▶ " + plugin.i18n("lore_click_create"));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }

        Icon icon = new Icon(item);
        icon.onClick(e -> {
            if (pagesArePaid()) {
                refreshAfterPriceChange();
                return;
            }
            if (bagService.purchaseBag(player)) {
                SoundUtil.playPurchaseSound(player, config);
                player.sendMessage(ChatColor.GREEN + plugin.i18n("create_success").replace("{0}", String.valueOf(nextBagNum)));
                new RemoteBagMainGUI(player, plugin, bagService, lockService, config).open();
            } else {
                SoundUtil.playErrorSound(player, config);
                player.sendMessage(ChatColor.RED + plugin.i18n("create_failed"));
            }
        });

        return icon;
    }

    /**
     * 设置导航按钮
     * <p>
     * 在默认的上一页/下一页按钮基础上添加关闭按钮
     */
    @Override
    protected void setupNavigationButtons() {
        super.setupNavigationButtons(); // 上一页/下一页按钮
        
        // 添加关闭按钮到中间位置
        Icon closeButton = createActionButton(
            Colors.RED,
            ChatColor.RED + plugin.i18n("gui_close"),
            e -> {
                player.closeInventory();
            }
        );
        addToBottomRow(4, closeButton);
    }
}
