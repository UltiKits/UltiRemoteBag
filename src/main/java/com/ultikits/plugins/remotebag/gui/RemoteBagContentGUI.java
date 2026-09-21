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
import mc.obliviate.inventory.Gui;
import mc.obliviate.inventory.Icon;
import mc.obliviate.inventory.InventoryAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.InventoryView;
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
            // Only report a save that happened: saveCurrentContents() refuses, with its own message,
            // when this page no longer holds edit authority.
            if (saveCurrentContents()) {
                SoundUtil.playCloseSound(player, config);
                player.sendMessage(ChatColor.GREEN + plugin.i18n("msg_bag_saved"));
            }
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
     * refused, including in edit mode.
     * <p>
     * The refusal is scoped to THIS page's window, mirroring {@link #onClick}: read-only guards the
     * bag, not the viewer's own inventory, so an administrator looking at somebody else's bag can
     * still right-drag a stack among slots of their own inventory. Refusing the whole event on mode
     * alone took that away, and took it away silently.
     * <p>
     * Every refusal this method performs says so. There is no other feedback for a cancelled drag —
     * no icon action runs, nothing moves — so a silent refusal is indistinguishable from a broken
     * build, which is exactly the reading that let UltiKits/UltiRemoteBag#27 sit open.
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
        for (int rawSlot : event.getRawSlots()) {
            boolean insideBagWindow = rawSlot >= 0 && rawSlot < getSize();
            if (!insideBagWindow) {
                // The viewer's own inventory is theirs in either mode.
                continue;
            }

            if (accessMode == AccessMode.READ_ONLY) {
                // In read-only the whole window is guarded, toolbar included, and "read-only" is the
                // reason for all of it.
                announceDragRefusal("msg_readonly_no_move");
                return CANCEL;
            }

            if (rawSlot >= CONTENT_SIZE) {
                announceDragRefusal("msg_cannot_drag_toolbar");
                return CANCEL;
            }
        }

        return ALLOW;
    }

    /**
     * Tells the viewer why a drag was refused, and plays the error sound.
     *
     * @param messageKey the i18n key naming the reason
     */
    private void announceDragRefusal(String messageKey) {
        SoundUtil.playErrorSound(player, config);
        player.sendMessage(ChatColor.RED + plugin.i18n(messageKey));
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
     * 将该玩家当前打开的编辑模式内容页写入背包服务并持久化
     * <p>
     * Persists the content page {@code viewer} currently has open, if it is one of these pages and
     * it is in edit mode. Returns whether anything was written, so a caller can tell "there was
     * nothing open" from "the open page was flushed".
     * <p>
     * The open page is resolved through {@link InventoryAPI#getPlayersCurrentGui(Player)} — the same
     * player-to-page map the GUI library's listener consults to decide which page receives a click,
     * so this cannot disagree with what the player is actually looking at.
     * <p>
     * The page must also be a page of the sender's OWN bag. {@code /bag save} persists the sender's
     * pages, and an admin viewing someone else's bag through {@code /bag see} holds a page whose
     * viewer is the admin while its owner is the target — so a viewer-identity check would be
     * tautologically true there and the command would write the target's data. The comparison is
     * therefore against {@link #ownerUuid}. An admin's own edits to someone else's page are still
     * saved by that page's Save button and by closing it, which is where that write belongs.
     * <p>
     * A read-only page is deliberately skipped: its live inventory is another player's bag being
     * looked at, and writing it back would let a viewer's stale view overwrite the owner's page.
     *
     * @param viewer 玩家 / the player whose open page should be flushed
     * @return what happened, so the caller can report a save only when one occurred
     */
    public static FlushOutcome flushOpenEditPage(Player viewer) {
        if (viewer == null || InventoryAPI.getInstance() == null) {
            return FlushOutcome.NO_OPEN_PAGE;
        }

        Gui current = InventoryAPI.getInstance().getPlayersCurrentGui(viewer);
        if (!(current instanceof RemoteBagContentGUI)) {
            return FlushOutcome.NO_OPEN_PAGE;
        }

        RemoteBagContentGUI page = (RemoteBagContentGUI) current;
        if (page.accessMode != AccessMode.EDIT
                || !viewer.getUniqueId().equals(page.ownerUuid)) {
            return FlushOutcome.NO_OPEN_PAGE;
        }

        return page.saveCurrentContents() ? FlushOutcome.WRITTEN : FlushOutcome.REFUSED;
    }

    /**
     * What {@link #flushOpenEditPage(Player)} did.
     * <p>
     * Three outcomes rather than a boolean, because a caller that reports "saved" has to distinguish
     * "there was nothing open, so persist the cache instead" from "there was an open page and it
     * refused to write" — the second must report nothing, since the page has already told the viewer
     * why.
     */
    public enum FlushOutcome {
        /** No flushable page was open; the caller should persist the cache itself. */
        NO_OPEN_PAGE,
        /** An open edit page was written, which also persisted the rest of that player's cache. */
        WRITTEN,
        /** An open edit page declined to write because it no longer holds edit authority. */
        REFUSED
    }

    /**
     * Whether {@code holderUuid} is, right now, looking at this bag page.
     * <p>
     * This is what {@link BagLockService} asks before letting a lock's timeout reclaim it: the
     * timeout exists to free a lock whose holder's session ended without releasing it, and a holder
     * who is online with the page open has not ended their session.
     * <p>
     * Presence is READ, never stored. There is no "page is open" flag anywhere, because a flag can
     * leak — on a crash, on a reload, on a force-close by another plugin, on a server-side inventory
     * replacement — and a leaked flag would make a lock immortal, which is worse than the lost update
     * it was added to prevent. Both facts below are live:
     * <ul>
     *   <li>{@link InventoryAPI#getPlayersCurrentGui(Player)} is the library's own player-to-page
     *       registry, written by {@code Gui#open()} and removed by its listener on
     *       {@link InventoryCloseEvent}. Every way a page stops being open fires that event —
     *       quitting, Escape, another plugin's {@code closeInventory()}, opening any other
     *       inventory — so a closed page cannot still be registered.</li>
     *   <li>The holder's live {@link org.bukkit.inventory.InventoryView} must still be showing this
     *       page's inventory. After a crash or a reload there is no view at all, so the lock expires
     *       under the ordinary rule with no special case and no separate backstop.</li>
     * </ul>
     * The comparison is {@code equals}, not {@code ==}, deliberately:
     * {@code org.bukkit.craftbukkit.inventory.CraftInventory#equals} compares the underlying
     * {@code net.minecraft.world.Container}, and a view's top inventory can be a different wrapper
     * around that same container, so reference identity would be a false negative on a real server
     * (measured from {@code paper-1.21.11.jar}) and would leave this check silently inert.
     *
     * @param ownerUuid  the bag's owner
     * @param pageNum    the page number
     * @param holderUuid the lock holder to look for
     * @return true if that player is online and has this very page open
     */
    public static boolean isPageOpenBy(UUID ownerUuid, int pageNum, UUID holderUuid) {
        if (ownerUuid == null || holderUuid == null || InventoryAPI.getInstance() == null) {
            return false;
        }

        // No server means nobody is online, so no page is open. Reached during early boot and during
        // shutdown, when Bukkit's static server reference is not (or no longer) set.
        if (Bukkit.getServer() == null) {
            return false;
        }

        Player holder = Bukkit.getPlayer(holderUuid);
        if (holder == null || !holder.isOnline()) {
            return false;
        }

        Gui current = InventoryAPI.getInstance().getPlayersCurrentGui(holder);
        if (!(current instanceof RemoteBagContentGUI)) {
            return false;
        }

        RemoteBagContentGUI page = (RemoteBagContentGUI) current;
        if (page.pageNum != pageNum || !ownerUuid.equals(page.ownerUuid)) {
            return false;
        }

        InventoryView view = holder.getOpenInventory();
        return view != null && page.getInventory().equals(view.getTopInventory());
    }

    /**
     * 保存当前 GUI 中的内容到背包服务
     * <p>
     * Copies this page's live inventory into the service cache and persists it.
     * <p>
     * Defence in depth, explicitly secondary: the primary protection against a stale page
     * overwriting a newer one is that a lock can no longer expire while its page is open
     * ({@link #isPageOpenBy}), so two pages of the same bag can no longer be open in edit mode at
     * once. This guard exists in case some path still gets there, and it asks the LIVE lock rather
     * than the {@code accessMode} this page was constructed with.
     * <p>
     * {@link BagLockService#mayWrite} answers true when no lock is held at all, which is the ordinary
     * benign case — the lock expired and nobody took it — and that case must keep saving. A guard
     * that refused there would silently stop persisting every normal session.
     *
     * @return true if the contents were written
     */
    private boolean saveCurrentContents() {
        if (!lockService.mayWrite(ownerUuid, pageNum, player.getUniqueId())) {
            // Somebody else holds this page now. Writing would overwrite their committed edits with
            // a snapshot taken before they existed. Refusing silently would trade their loss for
            // this viewer's, so say so.
            SoundUtil.playErrorSound(player, config);
            player.sendMessage(ChatColor.RED + plugin.i18n("msg_save_refused_lock_taken"));
            return false;
        }

        ItemStack[] contents = new ItemStack[CONTENT_SIZE];
        for (int i = 0; i < CONTENT_SIZE; i++) {
            contents[i] = getInventory().getItem(i);
        }
        bagService.setBagPage(ownerUuid, pageNum, contents);
        bagService.saveBag(ownerUuid);
        return true;
    }
}
