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

    /** Slots in one inventory row. Fixed by Minecraft, named so the arithmetic is not a literal. */
    public static final int SLOTS_PER_ROW = 9;

    /**
     * How much one bag page holds, in slots. Fixed, and not configurable.
     * <p>
     * The single source for the three places that used to derive it separately: the content
     * window's own size, the array the service allocates for a created, cleared or empty page, and
     * the denominator of the main GUI's "Slots Used" lore. They were a window constant and two
     * readings of {@code rows_per_page}, and they disagreed -- at the shipped {@code 6} a full page
     * rendered {@code 45/54}, promising nine slots that could never be filled, and at {@code 2} it
     * rendered {@code 45/18}. That is why
     * <a href="https://github.com/UltiKits/UltiRemoteBag/issues/24">UltiRemoteBag#24</a> ended in
     * the key being deleted rather than its documentation narrowed: the displayed number was itself
     * promising a capacity that did not exist, so correcting only the declaration would have moved
     * the lie into the documentation and left it on screen.
     * <p>
     * Making capacity genuinely configurable is wanted and is recorded as
     * <a href="https://github.com/UltiKits/UltiRemoteBag/issues/38">UltiRemoteBag#38</a>; it needs a
     * variable-height window and a rule for items already stored beyond a shrunken page, neither of
     * which the deleted key had.
     */
    public static final int PAGE_CAPACITY = 5 * SLOTS_PER_ROW;

    /**
     * The highest slot index the loader will read out of a stored page, exclusive.
     * <p>
     * One row above {@link #PAGE_CAPACITY}, which is the largest page the deleted
     * {@code rows_per_page: 6} could once address. Nothing in this module has ever written above
     * index 44 -- {@code RemoteBagContentGUI} saves exactly {@link #PAGE_CAPACITY} slots -- so the
     * band 45-53 is reachable only from a hand-edited or foreign-written row. It is still read, so
     * that no row which loads today stops loading; such an item is invisible in the window and is
     * dropped by the first save of that page, which is why a row holding one should be repaired
     * before the page is opened.
     * <p>
     * A bound is needed at all because a stored slot index is data: without it,
     * {@code items.100000000} would turn its own key into an allocation request, and the resulting
     * {@link OutOfMemoryError} is an {@link Error}, so the {@code catch (Exception)} around the
     * deserializer would not contain it.
     */
    public static final int MAX_ADDRESSABLE_SLOTS = PAGE_CAPACITY + SLOTS_PER_ROW;

    @ConfigEntry(path = "permission_based_pages", comment = "Enable permission-based page limits")
    private boolean permissionBasedPages = true;

    @NotEmpty
    @ConfigEntry(path = "permission_prefix", comment = "Permission prefix for page limits (e.g., ultibag.pages.3)")
    private String permissionPrefix = "ultibag.pages.";

    // ==================== 经济设置 ====================

    @ConfigEntry(path = "economy.enabled", comment = "{config_comment_economy_enabled}")
    private boolean economyEnabled = true;

    @Range(min = 0, max = 1000000000)
    @ConfigEntry(path = "economy.base_price", comment = "{config_comment_economy_base_price}")
    private int basePrice = 10000;

    @ConfigEntry(path = "economy.price_increase_enabled", comment = "{config_comment_economy_price_increase_enabled}")
    private boolean priceIncreaseEnabled = true;

    @Range(min = 0.0, max = 10.0)
    @ConfigEntry(path = "economy.price_increase_rate", comment = "{config_comment_economy_price_increase_rate}")
    private double priceIncreaseRate = 0.1;
    
    // ==================== 音效设置 ====================

    @ConfigEntry(path = "sound.enabled", comment = "{config_comment_sound_enabled}")
    private boolean soundEnabled = true;

    @NotEmpty
    @ConfigEntry(path = "sound.open", comment = "{config_comment_sound_open}")
    private String openSound = "BLOCK_CHEST_OPEN";

    @NotEmpty
    @ConfigEntry(path = "sound.close", comment = "{config_comment_sound_close}")
    private String closeSound = "BLOCK_CHEST_CLOSE";

    @NotEmpty
    @ConfigEntry(path = "sound.purchase", comment = "{config_comment_sound_purchase}")
    private String purchaseSound = "ENTITY_PLAYER_LEVELUP";

    @NotEmpty
    @ConfigEntry(path = "sound.error", comment = "{config_comment_sound_error}")
    private String errorSound = "ENTITY_VILLAGER_NO";

    @Range(min = 0.0, max = 1.0)
    @ConfigEntry(path = "sound.volume", comment = "{config_comment_sound_volume}")
    private double soundVolume = 1.0;

    @Range(min = 0.5, max = 2.0)
    @ConfigEntry(path = "sound.pitch", comment = "{config_comment_sound_pitch}")
    private double soundPitch = 1.0;

    // ==================== 锁定设置 ====================

    @Range(min = 10, max = 3600)
    @ConfigEntry(path = "lock.timeout_seconds", comment = "{config_comment_lock_timeout_seconds}")
    private int lockTimeout = 300;
    
    @ConfigEntry(path = "lock.notify_readonly_viewers", comment = "{config_comment_lock_notify_readonly_viewers}")
    private boolean notifyReadonlyViewers = true;

    public RemoteBagConfig(String configFilePath) {
        super(configFilePath);
    }
}
