package com.ultikits.plugins.remotebag.listener;

import com.ultikits.plugins.remotebag.gui.RemoteBagContentGUI;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 远程背包事件监听器
 * <p>
 * 负责处理玩家退出时的清理工作：
 * <ul>
 *   <li>释放玩家持有的所有背包锁</li>
 *   <li>保存并清理缓存数据</li>
 * </ul>
 * <p>
 * 注意：GUI 交互事件由 mc.obliviate.inventory 框架处理，
 * 本监听器仅处理全局事件。
 *
 * @author wisdomme
 * @version 2.0.0
 */
@EventListener
public class BagListener implements Listener {

    private final RemoteBagService bagService;
    private final BagLockService lockService;

    public BagListener(RemoteBagService bagService, BagLockService lockService) {
        this.bagService = bagService;
        this.lockService = lockService;
    }
    
    /**
     * 处理玩家退出事件
     * <p>
     * When a player quits: an edit-mode page they still have open is saved (Paper normally closed it, and
     * so saved it, just before this event); then every lock they hold is released; then their read cache
     * entry is dropped. Nothing is written from the cache: every change was written when it was made
     * (UltiKits/UltiRemoteBag#54).
     *
     * @param event 玩家退出事件
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        RemoteBagContentGUI.saveOpenEditPageOnQuit(player);
        lockService.releaseAll(player.getUniqueId());
        bagService.clearCache(player.getUniqueId());
    }
}
