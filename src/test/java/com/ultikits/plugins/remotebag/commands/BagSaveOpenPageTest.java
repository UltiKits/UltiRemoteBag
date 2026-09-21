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
 * {@link #savingWithNoPageOpenStillPersistsTheCache()} is the control for the store itself: it
 * proves that this harness's {@code /bag save} does write a row when the cache already holds the
 * item. Without it, "the row now contains the diamond" could pass for the wrong reason — a store
 * that accepts anything — and "no row was written" (the pre-fix behaviour) could not be
 * distinguished from a store that never records anything at all.
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

        lockService = mock(BagLockService.class);
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

        // Control: the sender does have a page of their own in the cache, so a flush that wrongly
        // treated this page as the sender's would be visible as a write, not as silence.
        ItemStack[] own = new ItemStack[45];
        own[CONTENT_SLOT] = new ItemStack(Material.EMERALD);
        bagService.setBagPage(player.getUniqueId(), PAGE, own);

        command.saveBag(player);

        assertThat(store.storedContents(target.getUniqueId().toString(), PAGE))
                .as("/bag save must not write the bag of the player whose page the sender is viewing")
                .isNull();
        assertThat(store.storedContents(player.getUniqueId().toString(), PAGE))
                .as("control: the sender's own cached page was still persisted by the same command")
                .contains("minecraft:emerald");
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

        // The sender has no page open and a cached page of their own.
        ItemStack[] own = new ItemStack[45];
        own[CONTENT_SLOT] = new ItemStack(Material.EMERALD);
        bagService.setBagPage(player.getUniqueId(), PAGE, own);

        command.saveBag(player);

        String stored = store.storedContents(player.getUniqueId().toString(), PAGE);
        assertThat(stored)
                .as("the sender's own cached page is what was persisted")
                .contains("minecraft:emerald");
        assertThat(stored)
                .as("the admin's in-progress view of this bag must not have been persisted by the sender")
                .doesNotContain("minecraft:diamond");
    }

    @Test
    @DisplayName("With no page open, /bag save still persists what the cache already holds")
    void savingWithNoPageOpenStillPersistsTheCache() {
        ItemStack[] cached = new ItemStack[45];
        cached[CONTENT_SLOT] = new ItemStack(Material.DIAMOND);
        bagService.setBagPage(player.getUniqueId(), PAGE, cached);

        command.saveBag(player);

        assertThat(store.storedContents(player.getUniqueId().toString(), PAGE))
                .as("control: this harness's /bag save really does write a row")
                .contains("minecraft:diamond");
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
