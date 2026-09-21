package com.ultikits.plugins.remotebag.gui;

import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.BagOpenResult;
import com.ultikits.plugins.remotebag.enums.AccessMode;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.plugins.remotebag.util.SoundUtil;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.gui.BaseInventoryPage;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.utils.XVersionUtils;
import mc.obliviate.inventory.Icon;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 远程背包内容 GUI
 * <p>
 * 显示和编辑背包的具体内容，支持编辑模式和只读模式。
 * <p>
 * Features:
 * <ul>
 *   <li>编辑模式：允许移动物品、保存</li>
 *   <li>只读模式：禁止移动物品（点击与拖拽均取消）、显示刷新按钮</li>
 *   <li>工具栏：返回、刷新、保存、模式指示、关闭按钮</li>
 *   <li>关闭时自动保存（编辑模式）并释放锁</li>
 * </ul>
 *
 * @author wisdomme
 * @version 1.0.0
 * @see BaseInventoryPage
 * @see RemoteBagService
 * @see BagLockService
 */
public class RemoteBagContentGUI extends BaseInventoryPage {

    private final UltiToolsPlugin plugin;
    private final RemoteBagService bagService;
    private final BagLockService lockService;
    private final RemoteBagConfig config;
    private final UUID ownerUuid;
    private final int pageNum;
    private final AccessMode accessMode;
    
    /**
     * 内容区域槽位数（前 5 行 = 45 槽）
     */
    private static final int CONTENT_SIZE = 45;

    /**
     * Return value of {@link #onClick}/{@link #onDrag} that REFUSES the interaction.
     * <p>
     * Spelled out as a named constant because the polarity is the opposite of what this class's
     * javadoc claimed until UltiKits/UltiRemoteBag#27, and a bare {@code return false} reads as
     * "no, do not cancel" to anyone who has not read the library.
     * <p>
     * Measured from {@code obliviate-invs} 4.3.0 bytecode ({@code core-4.3.0.jar},
     * {@code mc.obliviate.inventory.InvListener}), which is the only code that turns this value
     * into a cancel decision:
     * <ul>
     *   <li>{@code onClick}: {@code true} → {@code event.setCancelled(false)} — the click is
     *       ALLOWED. {@code false} → cancelled whenever {@code getSlot() == getRawSlot()} (every
     *       click on this page's own window), and, for a click in the player's own inventory,
     *       cancelled for {@code MOVE_TO_OTHER_INVENTORY}, {@code COLLECT_TO_CURSOR} and
     *       {@code UNKNOWN} only.</li>
     *   <li>{@code onDrag}: {@code event.setCancelled(!onDrag(event))}, unconditionally, with no
     *       per-slot fallback.</li>
     *   <li>{@code Gui}'s own defaults return {@code false} for both, i.e. the library's
     *       out-of-the-box behaviour is a locked page.</li>
     * </ul>
     * The Icon click action registered for the clicked slot is dispatched after {@code onClick}
     * returns either way, so cancelling does not disable a button.
     */
    private static final boolean CANCEL = false;

    /**
     * Return value of {@link #onClick}/{@link #onDrag} that PERMITS the interaction. See
     * {@link #CANCEL} for the measured library contract.
     */
    private static final boolean ALLOW = true;

    /**
     * 创建远程背包内容 GUI
     *
     * @param viewer      查看者玩家
     * @param plugin      插件实例
     * @param ownerUuid   背包所有者 UUID
     * @param pageNum     背包页码
     * @param bagService  背包服务
     * @param lockService 锁定服务
     * @param config      配置
     * @param accessMode  访问模式
     */
    public RemoteBagContentGUI(@NotNull Player viewer,
                               UltiToolsPlugin plugin,
                               UUID ownerUuid,
                               int pageNum,
                               RemoteBagService bagService,
                               BagLockService lockService,
                               RemoteBagConfig config,
                               AccessMode accessMode) {
        super(viewer, "remotebag-content-" + pageNum, buildTitle(plugin, pageNum, accessMode), 6);
        this.plugin = plugin;
        this.ownerUuid = ownerUuid;
        this.pageNum = pageNum;
        this.bagService = bagService;
        this.lockService = lockService;
        this.config = config;
        this.accessMode = accessMode;
    }

    /**
     * 构建 GUI 标题
     *
     * @param plugin  插件实例
     * @param pageNum 页码
     * @param mode    访问模式
     * @return 格式化的标题
     */
    private static String buildTitle(UltiToolsPlugin plugin, int pageNum, AccessMode mode) {
        String base = plugin.i18n("bag_name").replace("{0}", String.valueOf(pageNum));
        if (mode == AccessMode.READ_ONLY) {
            return ChatColor.GRAY + "[" + plugin.i18n("read_only") + "] " + ChatColor.GOLD + base;
        }
        return ChatColor.GOLD + base;
    }
    
    /**
     * 设置 GUI 内容
     *
     * @param event 背包打开事件
     */
    @Override
    protected void setupContent(InventoryOpenEvent event) {
        // 加载背包内容到内容区域
        loadBagContents();
        
        // 设置工具栏
        setupToolbar();
    }
    
    /**
     * GUI 设置完成后的回调
     *
     * @param event 背包打开事件
     */
    @Override
    protected void afterSetup(InventoryOpenEvent event) {
        SoundUtil.playOpenSound(player, config);
    }
    
    /**
     * 加载背包内容到 GUI
     */
    private void loadBagContents() {
        // 确保背包数据已加载
        bagService.loadBagIfNeeded(ownerUuid);
        
        ItemStack[] contents = bagService.getBagPage(ownerUuid, pageNum);
        if (contents != null) {
            for (int i = 0; i < Math.min(contents.length, CONTENT_SIZE); i++) {
                if (contents[i] != null) {
                    // 物品直接放入，不设置 Icon 点击事件
                    // 编辑模式下允许自由移动，只读模式在 onClick 中处理
                    getInventory().setItem(i, contents[i]);
                }
            }
        }
    }
    
    /**
     * 设置工具栏按钮
     */
    private void setupToolbar() {
        // 清空底部工具栏
        // addToBottomRow 会自动放到最后一行
        
        // 返回按钮 (槽位 0)
        addToBottomRow(0, createBackButton());
        
        // 分隔 (槽位 1-2)
        addToBottomRow(1, createBackgroundIcon());
        addToBottomRow(2, createBackgroundIcon());
        
        // 刷新按钮 (槽位 3) - 仅只读模式显示实际按钮
        if (accessMode == AccessMode.READ_ONLY) {
            addToBottomRow(3, createRefreshButton());
        } else {
            addToBottomRow(3, createBackgroundIcon());
        }
        
        // 保存按钮 (槽位 4)
        if (accessMode == AccessMode.EDIT) {
            addToBottomRow(4, createSaveButton());
        } else {
            addToBottomRow(4, createDisabledSaveButton());
        }
        
        // 模式指示器 (槽位 5)
        addToBottomRow(5, createModeIndicator());
        
        // 分隔 (槽位 6-7)
        addToBottomRow(6, createBackgroundIcon());
        addToBottomRow(7, createBackgroundIcon());
        
        // 关闭按钮 (槽位 8)
        addToBottomRow(8, createCloseButton());
    }
    
    /**
     * 创建返回按钮
     *
     * @return 返回按钮 Icon
     */
    private Icon createBackButton() {
        Icon icon = createActionButton(Colors.YELLOW, ChatColor.YELLOW + plugin.i18n("btn_back"), e -> {
            SoundUtil.playPageSound(player, config);
            // 先关闭当前 GUI（会触发 onClose 保存）
            player.closeInventory();
            // 返回主页
            new RemoteBagMainGUI(player, plugin, bagService, lockService, config).open();
        });
        
        // 设置 lore
        ItemStack item = icon.getItem();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GRAY + plugin.i18n("lore_back_to_main"));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        return icon;
    }
    
    /**
     * 创建刷新按钮（只读模式专用）
     *
     * @return 刷新按钮 Icon
     */
    private Icon createRefreshButton() {
        ItemStack item = new ItemStack(Material.SUNFLOWER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + plugin.i18n("btn_refresh"));
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GRAY + plugin.i18n("lore_refresh_hint1"));
            lore.add(ChatColor.GRAY + plugin.i18n("lore_refresh_hint2"));
            lore.add(ChatColor.GRAY + plugin.i18n("lore_refresh_hint3"));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        Icon icon = new Icon(item);
        icon.onClick(e -> {
            // 检查是否可以升级为编辑模式
            if (lockService.canUpgradeToEdit(ownerUuid, pageNum)) {
                player.sendMessage(ChatColor.GREEN + plugin.i18n("msg_upgrading_to_edit"));
                player.closeInventory();
                
                // 重新以编辑模式打开
                BagOpenResult result = lockService.adminOpen(ownerUuid, pageNum, player);
                if (result.isEditMode()) {
                    new RemoteBagContentGUI(player, plugin, ownerUuid, pageNum,
                            bagService, lockService, config, AccessMode.EDIT).open();
                } else {
                    // 如果还是无法获取编辑权限，以只读模式重新打开
                    new RemoteBagContentGUI(player, plugin, ownerUuid, pageNum,
                            bagService, lockService, config, AccessMode.READ_ONLY).open();
                }
            } else {
                SoundUtil.playErrorSound(player, config);
                player.sendMessage(ChatColor.YELLOW + plugin.i18n("msg_owner_still_using"));
                // 刷新内容显示
                loadBagContents();
            }
        });
        
        return icon;
    }
    
    /**
     * 创建保存按钮（编辑模式）
     *
     * @return 保存按钮 Icon
     */
    private Icon createSaveButton() {
        Icon icon = createActionButton(Colors.GREEN, ChatColor.GREEN + plugin.i18n("btn_save"), e -> {
            saveCurrentContents();
            SoundUtil.playCloseSound(player, config);
            player.sendMessage(ChatColor.GREEN + plugin.i18n("msg_bag_saved"));
        });
        
        // 设置 lore
        ItemStack item = icon.getItem();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GRAY + plugin.i18n("lore_save_hint"));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        return icon;
    }
    
    /**
     * 创建禁用的保存按钮（只读模式）
     *
     * @return 禁用的保存按钮 Icon
     */
    private Icon createDisabledSaveButton() {
        ItemStack item = XVersionUtils.getColoredPlaneGlass(Colors.RED);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + plugin.i18n("btn_save_disabled"));
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GRAY + plugin.i18n("lore_readonly_hint1"));
            lore.add(ChatColor.GRAY + plugin.i18n("lore_readonly_hint2"));
            lore.add("");
            lore.add(ChatColor.YELLOW + plugin.i18n("lore_readonly_hint3"));
            lore.add(ChatColor.YELLOW + plugin.i18n("lore_readonly_hint4"));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        Icon icon = new Icon(item);
        icon.onClick(e -> {
            SoundUtil.playErrorSound(player, config);
            player.sendMessage(ChatColor.RED + plugin.i18n("msg_cannot_save_readonly"));
        });
        
        return icon;
    }
    
    /**
     * 创建模式指示器
     *
     * @return 模式指示器 Icon
     */
    private Icon createModeIndicator() {
        Colors color;
        String name;
        List<String> lore = new ArrayList<>();
        
        if (accessMode == AccessMode.EDIT) {
            color = Colors.GREEN;
            name = ChatColor.GREEN + plugin.i18n("mode_edit");
            lore.add("");
            lore.add(ChatColor.GRAY + plugin.i18n("lore_edit_mode"));
        } else {
            color = Colors.YELLOW;
            name = ChatColor.YELLOW + plugin.i18n("mode_readonly");
            lore.add("");
            lore.add(ChatColor.GRAY + plugin.i18n("lore_readonly_mode1"));
            lore.add(ChatColor.GRAY + plugin.i18n("lore_readonly_mode2"));
        }
        
        ItemStack item = XVersionUtils.getColoredPlaneGlass(color);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        return new Icon(item);
    }
    
    /**
     * 创建关闭按钮
     *
     * @return 关闭按钮 Icon
     */
    private Icon createCloseButton() {
        Icon icon = createActionButton(Colors.RED, ChatColor.RED + plugin.i18n("btn_close"), e -> {
            player.closeInventory();
        });
        
        // 设置 lore
        ItemStack item = icon.getItem();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add("");
            if (accessMode == AccessMode.EDIT) {
                lore.add(ChatColor.GRAY + plugin.i18n("lore_close_save"));
            } else {
                lore.add(ChatColor.GRAY + plugin.i18n("lore_close_discard"));
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        return icon;
    }
    
    /**
     * 处理物品点击事件
     * <p>
     * Handles an inventory click.
     *
     * @param event 点击事件
     * @return {@link #ALLOW} to let the click through, {@link #CANCEL} to refuse it
     */
    @Override
    public boolean onClick(InventoryClickEvent event) {
        int rawSlot = event.getRawSlot();
        boolean insideBagWindow = rawSlot >= 0 && rawSlot < getSize();

        // Toolbar row: buttons, not storage, so it is never movable in either mode. This is what
        // stops the disabled Save icon being picked up onto the cursor. The library dispatches the
        // Icon's own click action after this method returns, regardless of the cancel decision, so
        // every toolbar button keeps working.
        if (insideBagWindow && rawSlot >= CONTENT_SIZE) {
            return CANCEL;
        }

        if (accessMode == AccessMode.READ_ONLY) {
            if (isRefusalWorthAnnouncing(event, insideBagWindow)) {
                SoundUtil.playErrorSound(player, config);
                player.sendMessage(ChatColor.RED + plugin.i18n("msg_readonly_no_move"));
            }
            return CANCEL;
        }

        // Edit mode: the content area and the viewer's own inventory behave like a chest.
        return ALLOW;
    }

    /**
     * 处理拖拽事件
     * <p>
     * Handles an inventory drag. A drag is not a click and reaches this class through a separate
     * library entry point, which this page previously did not override at all — leaving every drag
     * refused, including in edit mode. Read-only drags stay refused; edit-mode drags are allowed
     * unless they reach into the toolbar row, which would overwrite a button with an item.
     * <p>
     * Unlike a click, the library applies this method's answer to the whole drag unconditionally:
     * there is no per-slot fallback, so a drag spanning the content area and the toolbar has to be
     * refused outright rather than partially applied.
     *
     * @param event 拖拽事件
     * @return {@link #ALLOW} to let the drag through, {@link #CANCEL} to refuse it
     */
    @Override
    public boolean onDrag(InventoryDragEvent event) {
        if (accessMode == AccessMode.READ_ONLY) {
            return CANCEL;
        }

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= CONTENT_SIZE && rawSlot < getSize()) {
                return CANCEL;
            }
        }

        return ALLOW;
    }

    /**
     * Whether a refused read-only interaction should tell the viewer why.
     * <p>
     * True for any attempt to move an item that targets this page's own window, and for a
     * shift-click from the viewer's own inventory (the one obvious gesture for pushing an item into
     * the bag from the other side). Deliberately false for the remaining player-side actions: the
     * library refuses only some of those, and a "cannot move items" line on a move that actually
     * succeeded would be a worse defect than silence.
     *
     * @param event           the click being refused
     * @param insideBagWindow whether the clicked raw slot belongs to this page's own inventory
     * @return true if a refusal message and sound should be sent
     */
    private boolean isRefusalWorthAnnouncing(InventoryClickEvent event, boolean insideBagWindow) {
        if (!isItemMovementAttempt(event)) {
            return false;
        }
        return insideBagWindow
                || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY;
    }

    /**
     * Whether a click actually carries an item, either in the clicked slot or on the cursor.
     * <p>
     * Both getters return an AIR stack rather than {@code null} for an empty slot or an empty
     * cursor on a live server, so a plain null check (which this class used to do) is true for
     * essentially every click, including a click on a background pane.
     *
     * @param event the click to inspect
     * @return true if an item is involved
     */
    private boolean isItemMovementAttempt(InventoryClickEvent event) {
        return isRealItem(event.getCurrentItem()) || isRealItem(event.getCursor());
    }

    private boolean isRealItem(ItemStack item) {
        return item != null && item.getType() != Material.AIR;
    }

    /**
     * 处理 GUI 关闭事件
     *
     * @param event 关闭事件
     */
    @Override
    public void onClose(InventoryCloseEvent event) {
        if (accessMode == AccessMode.EDIT) {
            // 编辑模式 - 保存并释放锁
            saveCurrentContents();
            lockService.release(ownerUuid, pageNum, player.getUniqueId());
            SoundUtil.playCloseSound(player, config);
        } else {
            // 只读模式 - 仅释放只读会话
            lockService.release(ownerUuid, pageNum, player.getUniqueId());
        }
    }
    
    /**
     * 保存当前 GUI 中的内容到背包服务
     */
    private void saveCurrentContents() {
        ItemStack[] contents = new ItemStack[CONTENT_SIZE];
        for (int i = 0; i < CONTENT_SIZE; i++) {
            contents[i] = getInventory().getItem(i);
        }
        bagService.setBagPage(ownerUuid, pageNum, contents);
        bagService.saveBag(ownerUuid);
    }
}
