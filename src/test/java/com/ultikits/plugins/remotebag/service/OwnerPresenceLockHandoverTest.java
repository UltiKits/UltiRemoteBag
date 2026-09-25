package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.MockBukkitSupport;
import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.BagLockInfo;
import com.ultikits.plugins.remotebag.entity.BagOpenResult;
import com.ultikits.plugins.remotebag.gui.RemoteBagContentGUI;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import mc.obliviate.inventory.InventoryAPI;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.HandlerList;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * A bag lock is not reclaimed by its timeout while its holder still has the page open, and the items
 * of both parties survive the scenario in which it used to be
 * (found reviewing pull request UltiKits/UltiRemoteBag#34).
 *
 * <h2>The defect these cases pin down</h2>
 * {@code lock.timeout_seconds} used to expire a lock purely on wall-clock age, and nothing closed or
 * downgraded a page when its lock expired. So an owner who went AFK with the page open lost edit
 * authority after the configured timeout while still looking at the page; an administrator then took
 * an ADMIN lock on the same page, added an item and saved it; and the owner's eventual close wrote
 * their pre-administrator 45-slot snapshot over the row. The administrator's item was destroyed --
 * gone from the database, gone from the cache, and not in anybody's inventory. The mirror case
 * duplicated instead: the administrator REMOVED an item, and the owner's stale snapshot put it back
 * while the administrator kept it.
 *
 * <h2>What is asserted, and why it is not a proxy</h2>
 * Not "the lock was not expired" -- that is the mechanism, and asserting it would pass against an
 * implementation that kept the lock and lost the items anyway. Each case totals every item in
 * existence before and after the whole scenario, across the persisted row (re-read through
 * {@code loadBagIfNeeded} after the cache is dropped, so the cache and the row are the same number),
 * both players' inventories and both players' cursors, and asserts the total is unchanged AND that
 * each item is in exactly one named place. Destruction lowers the total; duplication raises it; an
 * administrator's item being absorbed into a present owner's bag moves it, and the "exactly one named
 * place" half is what catches that.
 *
 * <h2>What makes a vacuous pass impossible here</h2>
 * Nothing is mocked except the plugin handle: a real {@link BagLockService}, a real
 * {@link RemoteBagService} over a real {@link InMemoryRemoteBagStore}, real
 * {@link RemoteBagContentGUI} pages opened through {@code Gui#open()}, and real
 * {@link InventoryClickEvent}/{@link InventoryCloseEvent}s dispatched through
 * {@link Bukkit#getPluginManager()} so the GUI library's own listener makes the cancel decision.
 * {@link #theAdministratorsEditIsPersistedWhenTheOwnerIsGone()} is the control for the harness
 * itself: with the owner's page closed, the identical sequence DOES hand the lock over and DOES
 * persist the administrator's item, so a "nothing moved" assertion above cannot pass because the
 * harness never moves anything.
 */
@DisplayName("A lock is not reclaimed while its page is open (UltiRemoteBag#34)")
class OwnerPresenceLockHandoverTest {

    private static final int PAGE = 1;
    private static final int OWNER_SLOT = 0;
    private static final int ADMIN_TARGET_SLOT = 5;
    /** Bottom row slot 4 -- the Save icon in edit mode, the disabled Save icon in read-only mode. */
    private static final int TOOLBAR_SAVE_SLOT = 49;
    private static final int LOCK_TIMEOUT_SECONDS = 300;

    private ServerMock server;
    private InventoryAPI inventoryApi;

    private UltiToolsPlugin mockPlugin;
    private RemoteBagConfig config;
    private InMemoryRemoteBagStore store;
    private RemoteBagService bagService;
    private BagLockService lockService;

    private PlayerMock owner;
    private PlayerMock admin;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkitSupport.bootstrapLiveServer();
        UltiRemoteBagTestHelper.setUp();

        inventoryApi = new InventoryAPI(MockBukkit.createMockPlugin("ObliviateHost"));
        inventoryApi.init();

        mockPlugin = mock(UltiToolsPlugin.class);
        lenient().when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(mockPlugin.getLogger()).thenReturn(mock(PluginLogger.class));

        config = UltiRemoteBagTestHelper.createDefaultConfig();
        store = new InMemoryRemoteBagStore();

        bagService = new RemoteBagService(mockPlugin, config);
        UltiRemoteBagTestHelper.setField(bagService, "dataOperator", store);

        lockService = new BagLockService();
        UltiRemoteBagTestHelper.setField(lockService, "plugin", mockPlugin);
        // Tolerant: the config field arrives with UltiKits/UltiRemoteBag#19's fix. See
        // UltiRemoteBagTestHelper#setFieldIfPresent.
        UltiRemoteBagTestHelper.setFieldIfPresent(lockService, "config", config);
        lockService.setLockTimeout(LOCK_TIMEOUT_SECONDS);

        owner = server.addPlayer("Owner");
        admin = server.addPlayer("Admin");
    }

    @AfterEach
    void tearDown() throws Exception {
        HandlerList.unregisterAll(inventoryApi.getListener());
        UltiRemoteBagTestHelper.tearDown();
        MockBukkitSupport.safeUnmock();
    }

    @Test
    @DisplayName("An administrator's item is still theirs after an AFK owner's page outlives the timeout")
    void theAdministratorsItemIsNotDestroyedByThePresentOwnersStaleSnapshot() {
        seedOwnerPageWithOneDiamond();
        admin.getInventory().setItem(0, new ItemStack(Material.EMERALD));
        int diamondsBefore = totalDiamonds();
        int emeraldsBefore = totalEmeralds();
        assertThat(diamondsBefore).as("precondition: exactly one diamond exists").isEqualTo(1);
        assertThat(emeraldsBefore).as("precondition: exactly one emerald exists").isEqualTo(1);

        // 1. The owner opens page 1 and goes AFK with it open.
        RemoteBagContentGUI ownersPage = openAsOwner();
        // 2. The configured timeout elapses with no clicks at all. Presence, not activity, is what
        //    keeps the lock: refreshing a timestamp on interaction would not survive this step.
        ageTheLockPastItsTimeout();

        // 3. The administrator opens the same page.
        BagOpenResult adminResult = lockService.adminOpen(owner.getUniqueId(), PAGE, admin);
        RemoteBagContentGUI adminsPage = openPageFor(admin, adminResult.getAccessMode());

        // 4. The administrator tries to put their emerald into the page and save it.
        admin.setItemOnCursor(admin.getInventory().getItem(0));
        admin.getInventory().setItem(0, null);
        click(admin, adminsPage, ADMIN_TARGET_SLOT, ClickType.LEFT, InventoryAction.PLACE_ALL);
        click(admin, adminsPage, TOOLBAR_SAVE_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL);

        // 5. The owner comes back and closes the page, which saves their own snapshot.
        close(owner, ownersPage);
        close(admin, adminsPage);
        returnCursorToInventory(admin);

        assertThat(totalDiamonds())
                .as("the owner's diamond still exists exactly once")
                .isEqualTo(diamondsBefore);
        assertThat(totalEmeralds())
                .as("the administrator's emerald still exists exactly once -- it was destroyed here "
                        + "before the lock stopped expiring under a present holder")
                .isEqualTo(emeraldsBefore);
        assertThat(countIn(admin.getInventory(), Material.EMERALD))
                .as("and the one place it is, is the administrator's own inventory: a present owner "
                        + "keeps exclusive edit authority, so nothing else may be written into their page")
                .isEqualTo(1);
        assertThat(countIn(storedPageOf(owner.getUniqueId()), Material.EMERALD))
                .as("the owner's stored page did not absorb the administrator's emerald")
                .isZero();
        assertThat(countIn(storedPageOf(owner.getUniqueId()), Material.DIAMOND))
                .as("the owner's own item is still where it was")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("An item the administrator takes out is not duplicated back by the present owner's snapshot")
    void theOwnersStaleSnapshotDoesNotDuplicateAnItemTheAdministratorRemoved() {
        seedOwnerPageWithOneDiamond();
        int diamondsBefore = totalDiamonds();
        assertThat(diamondsBefore).as("precondition: exactly one diamond exists").isEqualTo(1);

        RemoteBagContentGUI ownersPage = openAsOwner();
        ageTheLockPastItsTimeout();

        BagOpenResult adminResult = lockService.adminOpen(owner.getUniqueId(), PAGE, admin);
        RemoteBagContentGUI adminsPage = openPageFor(admin, adminResult.getAccessMode());

        // The mirror of the case above: the administrator TAKES the diamond and saves the now-empty
        // page. The owner's stale snapshot then used to restore it while the administrator kept it.
        click(admin, adminsPage, OWNER_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        click(admin, adminsPage, TOOLBAR_SAVE_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL);

        close(owner, ownersPage);
        close(admin, adminsPage);
        returnCursorToInventory(admin);

        assertThat(totalDiamonds())
                .as("the diamond exists exactly once -- not once in the bag and once in the "
                        + "administrator's hands")
                .isEqualTo(diamondsBefore);
        assertThat(countIn(storedPageOf(owner.getUniqueId()), Material.DIAMOND))
                .as("and the one place it is, is still the owner's page: a read-only viewer cannot "
                        + "take an item out of a present owner's bag")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Control: with the owner's page closed, the timeout does hand the lock over and the administrator's save persists")
    void theAdministratorsEditIsPersistedWhenTheOwnerIsGone() {
        seedOwnerPageWithOneDiamond();
        admin.getInventory().setItem(0, new ItemStack(Material.EMERALD));

        RemoteBagContentGUI ownersPage = openAsOwner();
        ageTheLockPastItsTimeout();
        // The one difference from the first case: the owner is no longer looking at the page. Closing
        // it also releases the lock, which is the ordinary path; ageing it first means the assertion
        // below would still hold if it had not.
        close(owner, ownersPage);

        BagOpenResult adminResult = lockService.adminOpen(owner.getUniqueId(), PAGE, admin);
        assertThat(adminResult.isEditMode())
                .as("control: with nobody holding the page, an administrator does get edit mode")
                .isTrue();
        RemoteBagContentGUI adminsPage = openPageFor(admin, adminResult.getAccessMode());

        admin.setItemOnCursor(admin.getInventory().getItem(0));
        admin.getInventory().setItem(0, null);
        click(admin, adminsPage, ADMIN_TARGET_SLOT, ClickType.LEFT, InventoryAction.PLACE_ALL);
        click(admin, adminsPage, TOOLBAR_SAVE_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        close(admin, adminsPage);

        assertThat(countIn(storedPageOf(owner.getUniqueId()), Material.EMERALD))
                .as("control: this harness really can move an item into a page and persist it, so a "
                        + "'nothing moved' assertion elsewhere is not passing because the harness is inert")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("A second page of the same bag is still reclaimed while the first one is open")
    void aLockOnAPageNobodyIsLookingAtStillExpires() {
        // Presence is per page, so holding page 1 open must not make page 2's lock immortal. This is
        // the property that keeps the fix from turning one open page into a server-wide lock leak.
        openAsOwner();
        lockService.ownerOpen(owner.getUniqueId(), 2, owner);
        ageTheLockPastItsTimeout();
        ageTheLockPastItsTimeout(2);

        assertThat(lockService.canUpgradeToEdit(owner.getUniqueId(), PAGE))
                .as("page 1 is open, so its lock is not reclaimable")
                .isFalse();
        assertThat(lockService.canUpgradeToEdit(owner.getUniqueId(), 2))
                .as("page 2 is not open, so its lock times out as before")
                .isTrue();
    }

    @Test
    @DisplayName("Quitting releases the lock even though the page was open when the player left")
    void quittingReleasesTheLockOfAnOpenPage() {
        openAsOwner();
        ageTheLockPastItsTimeout();
        assertThat(lockService.canUpgradeToEdit(owner.getUniqueId(), PAGE))
                .as("precondition: while the owner is present the lock is held")
                .isFalse();

        // What the module's PlayerQuitEvent listener does. Also checked: once the player is offline,
        // the presence probe cannot find their page either, so the timeout would reclaim the lock on
        // its own even if this call were missed.
        lockService.releaseAll(owner.getUniqueId());

        assertThat(lockService.isLocked(owner.getUniqueId(), PAGE))
                .as("a quit must not leave an immortal lock behind")
                .isFalse();
    }

    @Test
    @DisplayName("A lock whose holder is offline is reclaimed, so a crash cannot leave it immortal")
    void anOfflineHoldersLockIsReclaimed() {
        // The page is registered with the GUI library and then the holder disappears without a clean
        // close -- a crash, a dropped connection. The presence probe asks the live server whether the
        // holder is online, so there is nothing left for it to find and no separate backstop needed.
        openAsOwner();
        ageTheLockPastItsTimeout();
        owner.disconnect();

        assertThat(RemoteBagContentGUI.isPageOpenBy(owner.getUniqueId(), PAGE, owner.getUniqueId()))
                .as("an offline holder is not present, whatever the library's page map still says")
                .isFalse();
        assertThat(lockService.canUpgradeToEdit(owner.getUniqueId(), PAGE))
                .as("so the timeout reclaims the lock as it always did")
                .isTrue();
    }

    // ==================== Harness ====================

    private void seedOwnerPageWithOneDiamond() {
        ItemStack[] page = new ItemStack[45];
        page[OWNER_SLOT] = new ItemStack(Material.DIAMOND);
        bagService.setBagPage(owner.getUniqueId(), PAGE, page);
        bagService.saveBag(owner.getUniqueId());
        bagService.clearCache(owner.getUniqueId());
    }

    private RemoteBagContentGUI openAsOwner() {
        BagOpenResult result = lockService.ownerOpen(owner.getUniqueId(), PAGE, owner);
        assertThat(result.isEditMode())
                .as("precondition: the owner opens their own page in edit mode")
                .isTrue();
        return openPageFor(owner, result.getAccessMode());
    }

    private RemoteBagContentGUI openPageFor(PlayerMock viewer,
                                            com.ultikits.plugins.remotebag.enums.AccessMode mode) {
        RemoteBagContentGUI page = new RemoteBagContentGUI(viewer, mockPlugin, owner.getUniqueId(),
                PAGE, bagService, lockService, config, mode);
        page.open();
        Bukkit.getPluginManager().callEvent(new InventoryOpenEvent(viewer.getOpenInventory()));
        return page;
    }

    private void ageTheLockPastItsTimeout() {
        ageTheLockPastItsTimeout(PAGE);
    }

    @SuppressWarnings("unchecked")
    private void ageTheLockPastItsTimeout(int pageNum) {
        try {
            Map<String, BagLockInfo> locks =
                    (Map<String, BagLockInfo>) UltiRemoteBagTestHelper.getField(lockService, "locks");
            String key = owner.getUniqueId() + ":" + pageNum;
            BagLockInfo current = locks.get(key);
            assertThat(current).as("precondition: a lock exists to age").isNotNull();
            locks.put(key, BagLockInfo.builder()
                    .holderUuid(current.getHolderUuid())
                    .holderName(current.getHolderName())
                    .lockType(current.getLockType())
                    .acquiredAt(System.currentTimeMillis() - (LOCK_TIMEOUT_SECONDS * 1000L + 1000L))
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("could not age the lock", e);
        }
    }

    private void click(PlayerMock clicker, RemoteBagContentGUI page, int rawSlot, ClickType type,
                       InventoryAction action) {
        InventoryView view = clicker.getOpenInventory();
        InventoryType.SlotType slotType = rawSlot < page.getInventory().getSize()
                ? InventoryType.SlotType.CONTAINER
                : InventoryType.SlotType.QUICKBAR;
        InventoryClickEvent event = new InventoryClickEvent(view, slotType, rawSlot, type, action);
        Bukkit.getPluginManager().callEvent(event);
        applyVanillaEffect(clicker, event);
    }

    /**
     * Performs the vanilla server-side effect of a click the plugin left uncancelled. Only the two
     * actions this scenario uses are modelled; anything else fails loudly, so a case added later
     * cannot assert "the item did not move" against an effect that was never implemented.
     */
    private void applyVanillaEffect(PlayerMock clicker, InventoryClickEvent event) {
        if (event.isCancelled()) {
            return;
        }
        InventoryView view = event.getView();
        int rawSlot = event.getRawSlot();
        switch (event.getAction()) {
            case PLACE_ALL: {
                view.setItem(rawSlot, clicker.getItemOnCursor());
                clicker.setItemOnCursor(null);
                break;
            }
            case PICKUP_ALL: {
                clicker.setItemOnCursor(view.getItem(rawSlot));
                view.setItem(rawSlot, null);
                break;
            }
            default:
                throw new IllegalStateException(
                        "this harness models no vanilla effect for " + event.getAction());
        }
    }

    private void close(PlayerMock viewer, RemoteBagContentGUI page) {
        Bukkit.getPluginManager().callEvent(new InventoryCloseEvent(viewer.getOpenInventory()));
        assertThat(page.isClosed()).as("the page registered the close").isTrue();
    }

    /** A cursor item is still that player's; fold it into their inventory before totalling. */
    private void returnCursorToInventory(PlayerMock player) {
        ItemStack onCursor = player.getItemOnCursor();
        if (onCursor != null && onCursor.getType() != Material.AIR) {
            player.getInventory().addItem(onCursor);
            player.setItemOnCursor(null);
        }
    }

    /**
     * Re-reads the owner's page from the store through {@code loadBagIfNeeded}, so the number counted
     * is what actually persisted rather than what the cache happens to hold. Dropping the cache first
     * makes the cache and the row the same number, which is why they are not counted separately.
     */
    private ItemStack[] storedPageOf(UUID playerUuid) {
        bagService.clearCache(playerUuid);
        bagService.loadBagIfNeeded(playerUuid);
        ItemStack[] page = bagService.getBagPage(playerUuid, PAGE);
        return page == null ? new ItemStack[0] : page;
    }

    private int totalDiamonds() {
        return totalOf(Material.DIAMOND);
    }

    private int totalEmeralds() {
        return totalOf(Material.EMERALD);
    }

    /**
     * Every place an item can be once the scenario has finished: the persisted page, both players'
     * inventories and both players' cursors. Open page inventories are deliberately not counted --
     * they are a view of pending edits, and counting them alongside the row they were written to
     * would double-count a persisted item.
     */
    private int totalOf(Material material) {
        return countIn(storedPageOf(owner.getUniqueId()), material)
                + countIn(storedPageOf(admin.getUniqueId()), material)
                + countIn(owner.getInventory(), material)
                + countIn(admin.getInventory(), material)
                + countCursor(owner, material)
                + countCursor(admin, material);
    }

    private int countIn(ItemStack[] contents, Material material) {
        int total = 0;
        for (ItemStack item : contents) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private int countIn(Inventory inventory, Material material) {
        return countIn(inventory.getContents(), material);
    }

    private int countCursor(PlayerMock player, Material material) {
        ItemStack onCursor = player.getItemOnCursor();
        return onCursor != null && onCursor.getType() == material ? onCursor.getAmount() : 0;
    }
}
