package com.ultikits.plugins.remotebag.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.annotations.config.Range;

import lombok.Getter;
import lombok.Setter;

/**
 * Remote Bag configuration.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Getter
@Setter
@ConfigEntity("config/remotebag.yml")
public class RemoteBagConfig extends AbstractConfigEntity {

    @Range(min = 1, max = 100)
    @ConfigEntry(path = "default_pages", comment = "Default number of bag pages for new players")
    private int defaultPages = 1;

    @Range(min = 1, max = 100)
    @ConfigEntry(path = "max_pages", comment = "Maximum number of bag pages a player can have")
    private int maxPages = 10;

    /**
     * The largest {@code rows_per_page} any legal configuration may hold.
     * <p>
     * A compile-time constant rather than a literal in the {@link Range} below, because
     * {@code RemoteBagService} bounds its page allocation by this key's ceiling and used to restate
     * that derivation as its own literal {@code 54}. Nothing linked the two, so widening this range
     * would silently have turned that allocation guard into a load-time item-discard: every stored
     * slot index between the old ceiling and the new one would become legal for the GUI to write and
     * illegal for the loader to read. Referencing one constant makes the two numbers unable to
     * diverge, which no test can achieve.
     */
    public static final int MAX_ROWS_PER_PAGE = 6;

    /** Slots in one inventory row. Fixed by Minecraft, named so the arithmetic is not a literal. */
    public static final int SLOTS_PER_ROW = 9;

    @Range(min = 1, max = MAX_ROWS_PER_PAGE)
    @ConfigEntry(path = "rows_per_page", comment = "Number of rows per page (1-6, each row = 9 slots)")
    private int rowsPerPage = MAX_ROWS_PER_PAGE;

    @ConfigEntry(path = "permission_based_pages", comment = "Enable permission-based page limits")
    private boolean permissionBasedPages = true;

    @NotEmpty
    @ConfigEntry(path = "permission_prefix", comment = "Permission prefix for page limits (e.g., ultibag.pages.3)")
    private String permissionPrefix = "ultibag.pages.";

    @ConfigEntry(path = "save_on_close", comment = "Save bag when player closes the GUI")
    private boolean saveOnClose = true;

    // ==================== 经济设置 ====================

    @ConfigEntry(path = "economy.enabled", comment = "是否启用购买背包功能（需要 Vault）")
    private boolean economyEnabled = true;

    @Range(min = 0, max = 1000000000)
    @ConfigEntry(path = "economy.base_price", comment = "购买背包的基础价格")
    private int basePrice = 10000;

    @ConfigEntry(path = "economy.price_increase_enabled", comment = "是否启用价格递增（每购买一个背包价格增加）")
    private boolean priceIncreaseEnabled = true;

    @Range(min = 0.0, max = 10.0)
    @ConfigEntry(path = "economy.price_increase_rate", comment = "价格递增比率（0.1 = 每个背包增加10%）")
    private double priceIncreaseRate = 0.1;
    
    // ==================== 音效设置 ====================

    @ConfigEntry(path = "sound.enabled", comment = "是否启用音效")
    private boolean soundEnabled = true;

    @NotEmpty
    @ConfigEntry(path = "sound.open", comment = "打开背包音效")
    private String openSound = "BLOCK_CHEST_OPEN";

    @NotEmpty
    @ConfigEntry(path = "sound.close", comment = "关闭背包音效")
    private String closeSound = "BLOCK_CHEST_CLOSE";

    @NotEmpty
    @ConfigEntry(path = "sound.purchase", comment = "购买成功音效")
    private String purchaseSound = "ENTITY_PLAYER_LEVELUP";

    @NotEmpty
    @ConfigEntry(path = "sound.error", comment = "错误提示音效")
    private String errorSound = "ENTITY_VILLAGER_NO";

    @Range(min = 0.0, max = 1.0)
    @ConfigEntry(path = "sound.volume", comment = "音量 (0.0-1.0)")
    private double soundVolume = 1.0;

    @Range(min = 0.5, max = 2.0)
    @ConfigEntry(path = "sound.pitch", comment = "音调 (0.5-2.0)")
    private double soundPitch = 1.0;

    // ==================== 锁定设置 ====================

    @Range(min = 10, max = 3600)
    @ConfigEntry(path = "lock.timeout_seconds",
            comment = "Bag lock recovery timeout in seconds. Reclaims a lock whose holder's session "
                    + "ended without releasing it; a holder who is online with the page open keeps "
                    + "the lock however long they idle. "
                    + "背包锁的回收超时时间（秒）。仅用于回收持有者会话异常结束而未释放的锁；"
                    + "持有者在线且页面仍打开时，无论空闲多久都会保留该锁")
    private int lockTimeout = 300;
    
    @ConfigEntry(path = "lock.notify_readonly_viewers", comment = "所有者开始使用背包时是否通知只读查看者")
    private boolean notifyReadonlyViewers = true;

    public RemoteBagConfig(String configFilePath) {
        super(configFilePath);
    }
}
