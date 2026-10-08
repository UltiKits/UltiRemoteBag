package com.ultikits.plugins.remotebag.commands;

import com.ultikits.plugins.remotebag.MockBukkitSupport;
import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.enums.AccessMode;
import com.ultikits.plugins.remotebag.gui.RemoteBagContentGUI;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.InMemoryRemoteBagStore;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import mc.obliviate.inventory.InventoryAPI;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.HandlerList;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * {@code /bag save} against a content page that is still open
 * (<a href="https://github.com/UltiKits/UltiRemoteBag/issues/22">UltiRemoteBag#22</a>).
 *
 * <h2>What makes a vacuous pass impossible here</h2>
 * The service under test is a real {@link RemoteBagService} over a real in-memory
 * {@link InMemoryRemoteBagStore}, not a mock, so nothing here can be satisfied by an interaction
 * that was merely recorded: every assertion reads the row the service actually wrote, and the
 * round-trip case reads it back through {@code loadBagIfNeeded} after the cache has been cleared,
 * so the stored YAML has to deserialize into the same slot.
 * <p>
 * {@link #savingFlushesTheOpenEditPage()} is the control for the store itself: it proves that this
 * harness's {@code /bag save} does write a row. Without it, "no row was written" could not be
 * distinguished from a store that never records anything at all.
 * <p>
 * Since UltiKits/UltiRemoteBag#54 (maintainer decision 2026-10-06 00:04) nothing is written from the
 * cache: with no page open, {@code /bag save} writes nothing, because every change was written when it
 * was made, and a cached copy written now could overwrite a page another server changed since. The
 * cases that asserted a write from the cache assert that instead.
 */
@DisplayName("/bag save with a content page still open (UltiRemoteBag#22)")
class BagSaveOpenPageTest {

    private static final int PAGE = 1;
    private static final int CONTENT_SLOT = 0;

    private ServerMock server;
    private PlayerMock player;
    private InventoryAPI inventoryApi;

    private BagCommand command;
    private RemoteBagService bagService;
    private BagLockService lockService;
    private RemoteBagConfig config;
    private InMemoryRemoteBagStore store;
    private UltiToolsPlugin mockPlugin;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkitSupport.bootstrapLiveServer();
        UltiRemoteBagTestHelper.setUp();

        inventoryApi = new InventoryAPI(MockBukkit.createMockPlugin("ObliviateHost"));
        inventoryApi.init();

        store = new InMemoryRemoteBagStore();
        config = UltiRemoteBagTestHelper.createDefaultConfig();

        mockPlugin = mock(UltiToolsPlugin.class);
        lenient().when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(mockPlugin.getLogger()).thenReturn(mock(PluginLogger.class));

        bagService = new RemoteBagService(mockPlugin, config);
        UltiRemoteBagTestHelper.setField(bagService, "dataOperator", store);

        // A REAL lock service, not a mock. The page's save path now asks it for the live access mode
        // before writing, and with no lock held it answers EDIT -- the ordinary benign case, which
        // must keep saving. A mock would answer null there and make every case below refuse, so the
        // real service is what keeps these cases about /bag save rather than about the stub.
        lockService = new BagLockService();
        UltiRemoteBagTestHelper.setField(lockService, "plugin", mockPlugin);
        // Tolerant: the config field arrives with UltiKits/UltiRemoteBag#19's fix. See
        // UltiRemoteBagTestHelper#setFieldIfPresent.
        UltiRemoteBagTestHelper.setFieldIfPresent(lockService, "config", config);
        command = new BagCommand(mockPlugin, bagService, lockService, config);

        player = server.addPlayer("Owner");
    }

    @AfterEach
    void tearDown() throws Exception {
        HandlerList.unregisterAll(inventoryApi.getListener());
        UltiRemoteBagTestHelper.tearDown();
        MockBukkitSupport.safeUnmock();
    }

    @Test
    @DisplayName("An item placed into the open page is stored by /bag save, without closing the page")
    void savingFlushesTheOpenEditPage() {
        RemoteBagContentGUI page = openEditPage();
        page.getInventory().setItem(CONTENT_SLOT, new ItemStack(Material.DIAMOND));
        assertThat(store.storedContents(player.getUniqueId().toString(), PAGE))
                .as("precondition: nothing is stored for this page yet")
                .isNull();

        command.saveBag(player);

        assertThat(store.storedContents(player.getUniqueId().toString(), PAGE))
                .as("the just-placed diamond reached the stored row")
                .contains("minecraft:diamond");
        assertThat(page.isClosed())
                .as("the page must not have been closed to achieve this")
                .isFalse();
    }

    @Test
    @DisplayName("A flushing /bag save persists each page once, not twice")
    void aFlushingSaveDoesNotPersistEveryPageTwice() {
        // flushOpenEditPage -> saveCurrentContents already ends in saveBag(ownerUuid), and the command
        // then called saveBag(player) again. For a sender whose own page was flushed those are the same
        // UUID, so every cached page was re-queried, re-serialized and re-updated a second time and
        // last_updated was written twice. Invisible in the stored contents -- the second write stores
        // the same bytes -- so the store counts its updates.
        // An existing row, so the write takes the update branch rather than the insert branch; stored
        // before the page opens, which is when the page reads what its save is conditioned on.
        store.seed(player.getUniqueId().toString(), PAGE, "");
        RemoteBagContentGUI page = openEditPage();
        page.getInventory().setItem(CONTENT_SLOT, new ItemStack(Material.DIAMOND));
        int updatesBefore = store.updateCount();

        command.saveBag(player);

        assertThat(store.updateCount() - updatesBefore)
                .as("one /bag save must update the page's row once")
                .isEqualTo(1);
        assertThat(store.storedContents(player.getUniqueId().toString(), PAGE))
                .as("control: the one update it did perform is the flush, so the item really landed")
                .contains("minecraft:diamond");
    }

    @Test
    @DisplayName("The stored page deserializes back into the same slot after the cache is dropped")
    void theFlushedPageSurvivesACacheDrop() {
        RemoteBagContentGUI page = openEditPage();
        page.getInventory().setItem(CONTENT_SLOT, new ItemStack(Material.DIAMOND));

        command.saveBag(player);
        bagService.clearCache(player.getUniqueId());
        bagService.loadBagIfNeeded(player.getUniqueId());

        ItemStack[] reloaded = bagService.getBagPage(player.getUniqueId(), PAGE);
        assertThat(reloaded).as("the page was re-read from the store").isNotNull();
        assertThat(reloaded[CONTENT_SLOT])
                .as("the diamond came back out of storage in the same slot")
                .isEqualTo(new ItemStack(Material.DIAMOND));
    }

    @Test
    @DisplayName("A read-only page open on the same player is not written back by /bag save")
    void savingDoesNotFlushAReadOnlyPage() {
        RemoteBagContentGUI page = openPage(AccessMode.READ_ONLY);
        // Whatever ends up in a read-only page's live inventory is somebody else's bag being
        // looked at, so /bag save must not copy it into this sender's own pages.
        page.getInventory().setItem(CONTENT_SLOT, new ItemStack(Material.DIAMOND));

        command.saveBag(player);

        assertThat(store.storedContents(player.getUniqueId().toString(), PAGE))
                .as("a read-only page's contents must not be persisted")
                .isNull();
    }

    @Test
    @DisplayName("Another player's open page is untouched when this player runs /bag save")
    void savingDoesNotFlushAnotherPlayersOpenPage() {
        PlayerMock other = server.addPlayer("Other");
        RemoteBagContentGUI otherPage = new RemoteBagContentGUI(other, mockPlugin,
                other.getUniqueId(), PAGE, bagService, lockService, config, AccessMode.EDIT);
        otherPage.open();
        Bukkit.getPluginManager().callEvent(new InventoryOpenEvent(other.getOpenInventory()));
        otherPage.getInventory().setItem(CONTENT_SLOT, new ItemStack(Material.DIAMOND));

        command.saveBag(player);

        assertThat(store.storedContents(other.getUniqueId().toString(), PAGE))
                .as("running /bag save as one player must not write another player's open page")
                .isNull();
    }

    @Test
    @DisplayName("Another player's bag, opened by this sender as an admin, is not written by /bag save")
    void savingDoesNotFlushAnotherPlayersBagOpenedByThisViewer() {
        // The /bag see path constructs the page with the ADMIN as its viewer and the TARGET as its
        // owner (BagCommand#openAdminBagPage), and BagLockService#adminOpen hands back EDIT mode when
        // the target holds no lock. Comparing the sender against the page's viewer is therefore
        // tautologically true here, and comparing it against the page's OWNER is what the command's
        // "save my bag" semantics actually require. Raised as a P2 on pull request #34.
        PlayerMock target = server.addPlayer("Target");
        RemoteBagContentGUI targetsPage = new RemoteBagContentGUI(player, mockPlugin,
                target.getUniqueId(), PAGE, bagService, lockService, config, AccessMode.EDIT);
        targetsPage.open();
        Bukkit.getPluginManager().callEvent(new InventoryOpenEvent(player.getOpenInventory()));
        targetsPage.getInventory().setItem(CONTENT_SLOT, new ItemStack(Material.DIAMOND));

        // Control: the sender does have a stored, cached page of their own, so a flush that wrongly
        // treated this page as the sender's would be visible as a write over it, not as silence.
        ItemStack[] own = new ItemStack[45];
        own[CONTENT_SLOT] = new ItemStack(Material.EMERALD);
        storeAndCache(own);

        command.saveBag(player);

        assertThat(store.storedContents(target.getUniqueId().toString(), PAGE))
                .as("/bag save must not write the bag of the player whose page the sender is viewing")
                .isNull();
        assertThat(store.storedContents(player.getUniqueId().toString(), PAGE))
                .as("control: the sender's own stored page is untouched by the same command")
                .contains("minecraft:emerald")
                .doesNotContain("minecraft:diamond");
    }

    @Test
    @DisplayName("An admin's open view of the sender's own bag is not flushed by the sender's /bag save")
    void savingDoesNotFlushSomebodyElsesViewOfTheSendersBag() {
        // This is what the sender-keyed lookup is for, as distinct from the owner comparison. Here the
        // page's OWNER is the sender, so the owner check passes; only the fact that the page is not the
        // SENDER's open page keeps it out. An implementation that scanned for "any open content page of
        // this bag" would let one player's /bag save persist another player's half-finished edits at an
        // arbitrary moment.
        PlayerMock admin = server.addPlayer("Admin");
        RemoteBagContentGUI adminsViewOfOurBag = new RemoteBagContentGUI(admin, mockPlugin,
                player.getUniqueId(), PAGE, bagService, lockService, config, AccessMode.EDIT);
        adminsViewOfOurBag.open();
        Bukkit.getPluginManager().callEvent(new InventoryOpenEvent(admin.getOpenInventory()));
        adminsViewOfOurBag.getInventory().setItem(CONTENT_SLOT, new ItemStack(Material.DIAMOND));

        // The sender has no page open and a stored, cached page of their own.
        ItemStack[] own = new ItemStack[45];
        own[CONTENT_SLOT] = new ItemStack(Material.EMERALD);
        storeAndCache(own);

        command.saveBag(player);

        String stored = store.storedContents(player.getUniqueId().toString(), PAGE);
        assertThat(stored)
                .as("the sender's own stored page is what is stored")
                .contains("minecraft:emerald");
        assertThat(stored)
                .as("the admin's in-progress view of this bag must not have been persisted by the sender")
                .doesNotContain("minecraft:diamond");
    }

    @Test
    @DisplayName("With nothing cached at all, /bag save says so instead of confirming a save")
    void savingWithAnEmptyCacheReportsThatNothingWasSaved() {
        // The real service, not a stub: saveBag returns false for a player with no cached pages -- a
        // fresh login that has not opened a page -- and the command printed bag_saved_manually anyway,
        // which is what made the ultiremotebag.bag.save row's "or run this from a fresh login"
        // precondition assert against correct code. A mocked service cannot pin this: the behaviour
        // under test is the service's own answer.
        assertThat(store.rows()).as("precondition: the store is empty").isEmpty();

        command.saveBag(player);

        String messages = messagesSentTo(player);
        assertThat(messages)
                .as("the command must not claim a save it did not perform")
                .contains("msg_nothing_to_save")
                .doesNotContain("bag_saved_manually");
        assertThat(store.rows())
                .as("and it really wrote nothing, so the message is not merely pessimistic")
                .isEmpty();
    }

    @Test
    @DisplayName("Control: with a page cached, the same command does confirm the save")
    void savingWithACachedPageConfirmsTheSave() {
        // Without this, the case above could pass because the command never reports success at all.
        ItemStack[] cached = new ItemStack[45];
        cached[CONTENT_SLOT] = new ItemStack(Material.DIAMOND);
        storeAndCache(cached);

        command.saveBag(player);

        assertThat(messagesSentTo(player))
                .as("control: with the sender's pages held (and stored), the save is confirmed")
                .contains("bag_saved_manually");
    }

    @Test
    @DisplayName("With no page open, /bag save writes nothing from the cache: a cached copy is never written over the stored page (UltiRemoteBag#54)")
    void savingWithNoPageOpenWritesNothingFromTheCache() {
        // The stored page is empty; this server's cache holds an older copy with a diamond in it (the
        // page was emptied on another server since this one read it).
        store.seed(player.getUniqueId().toString(), PAGE, "");
        ItemStack[] stale = new ItemStack[45];
        stale[CONTENT_SLOT] = new ItemStack(Material.DIAMOND);
        UltiRemoteBagTestHelper.cachePage(bagService, player.getUniqueId(), PAGE, stale);
        int updatesBefore = store.updateCount();

        command.saveBag(player);

        assertThat(store.storedContents(player.getUniqueId().toString(), PAGE))
                .as("the cached copy is not written over the stored page")
                .doesNotContain("minecraft:diamond");
        assertThat(store.updateCount()).as("nothing was written").isEqualTo(updatesBefore);
    }

    /** Stores a page for the sender and puts it in the read cache, as a read of it would. */
    private void storeAndCache(ItemStack[] items) {
        bagService.savePage(player.getUniqueId(), PAGE, items, bagService.readPage(player.getUniqueId(), PAGE));
        UltiRemoteBagTestHelper.cachePage(bagService, player.getUniqueId(), PAGE, items);
    }

    /** Every chat line sent to this player since the last read, joined; i18n echoes its key. */
    private String messagesSentTo(PlayerMock target) {
        StringBuilder all = new StringBuilder();
        String next;
        while ((next = target.nextMessage()) != null) {
            all.append(next).append('\n');
        }
        return all.toString();
    }

    private RemoteBagContentGUI openEditPage() {
        return openPage(AccessMode.EDIT);
    }

    private RemoteBagContentGUI openPage(AccessMode mode) {
        RemoteBagContentGUI page = new RemoteBagContentGUI(player, mockPlugin,
                player.getUniqueId(), PAGE, bagService, lockService, config, mode);
        page.open();
        Bukkit.getPluginManager().callEvent(new InventoryOpenEvent(player.getOpenInventory()));
        return page;
    }
}
