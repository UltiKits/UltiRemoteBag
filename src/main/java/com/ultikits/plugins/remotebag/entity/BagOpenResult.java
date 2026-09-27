package com.ultikits.plugins.remotebag.entity;

import com.ultikits.plugins.remotebag.enums.AccessMode;
import com.ultikits.plugins.remotebag.enums.LockType;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import lombok.Data;

import javax.annotation.Nullable;

/**
 * 背包打开结果实体
 * 封装尝试打开背包的结果信息
 * 
 * @author wisdomme
 * @version 1.0.0
 */
@Data
public class BagOpenResult {
    
    /**
     * 是否成功打开
     */
    private final boolean success;
    
    /**
     * 访问模式（成功时有效）
     */
    @Nullable
    private final AccessMode accessMode;
    
    /**
     * Which notice the holder of the result is shown (read-only or blocked), or {@code null} for an
     * edit-mode result, which shows none. The text itself comes from the language file when it is
     * rendered ({@link #renderMessage(UltiToolsPlugin)}), in the server's language.
     */
    @Nullable
    private final Notice notice;
    
    /**
     * 现有锁信息（被阻止时提供）
     */
    @Nullable
    private final BagLockInfo existingLock;
    
    /**
     * 私有构造函数
     */
    private BagOpenResult(boolean success, AccessMode accessMode, Notice notice, BagLockInfo existingLock) {
        this.success = success;
        this.accessMode = accessMode;
        this.notice = notice;
        this.existingLock = existingLock;
    }

    /** The notice a result carries; each names one language-file entry. */
    public enum Notice {
        /** The owner is using the page, so it opens read-only. */
        READ_ONLY_IN_USE,
        /** An admin holds the page, so it does not open. */
        BLOCKED_BY_ADMIN,
        /** The owner holds the page, so it does not open. */
        BLOCKED_IN_USE
    }
    
    /**
     * 创建编辑模式成功结果
     * 
     * @return 编辑模式的成功结果
     */
    public static BagOpenResult editMode() {
        return new BagOpenResult(true, AccessMode.EDIT, null, null);
    }
    
    /**
     * 创建只读模式结果
     * 
     * @param ownerLock 所有者锁信息
     * @return 只读模式的结果
     */
    public static BagOpenResult readOnlyMode(BagLockInfo ownerLock) {
        return new BagOpenResult(true, AccessMode.READ_ONLY, Notice.READ_ONLY_IN_USE, ownerLock);
    }
    
    /**
     * 创建被阻止的结果
     * 
     * @param lock 阻止的锁信息
     * @return 被阻止的结果
     */
    public static BagOpenResult blocked(BagLockInfo lock) {
        Notice notice = lock.getLockType() == LockType.ADMIN ? Notice.BLOCKED_BY_ADMIN : Notice.BLOCKED_IN_USE;
        return new BagOpenResult(false, null, notice, lock);
    }

    /**
     * The notice this result shows, in the server's language, with the lock holder's name filled in;
     * {@code null} for an edit-mode result, which shows none. The holder's name is filled in after the
     * text is read, so the name is shown as written.
     *
     * @param plugin the module, whose language file gives the text
     * @return the text to send, or {@code null}
     */
    @Nullable
    public String renderMessage(UltiToolsPlugin plugin) {
        if (notice == null) {
            return null;
        }
        String text;
        switch (notice) {
            case READ_ONLY_IN_USE:
                text = plugin.i18n("bag_read_only_in_use");
                break;
            case BLOCKED_BY_ADMIN:
                text = plugin.i18n("bag_blocked_by_admin");
                break;
            default:
                text = plugin.i18n("bag_blocked_in_use");
                break;
        }
        String holder = existingLock == null ? "" : String.valueOf(existingLock.getHolderName());
        return text.replace("{PLAYER}", holder);
    }
    
    /**
     * 判断是否为编辑模式
     * 
     * @return 如果是编辑模式返回 true
     */
    public boolean isEditMode() {
        return success && accessMode == AccessMode.EDIT;
    }
    
    /**
     * 判断是否为只读模式
     * 
     * @return 如果是只读模式返回 true
     */
    public boolean isReadOnlyMode() {
        return success && accessMode == AccessMode.READ_ONLY;
    }
}
