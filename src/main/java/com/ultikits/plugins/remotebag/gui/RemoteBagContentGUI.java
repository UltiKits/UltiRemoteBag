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
import org.bukkit.event.inventory.ClickType;
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
import java.util.Map;
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
     * What this window read of its page when it opened (or last refreshed), or what its last save wrote:
     * its next save is written only if the stored page is still this, so a stale window can never
     * overwrite a page another server changed (UltiKits/UltiRemoteBag#54).
     */
    private RemoteBagService.PageRead pageRead;

    /**
     * Set when this window's edit claim was lost (UltiKits/UltiRemoteBag#54): from then on the window behaves
     * read-only and writes nothing.
     */
    private boolean editRightsLost;
    
    /**
     * 内容区域槽位数（前 5 行 = 45 槽）
     * <p>
     * Read from {@link RemoteBagConfig#PAGE_CAPACITY} rather than written as {@code 45}, so the
     * window's size, the array the service allocates for a page, and the denominator of the main
     * GUI's "Slots Used" lore are one number. They were three derivations of two different things
     * until UltiKits/UltiRemoteBag#24, and they disagreed on screen.
     */
    private static final int CONTENT_SIZE = RemoteBagConfig.PAGE_CAPACITY;

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
     * <p>
     * Writes EVERY content slot, including the empty ones. This method is also the read-only Refresh
     * button's whole implementation, and writing only the non-null entries left a refreshed view
     * showing the union of what was displayed before and what is stored now — so a viewer whose whole
     * purpose is to see the current state saw items the owner had already taken out. With
     * UltiKits/UltiRemoteBag#27 fixed the viewer can no longer act on those phantom items, but an
     * administrator can still act on the wrong picture.
     */
    private void loadBagContents() {
        // Read from the database, not from the cache: this is what the window's save is conditioned on
        // (UltiKits/UltiRemoteBag#54).
        pageRead = bagService.readPage(ownerUuid, pageNum);

        ItemStack[] contents = pageRead == null ? null : pageRead.getItems();
        for (int i = 0; i < CONTENT_SIZE; i++) {
            // 物品直接放入，不设置 Icon 点击事件
            // 编辑模式下允许自由移动，只读模式在 onClick 中处理
            boolean stored = contents != null && i < contents.length && contents[i] != null;
            getInventory().setItem(i, stored ? contents[i] : null);
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
            if (lockService.isClaimedElsewhere(ownerUuid, pageNum)) {
                // Still being edited on another server (UltiKits/UltiRemoteBag#54): stay read-only, say why,
                // and show the page as stored now.
                SoundUtil.playErrorSound(player, config);
                player.sendMessage(BagOpenResult.readOnlyElsewhere().renderMessage(plugin));
                loadBagContents();
                return;
            }
            // 检查是否可以升级为编辑模式
            if (lockService.canUpgradeToEdit(ownerUuid, pageNum)) {
                player.sendMessage(ChatColor.GREEN + plugin.i18n("msg_upgrading_to_edit"));
                player.closeInventory();
                
                // 重新以编辑模式打开 -- as the owner when it is the viewer's own bag, which since
                // UltiKits/UltiRemoteBag#54 can be open read-only because another server was editing it.
                BagOpenResult result = player.getUniqueId().equals(ownerUuid)
                        ? lockService.ownerOpen(ownerUuid, pageNum, player)
                        : lockService.adminOpen(ownerUuid, pageNum, player);
                if (result.isEditMode()) {
                    new RemoteBagContentGUI(player, plugin, ownerUuid, pageNum,
                            bagService, lockService, config, AccessMode.EDIT).open();
                } else {
                    // 如果还是无法获取编辑权限，以只读模式重新打开
                    if (result.getNotice() != null) {
                        player.sendMessage(result.renderMessage(plugin));
                    }
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

        if (!isEditable()) {
            if (isRefusalWorthAnnouncing(event, insideBagWindow)) {
                SoundUtil.playErrorSound(player, config);
                player.sendMessage(ChatColor.RED + plugin.i18n("msg_readonly_no_move"));
            }
            return CANCEL;
        }

        // Two vanilla actions are not confined to the slot they were clicked on: COLLECT_TO_CURSOR
        // sweeps every slot of both inventories, and a shift-click from the player's side scans the
        // whole top inventory for somewhere to put the stack. Both therefore reach raw slots 45-53,
        // which the guard above cannot see because it only knows where the click STARTED.
        if (wouldReachAToolbarIcon(event)) {
            SoundUtil.playErrorSound(player, config);
            player.sendMessage(ChatColor.RED + plugin.i18n("msg_toolbar_item_conflict"));
            return CANCEL;
        }

        // Edit mode: the content area and the viewer's own inventory behave like a chest -- but only
        // as far as nobody else has already said no. The library turns ALLOW into
        // event.setCancelled(false), which CLEARS a cancellation an earlier handler set rather than
        // merely declining to add one, and its own listener is a bare @EventHandler (NORMAL,
        // ignoreCancelled = false). Returning ALLOW unconditionally therefore overrode an anti-cheat
        // or region plugin at LOWEST/LOW/earlier-NORMAL. Deciding for this page is ours; reversing
        // somebody else's decision is not.
        return event.isCancelled() ? CANCEL : ALLOW;
    }

    /**
     * Whether this click is one of the two multi-slot actions AND carries an item that matches a
     * toolbar icon, so vanilla would reach into the toolbar row while applying it.
     * <p>
     * The library's contract is one boolean for the whole click, so a multi-slot action cannot be
     * allowed "except for slots 45-53" — it is allowed entirely or refused entirely. Refusing it is
     * the safe half, and it only bites when the player is holding something that matches a button.
     * <p>
     * The exposure is real rather than theoretical for this module in particular: before
     * UltiKits/UltiRemoteBag#27 the toolbar icons COULD be picked up out of the page, so a server
     * upgrading from that version may have players holding genuine copies of them. With a matching
     * item on the cursor, a double-click anywhere in the window collects the icon out of the toolbar;
     * the toolbar is rebuilt from scratch by {@code setupToolbar} on the next open and
     * {@link #saveCurrentContents} only ever serialises slots 0-44, so the collected copy is pure
     * duplication. The shift-click direction loses instead: the stack merges into a toolbar slot and
     * is gone when the page closes.
     * <p>
     * {@link ItemStack#isSimilar} is the Bukkit equivalent of the comparison vanilla uses to decide
     * both of these (type plus metadata, ignoring stack size), so this matches what the server would
     * actually do rather than approximating it.
     *
     * @param event the click being considered
     * @return true if the action would let vanilla touch a toolbar icon
     */
    private boolean wouldReachAToolbarIcon(InventoryClickEvent event) {
        ItemStack subject;
        switch (event.getAction()) {
            case COLLECT_TO_CURSOR:
                subject = isRealItem(event.getCursor()) ? event.getCursor() : event.getCurrentItem();
                break;
            case MOVE_TO_OTHER_INVENTORY:
                if (event.getRawSlot() >= 0 && event.getRawSlot() < getSize()) {
                    // Shift-clicking OUT of this window moves into the player's inventory, which has
                    // no toolbar to reach.
                    return false;
                }
                subject = event.getCurrentItem();
                break;
            default:
                return false;
        }

        if (!isRealItem(subject)) {
            return false;
        }

        for (int slot = CONTENT_SIZE; slot < getSize(); slot++) {
            ItemStack icon = getInventory().getItem(slot);
            if (icon != null && icon.isSimilar(subject)) {
                return true;
            }
        }
        return false;
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

            if (!isEditable()) {
                // In read-only the whole window is guarded, toolbar included, and "read-only" is the
                // reason for all of it.
                announceDragRefusal(plugin.i18n("msg_readonly_no_move"));
                return CANCEL;
            }

            if (rawSlot >= CONTENT_SIZE) {
                announceDragRefusal(plugin.i18n("msg_cannot_drag_toolbar"));
                return CANCEL;
            }
        }

        // Same reasoning as onClick's edit branch: the library applies setCancelled(!onDrag(...))
        // unconditionally, so answering ALLOW would clear another plugin's cancellation.
        return event.isCancelled() ? CANCEL : ALLOW;
    }

    /**
     * Tells the viewer why a drag was refused, and plays the error sound.
     *
     * @param reason the reason, already resolved from the language file by the caller, so every
     *               key this class displays is a literal at its call site
     */
    private void announceDragRefusal(String reason) {
        SoundUtil.playErrorSound(player, config);
        player.sendMessage(ChatColor.RED + reason);
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
     * Whether a click actually carries an item: in the clicked slot, on the cursor, or -- for a
     * number-key or off-hand swap -- in the viewer's own hotbar or off-hand slot the key names.
     * <p>
     * Both getters return an AIR stack rather than {@code null} for an empty slot or an empty
     * cursor on a live server, so a plain null check (which this class used to do) is true for
     * essentially every click, including a click on a background pane. A swap over an empty
     * content slot moves the viewer's hotbar or off-hand item, which neither getter sees, so it
     * used to be refused silently (UltiKits/UltiRemoteBag#35).
     *
     * @param event the click to inspect
     * @return true if an item is involved
     */
    private boolean isItemMovementAttempt(InventoryClickEvent event) {
        return isRealItem(event.getCurrentItem()) || isRealItem(event.getCursor())
                || isRealItem(itemSwappedInByKey(event));
    }

    /**
     * The viewer's own item a number-key or off-hand swap would move, or {@code null} for any other
     * click.
     */
    private ItemStack itemSwappedInByKey(InventoryClickEvent event) {
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            return event.getWhoClicked().getInventory().getItemInOffHand();
        }
        int button = event.getHotbarButton();
        if (event.getClick() == ClickType.NUMBER_KEY && button >= 0) {
            return event.getWhoClicked().getInventory().getItem(button);
        }
        return null;
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
        try {
            if (isEditable()) {
                // 编辑模式 - 保存并释放锁
                // Unconditional, and it has to stay that way: edit-mode clicks are not cancelled, so an item
                // the player has dragged in has already left their own inventory. That is why `save_on_close`
                // was deleted rather than wired (UltiKits/UltiRemoteBag#18, #37). A save that is refused gives
                // those items back instead (UltiKits/UltiRemoteBag#54).
                saveCurrentContents(false);
                SoundUtil.playCloseSound(player, config);
            }
        } finally {
            // Always, even if the save threw: the lock and the edit claim (and with it the claim's background
            // renewal) never outlive the window (UltiKits/UltiRemoteBag#54).
            lockService.release(ownerUuid, pageNum, player.getUniqueId());
            // A view of another player's bag leaves no copy of it behind (UltiKits/UltiRemoteBag#54).
            bagService.forgetUnlessOnline(ownerUuid);
        }
    }

    /** Whether this window may change its page: opened for editing, and its edit claim not lost. */
    private boolean isEditable() {
        return accessMode == AccessMode.EDIT && !editRightsLost;
    }

    /**
     * Called on the main thread when the edit claim of {@code holderUuid}'s session on this page was lost
     * (UltiKits/UltiRemoteBag#54): the window that session has open turns read-only at once, says so, gives back
     * the items put in since its last save, and shows the page as stored now.
     *
     * @param holderUuid the player whose editing session held the claim
     * @param ownerUuid  the bag owner
     * @param page       the page number
     */
    public static void claimLost(UUID holderUuid, UUID ownerUuid, int page) {
        if (holderUuid == null || InventoryAPI.getInstance() == null || Bukkit.getServer() == null) {
            return;
        }
        Player holder = Bukkit.getPlayer(holderUuid);
        if (holder == null) {
            return;
        }
        Gui current = InventoryAPI.getInstance().getPlayersCurrentGui(holder);
        if (current instanceof RemoteBagContentGUI) {
            RemoteBagContentGUI window = (RemoteBagContentGUI) current;
            if (window.pageNum == page && ownerUuid.equals(window.ownerUuid) && window.isEditable()) {
                window.loseEditRights(true);
            }
        }
    }

    /** Turns this window read-only for good, says so, and gives back what was put in since its last save. */
    private void loseEditRights(boolean windowStaysOpen) {
        editRightsLost = true;
        SoundUtil.playErrorSound(player, config);
        player.sendMessage(plugin.i18n("bag_claim_lost_read_only"));
        giveBackPutIns();
        if (windowStaysOpen) {
            loadBagContents();
        }
    }

    /**
     * Gives back to the viewer every item this window holds beyond what it read of its page (or what its last
     * save wrote) -- the items put in since -- because they are not going to be stored (UltiKits/UltiRemoteBag#54;
     * maintainer decision of 2026-10-06). Moving an item within the page counts as nothing. What does not fit in
     * the inventory drops at the viewer's feet; one log line names the items, the player and the page. Items
     * taken out of the page are not taken back.
     */
    private void giveBackPutIns() {
        List<ItemStack> putIn = putInSinceRead();
        if (putIn.isEmpty()) {
            return;
        }
        Map<Integer, ItemStack> leftOver = player.getInventory().addItem(putIn.toArray(new ItemStack[0]));
        int dropped = 0;
        for (ItemStack rest : leftOver.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
            dropped += rest.getAmount();
        }
        plugin.getLogger().warn(plugin.i18n("log_bag_items_returned")
                .replace("{PAGE}", String.valueOf(pageNum))
                .replace("{DROPPED}", String.valueOf(dropped))
                .replace("{ITEMS}", describe(putIn))
                .replace("{OWNER}", String.valueOf(ownerUuid))
                // The player's name last: it is data, so a brace sequence inside it stays as written.
                .replace("{PLAYER}", String.valueOf(player.getName())));
        player.sendMessage(ChatColor.YELLOW + plugin.i18n("msg_items_returned"));
    }

    /** The items this window shows beyond what it read, by type and amount. */
    private List<ItemStack> putInSinceRead() {
        List<ItemStack> read = new ArrayList<>();
        if (pageRead != null && pageRead.getItems() != null) {
            for (ItemStack item : pageRead.getItems()) {
                if (isRealItem(item)) {
                    read.add(item);
                }
            }
        }
        int[] unmatched = new int[read.size()];
        for (int i = 0; i < read.size(); i++) {
            unmatched[i] = read.get(i).getAmount();
        }
        List<ItemStack> putIn = new ArrayList<>();
        for (int slot = 0; slot < CONTENT_SIZE; slot++) {
            ItemStack shown = getInventory().getItem(slot);
            if (!isRealItem(shown)) {
                continue;
            }
            int amount = shown.getAmount();
            for (int i = 0; i < read.size() && amount > 0; i++) {
                if (unmatched[i] > 0 && read.get(i).isSimilar(shown)) {
                    int matched = Math.min(amount, unmatched[i]);
                    unmatched[i] -= matched;
                    amount -= matched;
                }
            }
            if (amount > 0) {
                ItemStack given = shown.clone();
                given.setAmount(amount);
                putIn.add(given);
            }
        }
        return putIn;
    }

    private static String describe(List<ItemStack> items) {
        StringBuilder text = new StringBuilder();
        for (ItemStack item : items) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(item.getType().name()).append(" x").append(item.getAmount());
        }
        return text.toString();
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
        if (!page.isEditable()
                || !viewer.getUniqueId().equals(page.ownerUuid)) {
            return FlushOutcome.NO_OPEN_PAGE;
        }

        return page.saveCurrentContents() ? FlushOutcome.WRITTEN : FlushOutcome.NOT_WRITTEN;
    }

    /**
     * Saves the edit-mode page {@code viewer} still has open, whoever's bag it is, before their quit
     * releases its lock. Paper closes a quitting player's window before the quit event, so this normally
     * finds nothing; it exists so the quit listener does not depend on that order.
     *
     * @param viewer the quitting player
     */
    public static void saveOpenEditPageOnQuit(Player viewer) {
        if (viewer == null || InventoryAPI.getInstance() == null) {
            return;
        }
        Gui current = InventoryAPI.getInstance().getPlayersCurrentGui(viewer);
        if (current instanceof RemoteBagContentGUI && ((RemoteBagContentGUI) current).isEditable()) {
            ((RemoteBagContentGUI) current).saveCurrentContents(false);
        }
    }

    /**
     * What {@link #flushOpenEditPage(Player)} did.
     * <p>
     * Three outcomes rather than a boolean, because a caller that reports "saved" has to distinguish
     * "there was nothing open" from "there was an open page and it did
     * not write" — the second must report nothing, since the page has already told the viewer why,
     * whether it declined for lack of authority or the database write failed.
     */
    public enum FlushOutcome {
        /** No flushable page was open; nothing was written (nothing is ever written from the cache). */
        NO_OPEN_PAGE,
        /** An open edit page was written (that page only). */
        WRITTEN,
        /**
         * An open edit page did not write: it no longer holds edit authority, or the database write
         * failed. Either way the page has already said so, and the caller must report nothing.
         */
        NOT_WRITTEN
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
     * Writes this page's live inventory as the page, conditionally on what the window read
     * ({@link RemoteBagService#savePage}, UltiKits/UltiRemoteBag#54); no other page is written.
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
        return saveCurrentContents(true);
    }

    /**
     * @param windowStaysOpen whether the window stays open after this save (Save button, {@code /bag save}), so
     *                        a refused save also redraws it from the page as stored now
     */
    private boolean saveCurrentContents(boolean windowStaysOpen) {
        if (lockService.hasLostClaim(ownerUuid, pageNum)) {
            // The edit claim was lost (UltiKits/UltiRemoteBag#54): nothing is written; the window turns
            // read-only and gives back what was put in.
            loseEditRights(windowStaysOpen);
            return false;
        }
        if (!lockService.mayWrite(ownerUuid, pageNum, player.getUniqueId())) {
            // Somebody else holds this page now. Writing would overwrite their committed edits with
            // a snapshot taken before they existed. Refusing silently would trade their loss for
            // this viewer's, so say so.
            SoundUtil.playErrorSound(player, config);
            player.sendMessage(ChatColor.RED + plugin.i18n("msg_save_refused_lock_taken"));
            return false;
        }

        if (pageRead == null) {
            // Never read: there is nothing this window's contents may be written over.
            SoundUtil.playErrorSound(player, config);
            player.sendMessage(ChatColor.RED + plugin.i18n("msg_save_failed"));
            return false;
        }
        ItemStack[] contents = new ItemStack[CONTENT_SIZE];
        for (int i = 0; i < CONTENT_SIZE; i++) {
            contents[i] = getInventory().getItem(i);
        }
        // Only this page, and only over what this window read (UltiKits/UltiRemoteBag#54).
        RemoteBagService.PageRead written = bagService.savePage(ownerUuid, pageNum, contents, pageRead);
        if (written == null) {
            // Not stored: reporting success here would be the same defect as reporting a save that did
            // not happen. What was put in since the read goes back to the viewer (UltiKits/UltiRemoteBag#54);
            // a window that stays open then shows the page as stored now and saves over that.
            SoundUtil.playErrorSound(player, config);
            player.sendMessage(ChatColor.RED + plugin.i18n("msg_save_failed"));
            giveBackPutIns();
            if (windowStaysOpen) {
                loadBagContents();
            }
            return false;
        }
        pageRead = written;
        return true;
    }
}
