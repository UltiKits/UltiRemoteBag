package com.ultikits.plugins.remotebag.listener;

import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@DisplayName("BagListener Tests")
class BagListenerTest {

    private BagListener listener;
    private RemoteBagService bagService;
    private BagLockService lockService;
    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiRemoteBagTestHelper.setUp();

        bagService = mock(RemoteBagService.class);
        lockService = mock(BagLockService.class);

        listener = new BagListener(bagService, lockService);

        playerUuid = UUID.randomUUID();
        player = UltiRemoteBagTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiRemoteBagTestHelper.tearDown();
    }

    // ==================== onPlayerQuit ====================

    @Nested
    @DisplayName("onPlayerQuit")
    class OnPlayerQuit {

        @Test
        @DisplayName("Should release all locks")
        void releasesAllLocks() {
            PlayerQuitEvent event = mock(PlayerQuitEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onPlayerQuit(event);

            verify(lockService).releaseAll(playerUuid);
        }

        @Test
        @DisplayName("Writes nothing from the cache (UltiRemoteBag#54)")
        void writesNothingFromTheCache() {
            // Every change was written when it was made; a cached copy written at quit could overwrite a
            // page another server changed since (maintainer decision 2026-10-06 00:04). An edit page the
            // player still has open is saved by the page itself (BagListener javadoc).
            PlayerQuitEvent event = mock(PlayerQuitEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onPlayerQuit(event);

            verify(bagService, never()).savePage(any(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("Should clear cache")
        void clearsCache() {
            PlayerQuitEvent event = mock(PlayerQuitEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onPlayerQuit(event);

            verify(bagService).clearCache(playerUuid);
        }

        @Test
        @DisplayName("Should execute all cleanup in order")
        void executesAllCleanup() {
            PlayerQuitEvent event = mock(PlayerQuitEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onPlayerQuit(event);

            // The locks are released, then the cache entry is dropped
            org.mockito.InOrder order = inOrder(lockService, bagService);
            order.verify(lockService).releaseAll(playerUuid);
            order.verify(bagService).clearCache(playerUuid);
        }

        @Test
        @DisplayName("F8 (UltiRemoteBag#54, gate 1): the locks, claims and cache are released even if saving the open page throws")
        void releasesEvenIfTheQuitSaveThrows() throws Exception {
            PlayerQuitEvent event = mock(PlayerQuitEvent.class);
            when(event.getPlayer()).thenReturn(player);
            mc.obliviate.inventory.InventoryAPI broken = mock(mc.obliviate.inventory.InventoryAPI.class);
            when(broken.getPlayersCurrentGui(any())).thenThrow(new IllegalStateException("test: the quit save fails"));
            Object before = UltiRemoteBagTestHelper.getStaticField(mc.obliviate.inventory.InventoryAPI.class, "instance");
            UltiRemoteBagTestHelper.setStaticField(mc.obliviate.inventory.InventoryAPI.class, "instance", broken);
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> listener.onPlayerQuit(event))
                        .isInstanceOf(IllegalStateException.class);
            } finally {
                UltiRemoteBagTestHelper.setStaticField(mc.obliviate.inventory.InventoryAPI.class, "instance", before);
            }

            verify(lockService).releaseAll(playerUuid);
            verify(bagService).clearCache(playerUuid);
        }
    }
}
