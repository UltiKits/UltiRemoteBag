package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.entity.BagLockInfo;
import com.ultikits.plugins.remotebag.entity.BagOpenResult;
import com.ultikits.plugins.remotebag.enums.AccessMode;
import com.ultikits.plugins.remotebag.enums.LockType;
import com.ultikits.plugins.remotebag.gui.RemoteBagContentGUI;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 背包锁定服务
 * 管理背包的并发访问控制，实现编辑/只读模式切换
 * 
 * 锁定规则：
 * - 所有者拥有最高优先级，管理员在所有者使用时只能只读
 * - 管理员编辑时，所有者需要等待
 * - 同一时间只有一个人可以编辑
 * - 锁在其页面仍处于打开状态时不会因超时被回收（见 {@link #isReclaimable}）
 * <p>
 * Lock rules: the owner outranks an administrator, only one holder may edit at a time, and
 * {@code lock.timeout_seconds} reclaims a lock ONLY when its holder is not currently holding the page
 * open. The timeout is a recovery mechanism for a session that ended without releasing its lock, not
 * a lease that a present holder has to renew — see {@link #isReclaimable(UUID, int, BagLockInfo)}.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class BagLockService {

    @Autowired
    private UltiToolsPlugin plugin;

    /**
     * 锁存储: "ownerUUID:pageNum" -> LockInfo
     */
    private final Map<String, BagLockInfo> locks = new ConcurrentHashMap<>();
    
    /**
     * 只读会话追踪: "ownerUUID:pageNum" -> Set<管理员UUID>
     * 记录正在以只读模式查看的管理员
     */
    private final Map<String, Set<UUID>> readOnlySessions = new ConcurrentHashMap<>();
    
    /**
     * 默认锁超时时间（毫秒）- 5分钟
     */
    private long lockTimeoutMillis = 300_000L;
    
    /**
     * 设置锁超时时间
     * 
     * @param timeoutSeconds 超时时间（秒）
     */
    public void setLockTimeout(int timeoutSeconds) {
        this.lockTimeoutMillis = timeoutSeconds * 1000L;
    }
    
    /**
     * 所有者尝试打开自己的背包
     * 
     * @param ownerUuid 背包所有者 UUID
     * @param pageNum   页码
     * @param owner     所有者玩家对象
     * @return 打开结果
     */
    public BagOpenResult ownerOpen(UUID ownerUuid, int pageNum, Player owner) {
        String key = makeKey(ownerUuid, pageNum);
        
        BagLockInfo existing = locks.get(key);
        
        if (existing != null) {
            // 检查是否是自己的锁
            if (existing.getHolderUuid().equals(owner.getUniqueId())) {
                return BagOpenResult.editMode();
            }
            
            // 检查锁是否可被超时回收
            if (isReclaimable(ownerUuid, pageNum, existing)) {
                locks.remove(key);
            } else if (existing.getLockType() == LockType.ADMIN) {
                // 管理员正在编辑，所有者需要等待
                return BagOpenResult.blocked(existing);
            }
        }
        
        // 所有者获取锁（最高优先级）
        BagLockInfo ownerLock = BagLockInfo.builder()
                .holderUuid(owner.getUniqueId())
                .holderName(owner.getName())
                .lockType(LockType.OWNER)
                .acquiredAt(System.currentTimeMillis())
                .build();
        
        locks.put(key, ownerLock);
        
        // 通知正在只读查看的管理员
        notifyReadOnlyAdmins(key, owner.getName());
        
        return BagOpenResult.editMode();
    }
    
    /**
     * 管理员尝试打开他人的背包
     * 
     * @param ownerUuid 背包所有者 UUID
     * @param pageNum   页码
     * @param admin     管理员玩家对象
     * @return 打开结果
     */
    public BagOpenResult adminOpen(UUID ownerUuid, int pageNum, Player admin) {
        String key = makeKey(ownerUuid, pageNum);
        
        BagLockInfo existing = locks.get(key);
        
        if (existing != null) {
            // 检查锁是否可被超时回收
            if (isReclaimable(ownerUuid, pageNum, existing)) {
                locks.remove(key);
                existing = null;
            }
        }
        
        if (existing != null) {
            if (existing.getLockType() == LockType.OWNER) {
                // 所有者正在使用 → 管理员只读模式
                addReadOnlySession(key, admin.getUniqueId());
                return BagOpenResult.readOnlyMode(existing);
            }
            
            if (existing.getLockType() == LockType.ADMIN) {
                if (existing.getHolderUuid().equals(admin.getUniqueId())) {
                    // 自己的锁，继续编辑
                    return BagOpenResult.editMode();
                }
                // 其他管理员正在编辑
                return BagOpenResult.blocked(existing);
            }
        }
        
        // 管理员获取编辑锁
        BagLockInfo adminLock = BagLockInfo.builder()
                .holderUuid(admin.getUniqueId())
                .holderName(admin.getName())
                .lockType(LockType.ADMIN)
                .acquiredAt(System.currentTimeMillis())
                .build();
        
        locks.put(key, adminLock);
        return BagOpenResult.editMode();
    }
    
    /**
     * 释放背包锁
     * 
     * @param ownerUuid 背包所有者 UUID
     * @param pageNum   页码
     * @param holder    锁持有者 UUID
     */
    public void release(UUID ownerUuid, int pageNum, UUID holder) {
        String key = makeKey(ownerUuid, pageNum);
        BagLockInfo existing = locks.get(key);
        
        if (existing != null && existing.getHolderUuid().equals(holder)) {
            locks.remove(key);
        }
        
        // 同时清理只读会话
        removeReadOnlySession(key, holder);
    }
    
    /**
     * 玩家退出时释放所有持有的锁
     * 
     * @param holder 玩家 UUID
     */
    public void releaseAll(UUID holder) {
        // 释放持有的锁
        locks.entrySet().removeIf(entry -> 
                entry.getValue().getHolderUuid().equals(holder)
        );
        
        // 清理只读会话
        for (Set<UUID> sessions : readOnlySessions.values()) {
            sessions.remove(holder);
        }
    }
    
    /**
     * 获取当前访问模式
     * 
     * @param ownerUuid 背包所有者 UUID
     * @param pageNum   页码
     * @param viewer    查看者 UUID
     * @return 访问模式
     */
    public AccessMode getCurrentAccessMode(UUID ownerUuid, int pageNum, UUID viewer) {
        String key = makeKey(ownerUuid, pageNum);
        BagLockInfo existing = locks.get(key);
        
        if (existing == null) {
            return AccessMode.EDIT;
        }
        
        // 检查锁是否可被超时回收
        if (isReclaimable(ownerUuid, pageNum, existing)) {
            locks.remove(key);
            return AccessMode.EDIT;
        }
        
        // 如果查看者持有锁
        if (existing.getHolderUuid().equals(viewer)) {
            return AccessMode.EDIT;
        }
        
        // 存在所有者锁但不是查看者 → 只读
        if (existing.getLockType() == LockType.OWNER) {
            return AccessMode.READ_ONLY;
        }
        
        return AccessMode.EDIT;
    }
    
    /**
     * 检查是否可以升级为编辑模式
     * 用于管理员从只读模式刷新时检查
     * 
     * @param ownerUuid 背包所有者 UUID
     * @param pageNum   页码
     * @return 如果可以升级为编辑模式返回 true
     */
    public boolean canUpgradeToEdit(UUID ownerUuid, int pageNum) {
        String key = makeKey(ownerUuid, pageNum);
        BagLockInfo existing = locks.get(key);
        
        // 无锁或锁已可被超时回收 → 可以编辑
        return existing == null || isReclaimable(ownerUuid, pageNum, existing);
    }
    
    /**
     * 获取锁信息
     * 
     * @param ownerUuid 背包所有者 UUID
     * @param pageNum   页码
     * @return 锁信息，如果无锁返回 empty
     */
    public Optional<BagLockInfo> getLockInfo(UUID ownerUuid, int pageNum) {
        String key = makeKey(ownerUuid, pageNum);
        BagLockInfo info = locks.get(key);
        
        if (info != null && isReclaimable(ownerUuid, pageNum, info)) {
            locks.remove(key);
            return Optional.empty();
        }

        return Optional.ofNullable(info);
    }

    /**
     * Whether {@code writer} may still persist this page.
     * <p>
     * True when no live lock is held on the page, or when the live lock is {@code writer}'s own. The
     * no-lock case is the ordinary benign one — the lock expired and nobody took it — and must keep
     * saving, or every normal session would silently stop persisting.
     * <p>
     * {@link #getCurrentAccessMode} is deliberately NOT reused for this. Its fall-through returns
     * {@link AccessMode#EDIT} for an {@link LockType#ADMIN} lock held by somebody else, which is
     * right for its own purpose (deciding what mode to hand a viewer, given that {@link #adminOpen}
     * refuses a second administrator outright) and wrong for this one: "another administrator holds
     * this page" is exactly the case a write must not proceed through.
     *
     * @param ownerUuid 背包所有者 UUID
     * @param pageNum   页码
     * @param writer    the player whose page wants to write
     * @return true if nobody else holds this page
     */
    public boolean mayWrite(UUID ownerUuid, int pageNum, UUID writer) {
        BagLockInfo existing = locks.get(makeKey(ownerUuid, pageNum));
        if (existing == null || isReclaimable(ownerUuid, pageNum, existing)) {
            return true;
        }
        return existing.getHolderUuid().equals(writer);
    }

    /**
     * Whether a lock's holder currently has this very page open.
     * <p>
     * Public because the admin path needs it to explain itself: without a reason, an administrator
     * who waited out {@code lock.timeout_seconds} and still got read-only would read the new
     * behaviour as a bug and file it as one.
     *
     * @param ownerUuid 背包所有者 UUID
     * @param pageNum   页码
     * @return true if a lock is held on this page and its holder is looking at it right now
     */
    public boolean isHeldByViewingHolder(UUID ownerUuid, int pageNum) {
        BagLockInfo existing = locks.get(makeKey(ownerUuid, pageNum));
        return existing != null
                && RemoteBagContentGUI.isPageOpenBy(ownerUuid, pageNum, existing.getHolderUuid());
    }

    /**
     * Whether the timeout may reclaim this lock.
     * <p>
     * {@code lock.timeout_seconds} exists to free a lock whose holder's session ended without
     * releasing it — a crash, a network drop, a shutdown mid-session. A holder who is online with
     * this page open has NOT ended their session, so the clock does not apply to them, however long
     * they idle.
     * <p>
     * The condition is presence, not activity. Refreshing the lock's timestamp on a click or a save
     * would be an approximation of presence, and an approximation that fails in the case that
     * matters: a page left open with no clicks at all would still be reclaimed after the timeout,
     * which is exactly the AFK owner whose later save destroyed an administrator's items. Presence
     * is directly observable, so it is observed — see
     * {@link RemoteBagContentGUI#isPageOpenBy(UUID, int, UUID)} for why it is read live rather than
     * tracked in a flag.
     * <p>
     * Every path that ends a session still releases the lock: quitting removes it outright
     * ({@link #releaseAll}, driven by the module's {@code PlayerQuitEvent} listener), closing the
     * page releases it in {@code RemoteBagContentGUI#onClose}, and a crash or a reload leaves no open
     * view for {@code isPageOpenBy} to find.
     *
     * @param ownerUuid 背包所有者 UUID
     * @param pageNum   页码
     * @param lock      the lock being examined
     * @return true if the lock is past its timeout AND nobody is holding its page open
     */
    private boolean isReclaimable(UUID ownerUuid, int pageNum, BagLockInfo lock) {
        if (!lock.isExpired(lockTimeoutMillis)) {
            return false;
        }
        return !RemoteBagContentGUI.isPageOpenBy(ownerUuid, pageNum, lock.getHolderUuid());
    }
    
    /**
     * 检查背包是否被锁定
     * 
     * @param ownerUuid 背包所有者 UUID
     * @param pageNum   页码
     * @return 如果被锁定返回 true
     */
    public boolean isLocked(UUID ownerUuid, int pageNum) {
        return getLockInfo(ownerUuid, pageNum).isPresent();
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 生成锁的 key
     */
    private String makeKey(UUID ownerUuid, int pageNum) {
        return ownerUuid.toString() + ":" + pageNum;
    }
    
    /**
     * 添加只读会话
     */
    private void addReadOnlySession(String key, UUID adminUuid) {
        readOnlySessions.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(adminUuid);
    }
    
    /**
     * 移除只读会话
     */
    private void removeReadOnlySession(String key, UUID uuid) {
        Set<UUID> sessions = readOnlySessions.get(key);
        if (sessions != null) {
            sessions.remove(uuid);
            if (sessions.isEmpty()) {
                readOnlySessions.remove(key);
            }
        }
    }
    
    /**
     * 通知只读模式的管理员
     */
    private void notifyReadOnlyAdmins(String key, String ownerName) {
        Set<UUID> sessions = readOnlySessions.get(key);
        if (sessions != null && !sessions.isEmpty()) {
            for (UUID adminUuid : sessions) {
                Player admin = Bukkit.getPlayer(adminUuid);
                if (admin != null && admin.isOnline()) {
                    admin.sendMessage("§e" + plugin.i18n("msg_owner_started_using").replace("{0}", ownerName));
                }
            }
        }
    }
}
