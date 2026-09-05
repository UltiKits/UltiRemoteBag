package com.ultikits.plugins.remotebag.util;

import com.cryptomorin.xseries.XSound;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import org.bukkit.entity.Player;

/**
 * 音效工具类
 * 提供统一的音效播放方法
 * 
 * @author wisdomme
 * @version 1.0.0
 */
public final class SoundUtil {
    
    private SoundUtil() {
        // 工具类禁止实例化
    }
    
    /**
     * 播放指定音效
     * 
     * @param player    玩家
     * @param soundName 音效名称（Bukkit Sound 枚举名）
     * @param volume    音量 (0.0-1.0)
     * @param pitch     音调 (0.5-2.0)
     */
    public static void playSound(Player player, String soundName, float volume, float pitch) {
        if (player == null || soundName == null || soundName.isEmpty()) {
            return;
        }

        // 音效名无效，静默忽略（Optional 为空时短路，行为与原 catch 分支一致）
        // Invalid sound name -- silently ignore (empty Optional short-circuits, same as the old catch)
        XSound.matchXSound(soundName.toUpperCase()).ifPresent(xSound ->
                player.playSound(player.getLocation(), xSound.get(), volume, pitch));
    }
    
    /**
     * 使用配置播放音效
     * 
     * @param player    玩家
     * @param soundName 音效名称
     * @param config    配置对象
     */
    public static void playSound(Player player, String soundName, RemoteBagConfig config) {
        if (!config.isSoundEnabled()) {
            return;
        }
        playSound(player, soundName, (float) config.getSoundVolume(), (float) config.getSoundPitch());
    }

    /**
     * 播放打开背包音效
     *
     * @param player 玩家
     * @param config 配置对象
     */
    public static void playOpenSound(Player player, RemoteBagConfig config) {
        if (!config.isSoundEnabled()) {
            return;
        }
        playSound(player, config.getOpenSound(), (float) config.getSoundVolume(), (float) config.getSoundPitch());
    }

    /**
     * 播放关闭背包音效
     *
     * @param player 玩家
     * @param config 配置对象
     */
    public static void playCloseSound(Player player, RemoteBagConfig config) {
        if (!config.isSoundEnabled()) {
            return;
        }
        playSound(player, config.getCloseSound(), (float) config.getSoundVolume(), (float) config.getSoundPitch());
    }

    /**
     * 播放购买成功音效
     *
     * @param player 玩家
     * @param config 配置对象
     */
    public static void playPurchaseSound(Player player, RemoteBagConfig config) {
        if (!config.isSoundEnabled()) {
            return;
        }
        playSound(player, config.getPurchaseSound(), (float) config.getSoundVolume(), (float) config.getSoundPitch());
    }

    /**
     * 播放错误提示音效
     *
     * @param player 玩家
     * @param config 配置对象
     */
    public static void playErrorSound(Player player, RemoteBagConfig config) {
        if (!config.isSoundEnabled()) {
            return;
        }
        playSound(player, config.getErrorSound(), (float) config.getSoundVolume(), (float) config.getSoundPitch());
    }

    /**
     * 播放翻页音效
     *
     * @param player 玩家
     * @param config 配置对象
     */
    public static void playPageSound(Player player, RemoteBagConfig config) {
        if (!config.isSoundEnabled()) {
            return;
        }
        // 翻页使用轻微的点击音效
        playSound(player, "UI_BUTTON_CLICK", (float) (config.getSoundVolume() * 0.5), (float) config.getSoundPitch());
    }
}
