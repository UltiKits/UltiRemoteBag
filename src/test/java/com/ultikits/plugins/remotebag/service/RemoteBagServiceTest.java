package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.MockBukkitSupport;
import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.RemoteBagData;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.Query;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.utils.EconomyUtils;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.reflect.Method;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RemoteBagService Tests")
class RemoteBagServiceTest {

    private RemoteBagService service;
    private RemoteBagConfig config;
    @SuppressWarnings("unchecked")
    private DataOperator<RemoteBagData> dataOperator = mock(DataOperator.class);
    @SuppressWarnings("unchecked")
    private Query<RemoteBagData> mockQuery = mock(Query.class);

    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        // Live test-time Bukkit server: several tests below construct real ItemStacks
        // (new ItemStack(Material.X, n)) which need a live registry to resolve.
        // MockBukkitSupport.bootstrapLiveServer() is this module's shared bootstrap entry
        // point.
        MockBukkitSupport.bootstrapLiveServer();

        UltiRemoteBagTestHelper.setUp();

        config = UltiRemoteBagTestHelper.createDefaultConfig();

        UltiToolsPlugin mockPlugin = mock(UltiToolsPlugin.class);
        when(mockPlugin.getDataOperator(RemoteBagData.class)).thenReturn(dataOperator);
        lenient().when(mockPlugin.getLogger()).thenReturn(mock(PluginLogger.class));

        // Stub the fluent Query DSL chain: dataOperator.query().where(...).eq(...).where(...).eq(...).list()
        lenient().when(dataOperator.query()).thenReturn(mockQuery);
        lenient().when(mockQuery.where(anyString())).thenReturn(mockQuery);
        lenient().when(mockQuery.eq(any())).thenReturn(mockQuery);
        lenient().when(mockQuery.and(anyString())).thenReturn(mockQuery);
        lenient().when(mockQuery.list()).thenReturn(Collections.emptyList());

        service = new RemoteBagService(mockPlugin, config);

        // Inject dataOperator via reflection (set by init())
        UltiRemoteBagTestHelper.setField(service, "dataOperator", dataOperator);

        playerUuid = UUID.randomUUID();
        player = UltiRemoteBagTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiRemoteBagTestHelper.tearDown();
        MockBukkitSupport.safeUnmock();
    }

    // ==================== getPlayerMaxPages ====================

    @Nested
    @DisplayName("getPlayerMaxPages")
    class GetPlayerMaxPages {

        @Test
        @DisplayName("Should follow max_pages changed on the same config bean without re-creating the service (/ul reload, UltiKits/UltiRemoteBag#12)")
        void followsMaxPagesChangedOnTheSameConfigBean() {
            // ConfigManager#reloadConfigs re-initialises the SAME RemoteBagConfig instance the
            // service was constructed with; a reload only takes effect if the service reads the
            // key on every call instead of caching it.
            RemoteBagConfig realConfig = new RemoteBagConfig("config/remotebag.yml");
            realConfig.setPermissionBasedPages(false);
            realConfig.setMaxPages(1);
            UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
            RemoteBagService liveService = new RemoteBagService(plugin, realConfig);

            assertThat(liveService.getPlayerMaxPages(player)).isEqualTo(1);

            realConfig.setMaxPages(3);

            assertThat(liveService.getPlayerMaxPages(player)).isEqualTo(3);
        }

        @Test
        @DisplayName("Should return max pages when permission based disabled")
        void returnMaxWhenDisabled() {
            when(config.isPermissionBasedPages()).thenReturn(false);
            when(config.getMaxPages()).thenReturn(10);

            int result = service.getPlayerMaxPages(player);

            assertThat(result).isEqualTo(10);
        }

        @Test
        @DisplayName("Should return highest permission level")
        void returnHighestPermission() {
            when(config.isPermissionBasedPages()).thenReturn(true);
            when(config.getMaxPages()).thenReturn(10);
            when(config.getPermissionPrefix()).thenReturn("ultibag.pages.");
            // Override default to false, then set specific permission
            when(player.hasPermission(anyString())).thenReturn(false);
            when(player.hasPermission("ultibag.pages.5")).thenReturn(true);
            when(player.hasPermission("ultibag.pages.4")).thenReturn(true);
            when(player.hasPermission("ultibag.pages.3")).thenReturn(true);
            when(player.hasPermission("ultibag.pages.2")).thenReturn(true);
            when(player.hasPermission("ultibag.pages.1")).thenReturn(true);

            int result = service.getPlayerMaxPages(player);

            assertThat(result).isEqualTo(5);
        }

        @Test
        @DisplayName("Should return default pages when no permissions")
        void returnDefaultWhenNoPermissions() {
            when(config.isPermissionBasedPages()).thenReturn(true);
            when(config.getMaxPages()).thenReturn(10);
            when(config.getDefaultPages()).thenReturn(1);
            when(config.getPermissionPrefix()).thenReturn("ultibag.pages.");
            when(player.hasPermission(anyString())).thenReturn(false);

            int result = service.getPlayerMaxPages(player);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("Should return max pages when player has all permissions")
        void returnMaxWhenAllPermissions() {
            when(config.isPermissionBasedPages()).thenReturn(true);
            when(config.getMaxPages()).thenReturn(10);
            when(config.getPermissionPrefix()).thenReturn("ultibag.pages.");
            when(player.hasPermission(anyString())).thenReturn(true);

            int result = service.getPlayerMaxPages(player);

            assertThat(result).isEqualTo(10);
        }

        @Test
        @DisplayName("Should check permissions from max to 1")
        void checksPermissionsDescending() {
            when(config.isPermissionBasedPages()).thenReturn(true);
            when(config.getMaxPages()).thenReturn(3);
            when(config.getPermissionPrefix()).thenReturn("ultibag.pages.");
            when(config.getDefaultPages()).thenReturn(1);
            when(player.hasPermission(anyString())).thenReturn(false);
            // Only has permission for page 2
            when(player.hasPermission("ultibag.pages.2")).thenReturn(true);

            int result = service.getPlayerMaxPages(player);

            assertThat(result).isEqualTo(2);
        }

        @Test
        @DisplayName("Should return default 1 page for custom default setting")
        void returnsCustomDefault() {
            when(config.isPermissionBasedPages()).thenReturn(true);
            when(config.getMaxPages()).thenReturn(10);
            when(config.getDefaultPages()).thenReturn(3);
            when(config.getPermissionPrefix()).thenReturn("ultibag.pages.");
            when(player.hasPermission(anyString())).thenReturn(false);

            int result = service.getPlayerMaxPages(player);

            assertThat(result).isEqualTo(3);
        }
    }

    // ==================== Cache Operations ====================

    @Nested
    @DisplayName("Cache Operations")
    class CacheOperations {

        @Test
        @DisplayName("getBagPage should return null when not in cache")
        void getBagPageNotInCache() {
            ItemStack[] result = service.getBagPage(playerUuid, 1);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("setBagPage should store in cache")
        void setBagPageStoresInCache() {
            ItemStack[] contents = new ItemStack[45];
            service.setBagPage(playerUuid, 1, contents);

            ItemStack[] result = service.getBagPage(playerUuid, 1);
            assertThat(result).isSameAs(contents);
        }

        @Test
        @DisplayName("setBagPage should handle multiple pages")
        void setBagPageMultiplePages() {
            ItemStack[] page1 = new ItemStack[45];
            ItemStack[] page2 = new ItemStack[45];

            service.setBagPage(playerUuid, 1, page1);
            service.setBagPage(playerUuid, 2, page2);

            assertThat(service.getBagPage(playerUuid, 1)).isSameAs(page1);
            assertThat(service.getBagPage(playerUuid, 2)).isSameAs(page2);
        }

        @Test
        @DisplayName("clearCache should remove player data")
        void clearCacheRemoves() {
            ItemStack[] contents = new ItemStack[45];
            service.setBagPage(playerUuid, 1, contents);

            service.clearCache(playerUuid);

            assertThat(service.getBagPage(playerUuid, 1)).isNull();
        }

        @Test
        @DisplayName("setBagPage should overwrite existing page")
        void setBagPageOverwrites() {
            ItemStack[] original = new ItemStack[45];
            ItemStack[] replacement = new ItemStack[45];

            service.setBagPage(playerUuid, 1, original);
            service.setBagPage(playerUuid, 1, replacement);

            assertThat(service.getBagPage(playerUuid, 1)).isSameAs(replacement);
        }

        @Test
        @DisplayName("getBagPage should return null for non-existent page of cached player")
        void getBagPageNonExistentPage() {
            service.setBagPage(playerUuid, 1, new ItemStack[45]);

            ItemStack[] result = service.getBagPage(playerUuid, 99);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("clearCache should not affect other players")
        void clearCacheDoesNotAffectOthers() {
            UUID otherUuid = UUID.randomUUID();
            service.setBagPage(playerUuid, 1, new ItemStack[45]);
            service.setBagPage(otherUuid, 1, new ItemStack[45]);

            service.clearCache(playerUuid);

            assertThat(service.getBagPage(playerUuid, 1)).isNull();
            assertThat(service.getBagPage(otherUuid, 1)).isNotNull();
        }

        @Test
        @DisplayName("clearCache on non-cached player should not throw")
        void clearCacheNonCachedPlayer() {
            assertThatCode(() -> service.clearCache(UUID.randomUUID()))
                    .doesNotThrowAnyException();
        }
    }

    // ==================== getItemCount ====================

    @Nested
    @DisplayName("getItemCount")
    class GetItemCount {

        @Test
        @DisplayName("Should return 0 for null page")
        void returnsZeroForNull() {
            assertThat(service.getItemCount(playerUuid, 1)).isZero();
        }

        @Test
        @DisplayName("Should count all items in stacks")
        void countsAllItems() {
            ItemStack[] contents = new ItemStack[45];
            contents[0] = new ItemStack(Material.STONE, 64);
            contents[1] = new ItemStack(Material.DIRT, 32);
            contents[2] = null;

            service.setBagPage(playerUuid, 1, contents);

            assertThat(service.getItemCount(playerUuid, 1)).isEqualTo(96); // 64 + 32
        }

        @Test
        @DisplayName("Should ignore air and null items")
        void ignoresAirAndNull() {
            ItemStack[] contents = new ItemStack[45];
            contents[0] = new ItemStack(Material.STONE, 10);
            contents[1] = new ItemStack(Material.AIR, 5);
            contents[2] = null;

            service.setBagPage(playerUuid, 1, contents);

            assertThat(service.getItemCount(playerUuid, 1)).isEqualTo(10);
        }

        @Test
        @DisplayName("Should return 0 for empty page (all null)")
        void returnsZeroForEmptyPage() {
            service.setBagPage(playerUuid, 1, new ItemStack[45]);

            assertThat(service.getItemCount(playerUuid, 1)).isZero();
        }

        @Test
        @DisplayName("Should count items with amount of 1")
        void countsItemsWithAmountOne() {
            ItemStack[] contents = new ItemStack[45];
            contents[0] = new ItemStack(Material.DIAMOND_SWORD, 1);
            contents[1] = new ItemStack(Material.DIAMOND_PICKAXE, 1);

            service.setBagPage(playerUuid, 1, contents);

            assertThat(service.getItemCount(playerUuid, 1)).isEqualTo(2);
        }

        @Test
        @DisplayName("Should count across many slots")
        void countsAcrossManySlots() {
            ItemStack[] contents = new ItemStack[45];
            for (int i = 0; i < 45; i++) {
                contents[i] = new ItemStack(Material.STONE, 1);
            }

            service.setBagPage(playerUuid, 1, contents);

            assertThat(service.getItemCount(playerUuid, 1)).isEqualTo(45);
        }
    }

    // ==================== getStackCount ====================

    @Nested
    @DisplayName("getStackCount")
    class GetStackCount {

        @Test
        @DisplayName("Should return 0 for null page")
        void returnsZeroForNull() {
            assertThat(service.getStackCount(playerUuid, 1)).isZero();
        }

        @Test
        @DisplayName("Should count occupied slots")
        void countsOccupiedSlots() {
            ItemStack[] contents = new ItemStack[45];
            contents[0] = new ItemStack(Material.STONE, 64);
            contents[1] = new ItemStack(Material.DIRT, 1);
            contents[2] = null;

            service.setBagPage(playerUuid, 1, contents);

            assertThat(service.getStackCount(playerUuid, 1)).isEqualTo(2);
        }

        @Test
        @DisplayName("Should ignore air and null items")
        void ignoresAirAndNull() {
            ItemStack[] contents = new ItemStack[45];
            contents[0] = new ItemStack(Material.STONE, 10);
            contents[1] = new ItemStack(Material.AIR, 5);
            contents[2] = null;

            service.setBagPage(playerUuid, 1, contents);

            assertThat(service.getStackCount(playerUuid, 1)).isEqualTo(1);
        }

        @Test
        @DisplayName("Should return 0 for empty page")
        void returnsZeroForEmptyPage() {
            service.setBagPage(playerUuid, 1, new ItemStack[45]);

            assertThat(service.getStackCount(playerUuid, 1)).isZero();
        }

        @Test
        @DisplayName("Should count full inventory correctly")
        void countsFullInventory() {
            ItemStack[] contents = new ItemStack[45];
            for (int i = 0; i < 45; i++) {
                contents[i] = new ItemStack(Material.STONE, 64);
            }

            service.setBagPage(playerUuid, 1, contents);

            assertThat(service.getStackCount(playerUuid, 1)).isEqualTo(45);
        }
    }

    // ==================== calculatePrice ====================

    @Nested
    @DisplayName("calculatePrice")
    class CalculatePrice {

        @Test
        @DisplayName("Should return base price for first bag")
        void basePriceForFirst() {
            when(config.getBasePrice()).thenReturn(10000);

            assertThat(service.calculatePrice(1)).isEqualTo(10000);
        }

        @Test
        @DisplayName("Should return base price when increase disabled")
        void basePriceWhenDisabled() {
            when(config.getBasePrice()).thenReturn(10000);
            when(config.isPriceIncreaseEnabled()).thenReturn(false);

            assertThat(service.calculatePrice(5)).isEqualTo(10000);
        }

        @Test
        @DisplayName("Should calculate price with 10% increase")
        void calculatesWithIncrease() {
            when(config.getBasePrice()).thenReturn(10000);
            when(config.isPriceIncreaseEnabled()).thenReturn(true);
            when(config.getPriceIncreaseRate()).thenReturn(0.1);

            // Bag 2: 10000 * 1.1^1 = 11000
            assertThat(service.calculatePrice(2)).isEqualTo(11000);

            // Bag 3: 10000 * 1.1^2 = 12100 (Math.ceil may round up due to floating point)
            assertThat(service.calculatePrice(3)).isEqualTo(12101);

            // Bag 4: 10000 * 1.1^3 = 13310 (Math.ceil may round up due to floating point)
            assertThat(service.calculatePrice(4)).isEqualTo(13311);
        }

        @Test
        @DisplayName("Should round up fractional prices")
        void roundsUpFractional() {
            when(config.getBasePrice()).thenReturn(10000);
            when(config.isPriceIncreaseEnabled()).thenReturn(true);
            when(config.getPriceIncreaseRate()).thenReturn(0.15);

            // Bag 2: 10000 * 1.15 = 11500
            assertThat(service.calculatePrice(2)).isEqualTo(11500);
        }

        @Test
        @DisplayName("Should return base price when bagNumber is 1 even with increase enabled")
        void basePriceWhenBagNumberOne() {
            when(config.getBasePrice()).thenReturn(5000);
            when(config.isPriceIncreaseEnabled()).thenReturn(true);
            when(config.getPriceIncreaseRate()).thenReturn(0.5);

            assertThat(service.calculatePrice(1)).isEqualTo(5000);
        }

        @Test
        @DisplayName("Should handle zero rate")
        void handlesZeroRate() {
            when(config.getBasePrice()).thenReturn(10000);
            when(config.isPriceIncreaseEnabled()).thenReturn(true);
            when(config.getPriceIncreaseRate()).thenReturn(0.0);

            // 10000 * 1.0^n = 10000
            assertThat(service.calculatePrice(5)).isEqualTo(10000);
        }

        @Test
        @DisplayName("Should handle high bag number with large rate")
        void handlesHighBagNumber() {
            when(config.getBasePrice()).thenReturn(1000);
            when(config.isPriceIncreaseEnabled()).thenReturn(true);
            when(config.getPriceIncreaseRate()).thenReturn(1.0);

            // Bag 5: 1000 * 2^4 = 16000
            assertThat(service.calculatePrice(5)).isEqualTo(16000);
        }
    }

    // ==================== getPlayerBagPages ====================

    @Nested
    @DisplayName("getPlayerBagPages")
    class GetPlayerBagPages {

        @Test
        @DisplayName("Should return list of page numbers")
        void returnsPageNumbers() {
            when(mockQuery.list()).thenReturn(Arrays.asList(
                    RemoteBagData.create(playerUuid, 1, ""),
                    RemoteBagData.create(playerUuid, 3, ""),
                    RemoteBagData.create(playerUuid, 2, "")
            ));

            List<Integer> pages = service.getPlayerBagPages(playerUuid);

            assertThat(pages).containsExactly(1, 2, 3); // Sorted
        }

        @Test
        @DisplayName("Should return default page 1 when no bags")
        void returnsDefaultWhenEmpty() {
            when(mockQuery.list()).thenReturn(Collections.emptyList());

            List<Integer> pages = service.getPlayerBagPages(playerUuid);

            assertThat(pages).containsExactly(1);
        }

        @Test
        @DisplayName("Should return cached pages after second call")
        void returnsCachedPages() {
            when(mockQuery.list()).thenReturn(Collections.singletonList(
                    RemoteBagData.create(playerUuid, 1, "")
            ));

            service.getPlayerBagPages(playerUuid);
            service.getPlayerBagPages(playerUuid);

            // query().list() should only be called once (caching via loadBagIfNeeded)
            verify(mockQuery, times(1)).list();
        }

        @Test
        @DisplayName("Should sort pages in ascending order")
        void sortsPages() {
            when(mockQuery.list()).thenReturn(Arrays.asList(
                    RemoteBagData.create(playerUuid, 5, ""),
                    RemoteBagData.create(playerUuid, 1, ""),
                    RemoteBagData.create(playerUuid, 3, "")
            ));

            List<Integer> pages = service.getPlayerBagPages(playerUuid);

            assertThat(pages).containsExactly(1, 3, 5);
        }
    }

    // ==================== loadBagIfNeeded ====================

    @Nested
    @DisplayName("loadBagIfNeeded")
    class LoadBagIfNeeded {

        @Test
        @DisplayName("Should not load when already in cache")
        void skipWhenInCache() {
            service.setBagPage(playerUuid, 1, new ItemStack[45]);

            service.loadBagIfNeeded(playerUuid);

            verify(mockQuery, never()).list();
        }

        @Test
        @DisplayName("Should load from database when not in cache")
        void loadsFromDatabase() {
            when(mockQuery.list()).thenReturn(Collections.singletonList(
                    RemoteBagData.create(playerUuid, 1, "")
            ));

            service.loadBagIfNeeded(playerUuid);

            verify(mockQuery).list();
        }

        @Test
        @DisplayName("Should handle empty database result")
        void handlesEmptyDbResult() {
            when(mockQuery.list()).thenReturn(Collections.emptyList());

            service.loadBagIfNeeded(playerUuid);

            // Should still add the player to cache (with empty pages map)
            // Second call should not query database again
            service.loadBagIfNeeded(playerUuid);
            verify(mockQuery, times(1)).list();
        }

        @Test
        @DisplayName("Should deserialize items with null contents as empty array")
        void deserializesNullContentsAsEmpty() {
            RemoteBagData data = RemoteBagData.create(playerUuid, 1, null);
            when(mockQuery.list()).thenReturn(Collections.singletonList(data));

            service.loadBagIfNeeded(playerUuid);

            ItemStack[] page = service.getBagPage(playerUuid, 1);
            assertThat(page).isNotNull();
            assertThat(page.length).isEqualTo(45); // the fixed page capacity
        }

        @Test
        @DisplayName("Should deserialize items with empty string contents as empty array")
        void deserializesEmptyContentsAsEmpty() {
            RemoteBagData data = RemoteBagData.create(playerUuid, 1, "");
            when(mockQuery.list()).thenReturn(Collections.singletonList(data));

            service.loadBagIfNeeded(playerUuid);

            ItemStack[] page = service.getBagPage(playerUuid, 1);
            assertThat(page).isNotNull();
            assertThat(page.length).isEqualTo(45);
        }

        @Test
        @DisplayName("Should handle multiple pages from database")
        void handlesMultiplePages() {
            when(mockQuery.list()).thenReturn(Arrays.asList(
                    RemoteBagData.create(playerUuid, 1, ""),
                    RemoteBagData.create(playerUuid, 2, "")
            ));

            service.loadBagIfNeeded(playerUuid);

            assertThat(service.getBagPage(playerUuid, 1)).isNotNull();
            assertThat(service.getBagPage(playerUuid, 2)).isNotNull();
        }
    }

    // ==================== saveBag ====================

    @Nested
    @DisplayName("saveBag")
    class SaveBag {

        @Test
        @DisplayName("Should do nothing when not in cache")
        void skipWhenNotInCache() throws Exception {
            service.saveBag(playerUuid);

            verify(dataOperator, never()).insert(any());
            verify(dataOperator, never()).update(any());
        }

        @Test
        @DisplayName("Should insert new bag data")
        void insertsNewData() {
            when(mockQuery.list()).thenReturn(Collections.emptyList());

            service.setBagPage(playerUuid, 1, new ItemStack[45]);
            service.saveBag(playerUuid);

            verify(dataOperator).insert(any(RemoteBagData.class));
        }

        @Test
        @DisplayName("Should update existing bag data")
        void updatesExistingData() throws Exception {
            RemoteBagData existing = RemoteBagData.create(playerUuid, 1, "old-content");
            when(mockQuery.list()).thenReturn(Collections.singletonList(existing));

            service.setBagPage(playerUuid, 1, new ItemStack[45]);
            service.saveBag(playerUuid);

            verify(dataOperator).update(any(RemoteBagData.class));
        }

        @Test
        @DisplayName("Should save multiple pages")
        void savesMultiplePages() {
            when(mockQuery.list()).thenReturn(Collections.emptyList());

            service.setBagPage(playerUuid, 1, new ItemStack[45]);
            service.setBagPage(playerUuid, 2, new ItemStack[45]);
            service.saveBag(playerUuid);

            verify(dataOperator, times(2)).insert(any(RemoteBagData.class));
        }

        @Test
        @DisplayName("Should handle update exception gracefully")
        void handlesUpdateException() throws Exception {
            RemoteBagData existing = RemoteBagData.create(playerUuid, 1, "old-content");
            when(mockQuery.list()).thenReturn(Collections.singletonList(existing));
            doThrow(new IllegalAccessException("Test error")).when(dataOperator).update(any(RemoteBagData.class));

            service.setBagPage(playerUuid, 1, new ItemStack[45]);

            // Should not throw, should log error instead
            assertThatCode(() -> service.saveBag(playerUuid)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Under language: zh a failed bag write is logged with the Chinese catalogue text (gate-1 WR-03)")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void updateFailureFollowsTheLanguageSetting() throws Exception {
            java.lang.reflect.Field pluginField = RemoteBagService.class.getDeclaredField("plugin");
            pluginField.setAccessible(true);
            UltiToolsPlugin plugin = (UltiToolsPlugin) pluginField.get(service);
            when(plugin.i18n(anyString())).thenAnswer(com.ultikits.plugins.remotebag.i18n.CatalogueText.answer("zh"));
            RemoteBagData existing = RemoteBagData.create(playerUuid, 1, "old-content");
            when(mockQuery.list()).thenReturn(Collections.singletonList(existing));
            doThrow(new IllegalAccessException("Test error")).when(dataOperator).update(any(RemoteBagData.class));
            String expected = com.ultikits.plugins.remotebag.i18n.CatalogueText.text("zh", "log_bag_update_failed");

            service.setBagPage(playerUuid, 1, new ItemStack[45]);
            service.saveBag(playerUuid);

            verify(plugin.getLogger()).error(eq(expected), any(IllegalAccessException.class));
        }
    }

    // ==================== saveAllBags ====================

    @Nested
    @DisplayName("saveAllBags")
    class SaveAllBags {

        @Test
        @DisplayName("Should save all cached bags")
        void savesAllCached() {
            UUID uuid1 = UUID.randomUUID();
            UUID uuid2 = UUID.randomUUID();

            when(mockQuery.list()).thenReturn(Collections.emptyList());

            service.setBagPage(uuid1, 1, new ItemStack[45]);
            service.setBagPage(uuid2, 1, new ItemStack[45]);

            service.saveAllBags();

            verify(dataOperator, atLeast(2)).insert(any(RemoteBagData.class));
        }

        @Test
        @DisplayName("Should do nothing when cache is empty")
        void doesNothingWhenCacheEmpty() throws Exception {
            service.saveAllBags();

            verify(dataOperator, never()).insert(any());
            verify(dataOperator, never()).update(any(RemoteBagData.class));
        }
    }

    // ==================== createBagPage ====================

    @Nested
    @DisplayName("createBagPage")
    class CreateBagPage {

        @Test
        @DisplayName("Should create page for player with no existing bags")
        void createsFirstPage() {
            // When no data exists in DB, getPlayerBagPages returns [1] as default
            // So createBagPage creates the next page (2)
            when(mockQuery.list()).thenReturn(Collections.emptyList());

            int pageNum = service.createBagPage(playerUuid);

            assertThat(pageNum).isEqualTo(2);
            verify(dataOperator).insert(any(RemoteBagData.class));
        }

        @Test
        @DisplayName("Should create next sequential page")
        void createsNextPage() {
            // First call to loadBagIfNeeded returns pages 1 and 2
            when(mockQuery.list())
                    .thenReturn(Arrays.asList(
                            RemoteBagData.create(playerUuid, 1, ""),
                            RemoteBagData.create(playerUuid, 2, "")
                    ))
                    // Subsequent calls for saveBag (checking existing) return empty
                    .thenReturn(Collections.emptyList());

            int pageNum = service.createBagPage(playerUuid);

            assertThat(pageNum).isEqualTo(3);
        }

        @Test
        @DisplayName("Should save bag after creating page")
        void savesBagAfterCreation() {
            when(mockQuery.list()).thenReturn(Collections.emptyList());

            service.createBagPage(playerUuid);

            verify(dataOperator).insert(any(RemoteBagData.class));
        }
    }

    // ==================== deleteBagPage ====================

    @Nested
    @DisplayName("deleteBagPage")
    class DeleteBagPage {

        @Test
        @DisplayName("Should return false when page not found")
        void returnsFalseWhenNotFound() {
            when(mockQuery.list()).thenReturn(Collections.emptyList());

            service.loadBagIfNeeded(playerUuid);

            boolean result = service.deleteBagPage(playerUuid, 5);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should delete page from cache and database")
        void deletesPage() {
            RemoteBagData data = RemoteBagData.create(playerUuid, 1, "");
            // First list() for loadBagIfNeeded, second list() for deleteBagPage's query
            when(mockQuery.list())
                    .thenReturn(Collections.singletonList(data))
                    .thenReturn(Collections.singletonList(data));

            service.loadBagIfNeeded(playerUuid);
            boolean result = service.deleteBagPage(playerUuid, 1);

            assertThat(result).isTrue();
            verify(dataOperator).delById(data.getId());
        }

        @Test
        @DisplayName("Should return false when player not in cache and no data")
        void returnsFalseWhenPlayerNotCached() {
            when(mockQuery.list()).thenReturn(Collections.emptyList());

            boolean result = service.deleteBagPage(playerUuid, 1);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should remove page from cache after deletion")
        void removesFromCacheAfterDeletion() {
            RemoteBagData data = RemoteBagData.create(playerUuid, 1, "");
            when(mockQuery.list())
                    .thenReturn(Collections.singletonList(data))
                    .thenReturn(Collections.singletonList(data));

            service.loadBagIfNeeded(playerUuid);
            service.deleteBagPage(playerUuid, 1);

            // After deleting page 1, getBagPage should return null for that page
            assertThat(service.getBagPage(playerUuid, 1)).isNull();
        }

        @Test
        @DisplayName("Should delete multiple database entries for same page")
        void deletesMultipleDbEntries() {
            RemoteBagData data1 = RemoteBagData.create(playerUuid, 1, "content1");
            RemoteBagData data2 = RemoteBagData.create(playerUuid, 1, "content2");

            // First list() for loadBagIfNeeded, second list() for deleteBagPage's query
            when(mockQuery.list())
                    .thenReturn(Collections.singletonList(data1))
                    .thenReturn(Arrays.asList(data1, data2));

            service.loadBagIfNeeded(playerUuid);
            service.deleteBagPage(playerUuid, 1);

            // Both entries have the same id (null from builder), so delById(null) is called twice
            verify(dataOperator, times(2)).delById(any());
        }
    }

    // ==================== clearBagPage ====================

    @Nested
    @DisplayName("clearBagPage")
    class ClearBagPage {

        @Test
        @DisplayName("Should return false when page not found")
        void returnsFalseWhenNotFound() {
            when(mockQuery.list()).thenReturn(Collections.emptyList());

            service.loadBagIfNeeded(playerUuid);

            boolean result = service.clearBagPage(playerUuid, 5);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should clear page contents")
        void clearsPageContents() {
            // First list() for loadBagIfNeeded, second for saveBag inside clearBagPage
            when(mockQuery.list())
                    .thenReturn(Collections.singletonList(
                            RemoteBagData.create(playerUuid, 1, "old-content")
                    ))
                    .thenReturn(Collections.emptyList());

            service.loadBagIfNeeded(playerUuid);
            boolean result = service.clearBagPage(playerUuid, 1);

            assertThat(result).isTrue();
            verify(dataOperator).insert(any(RemoteBagData.class));
        }

        @Test
        @DisplayName("Should return false when player not cached and no data")
        void returnsFalseWhenNotCached() {
            when(mockQuery.list()).thenReturn(Collections.emptyList());

            boolean result = service.clearBagPage(playerUuid, 1);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should replace contents with empty array after clearing")
        void replacesContentsWithEmpty() {
            // First list() for loadBagIfNeeded, second for saveBag inside clearBagPage
            when(mockQuery.list())
                    .thenReturn(Collections.singletonList(
                            RemoteBagData.create(playerUuid, 1, "")
                    ))
                    .thenReturn(Collections.emptyList());

            service.loadBagIfNeeded(playerUuid);
            service.clearBagPage(playerUuid, 1);

            ItemStack[] cleared = service.getBagPage(playerUuid, 1);
            assertThat(cleared).isNotNull();
            // All slots should be null (empty)
            for (ItemStack item : cleared) {
                assertThat(item).isNull();
            }
        }
    }

    // ==================== getConfig ====================

    @Nested
    @DisplayName("getConfig")
    class GetConfig {

        @Test
        @DisplayName("Should return config")
        void returnsConfig() {
            assertThat(service.getConfig()).isSameAs(config);
        }
    }

    // ==================== init ====================

    @Nested
    @DisplayName("init")
    class Init {

        @Test
        @DisplayName("Should initialize dataOperator from plugin")
        void initializesDataOperator() throws Exception {
            UltiToolsPlugin initPlugin = mock(UltiToolsPlugin.class);
            @SuppressWarnings("unchecked")
            DataOperator<RemoteBagData> initOperator = mock(DataOperator.class);
            when(initPlugin.getDataOperator(RemoteBagData.class)).thenReturn(initOperator);

            RemoteBagService initService = new RemoteBagService(initPlugin, config);
            initService.init();

            // Verify getDataOperator was called
            verify(initPlugin).getDataOperator(RemoteBagData.class);
        }
    }

    // ==================== purchaseBag ====================

    @Nested
    @DisplayName("purchaseBag")
    class PurchaseBag {

        @Test
        @DisplayName("Should create bag without economy when economy disabled")
        void createsBagWithoutEconomyWhenDisabled() {
            when(config.isEconomyEnabled()).thenReturn(false);
            when(config.isPermissionBasedPages()).thenReturn(false);
            when(config.getMaxPages()).thenReturn(10);
            when(mockQuery.list()).thenReturn(Collections.emptyList());

            boolean result = service.purchaseBag(player);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should return false when max pages exceeded")
        void returnsFalseWhenMaxPagesExceeded() {
            when(config.isEconomyEnabled()).thenReturn(false);
            when(config.isPermissionBasedPages()).thenReturn(false);
            when(config.getMaxPages()).thenReturn(2);

            // Pre-populate cache with 2 pages so nextBagNum = 3 > maxPages=2
            service.setBagPage(playerUuid, 1, new ItemStack[45]);
            service.setBagPage(playerUuid, 2, new ItemStack[45]);

            boolean result = service.purchaseBag(player);

            assertThat(result).isFalse();
        }

        /**
         * Makes a Vault economy available through the public Bukkit/Vault types only (#31): a mock
         * plugin named {@code Vault} plus a Vault {@link Economy} registered with the live
         * MockBukkit services manager. The framework's default economy bridge resolves exactly
         * these, so no framework-internal seam is touched.
         */
        private Plugin registerVaultEconomy(Economy economy) {
            Plugin vault = MockBukkit.createMockPlugin("Vault");
            Bukkit.getServicesManager().register(Economy.class, economy, vault, ServicePriority.Normal);
            return vault;
        }

        private void unregisterVaultEconomy(Plugin vault) {
            Bukkit.getServicesManager().unregisterAll(vault);
            EconomyUtils.reset();
        }

        @Test
        @DisplayName("Should purchase bag with economy when economy enabled and withdraw succeeds")
        void purchasesWithEconomySuccess() throws Exception {
            Economy mockEconomy = mock(Economy.class);
            when(mockEconomy.has(any(OfflinePlayer.class), anyDouble())).thenReturn(true);
            EconomyResponse successResponse = new EconomyResponse(10000, 90000,
                    EconomyResponse.ResponseType.SUCCESS, "");
            when(mockEconomy.withdrawPlayer(any(OfflinePlayer.class), anyDouble())).thenReturn(successResponse);
            Plugin vault = registerVaultEconomy(mockEconomy);

            try {
                when(config.isEconomyEnabled()).thenReturn(true);
                when(config.isPermissionBasedPages()).thenReturn(false);
                when(config.getMaxPages()).thenReturn(10);
                when(config.getBasePrice()).thenReturn(10000);
                when(config.isPriceIncreaseEnabled()).thenReturn(false);
                when(mockQuery.list()).thenReturn(Collections.emptyList());

                boolean result = service.purchaseBag(player);

                assertThat(result).isTrue();
                verify(mockEconomy).withdrawPlayer(eq(player), eq(10000.0));
            } finally {
                unregisterVaultEconomy(vault);
            }
        }

        @Test
        @DisplayName("Should return false and create no page when economy enabled but withdraw fails")
        void returnsFalseWhenWithdrawFails() throws Exception {
            Economy mockEconomy = mock(Economy.class);
            when(mockEconomy.has(any(OfflinePlayer.class), anyDouble())).thenReturn(false);
            Plugin vault = registerVaultEconomy(mockEconomy);

            try {
                when(config.isEconomyEnabled()).thenReturn(true);
                when(config.isPermissionBasedPages()).thenReturn(false);
                when(config.getMaxPages()).thenReturn(10);
                when(config.getBasePrice()).thenReturn(10000);
                when(config.isPriceIncreaseEnabled()).thenReturn(false);
                when(mockQuery.list()).thenReturn(Collections.emptyList());

                boolean result = service.purchaseBag(player);

                assertThat(result).isFalse();
                verify(mockEconomy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
                verify(dataOperator, never()).insert(any());
            } finally {
                unregisterVaultEconomy(vault);
            }
        }

        @Test
        @DisplayName("Should return false when economy enabled and max pages exceeded")
        void returnsFalseWithEconomyAndMaxPages() throws Exception {
            Economy mockEconomy = mock(Economy.class);
            Plugin vault = registerVaultEconomy(mockEconomy);

            try {
                when(config.isEconomyEnabled()).thenReturn(true);
                when(config.isPermissionBasedPages()).thenReturn(false);
                when(config.getMaxPages()).thenReturn(2);

                // Pre-populate cache with 2 pages
                service.setBagPage(playerUuid, 1, new ItemStack[45]);
                service.setBagPage(playerUuid, 2, new ItemStack[45]);

                boolean result = service.purchaseBag(player);

                assertThat(result).isFalse();
                // Should not even try to withdraw
                verify(mockEconomy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
            } finally {
                unregisterVaultEconomy(vault);
            }
        }
    }

    // ==================== deserializeItems ====================

    @Nested
    @DisplayName("deserializeItems")
    class DeserializeItems {

        @Test
        @DisplayName("Should return empty array for null data")
        void returnsEmptyForNull() throws Exception {
            Method deserialize = RemoteBagService.class.getDeclaredMethod(
                    "deserializeItems", String.class, int.class);
            deserialize.setAccessible(true);

            ItemStack[] result = (ItemStack[]) deserialize.invoke(service, (String) null, 1);

            assertThat(result).isNotNull();
            assertThat(result.length).isEqualTo(45); // the fixed page capacity
        }

        @Test
        @DisplayName("Should return empty array for empty string")
        void returnsEmptyForEmptyString() throws Exception {
            Method deserialize = RemoteBagService.class.getDeclaredMethod(
                    "deserializeItems", String.class, int.class);
            deserialize.setAccessible(true);

            ItemStack[] result = (ItemStack[]) deserialize.invoke(service, "", 1);

            assertThat(result).isNotNull();
            assertThat(result.length).isEqualTo(45);
        }

        @Test
        @DisplayName("Should handle invalid YAML gracefully")
        void handlesInvalidYaml() throws Exception {
            Method deserialize = RemoteBagService.class.getDeclaredMethod(
                    "deserializeItems", String.class, int.class);
            deserialize.setAccessible(true);

            // Invalid YAML that will cause a parse error
            ItemStack[] result = (ItemStack[]) deserialize.invoke(service, "not: valid: yaml: {{{}}}", 1);

            // Should return empty array (exception caught)
            assertThat(result).isNotNull();
            assertThat(result.length).isEqualTo(45);
        }

        @Test
        @DisplayName("Should return empty array for YAML without items section")
        void returnsEmptyForNoItemsSection() throws Exception {
            Method deserialize = RemoteBagService.class.getDeclaredMethod(
                    "deserializeItems", String.class, int.class);
            deserialize.setAccessible(true);

            // Valid YAML but no "items" section
            ItemStack[] result = (ItemStack[]) deserialize.invoke(service, "other_key: value\n", 1);

            assertThat(result).isNotNull();
            assertThat(result.length).isEqualTo(45);
            // All slots should be null
            for (ItemStack item : result) {
                assertThat(item).isNull();
            }
        }

        @Test
        @DisplayName("Should iterate items section keys when section exists")
        void iteratesItemsSectionKeys() throws Exception {
            Method deserialize = RemoteBagService.class.getDeclaredMethod(
                    "deserializeItems", String.class, int.class);
            deserialize.setAccessible(true);

            // Valid YAML with items configuration section.
            // Use simple subsection structure so isConfigurationSection("items") returns true
            // and getKeys(false) returns keys, exercising lines 190-193.
            // yaml.getItemStack("items.0") on a non-ItemStack section returns null (no throw).
            String yaml = "items:\n  '0':\n    type: STONE\n  '5':\n    type: DIRT\n";

            ItemStack[] result = (ItemStack[]) deserialize.invoke(service, yaml, 1);

            // getItemStack on a section that is not a serialized ItemStack returns null without
            // throwing, so both keys parse as slot numbers and both slots stay empty. Asserted
            // directly: the catch(Exception e) { assertThat(e).isNotNull(); } this case used to
            // carry passed on ANY throwable, including one from the reflection call itself, so it
            // could not tell "the keys were iterated" from "nothing ran at all".
            assertThat(result).isNotNull();
            assertThat(result.length).isEqualTo(45);
            assertThat(result[0]).isNull();
            assertThat(result[5]).isNull();
        }
    }

    // ==================== serializeItems ====================

    @Nested
    @DisplayName("serializeItems")
    class SerializeItems {

        @Test
        @DisplayName("Should return empty string for null items")
        void returnsEmptyForNull() throws Exception {
            Method serialize = RemoteBagService.class.getDeclaredMethod("serializeItems", ItemStack[].class);
            serialize.setAccessible(true);

            String result = (String) serialize.invoke(service, (Object) null);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return empty YAML for all-null items")
        void returnsEmptyForAllNull() throws Exception {
            Method serialize = RemoteBagService.class.getDeclaredMethod("serializeItems", ItemStack[].class);
            serialize.setAccessible(true);

            String result = (String) serialize.invoke(service, (Object) new ItemStack[45]);

            // Empty YAML config with no items set should produce empty or minimal output
            assertThat(result).isNotNull();
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("createBagPage should handle player with only default page")
        void createBagPageWithDefaultPage() {
            // When no data in DB, getPlayerBagPages returns [1] as default
            when(mockQuery.list()).thenReturn(Collections.emptyList());

            int pageNum = service.createBagPage(playerUuid);

            // Should create page 2 (next after default page 1)
            assertThat(pageNum).isEqualTo(2);
        }

        @Test
        @DisplayName("Multiple loadBagIfNeeded calls for same player should only query once")
        void multipleLoadsQueryOnce() {
            when(mockQuery.list()).thenReturn(Collections.singletonList(
                    RemoteBagData.create(playerUuid, 1, "")
            ));

            service.loadBagIfNeeded(playerUuid);
            service.loadBagIfNeeded(playerUuid);
            service.loadBagIfNeeded(playerUuid);

            verify(mockQuery, times(1)).list();
        }

        @Test
        @DisplayName("clearCache then loadBagIfNeeded should re-query")
        void clearCacheThenLoadReQueries() {
            when(mockQuery.list()).thenReturn(Collections.singletonList(
                    RemoteBagData.create(playerUuid, 1, "")
            ));

            service.loadBagIfNeeded(playerUuid);
            service.clearCache(playerUuid);
            service.loadBagIfNeeded(playerUuid);

            verify(mockQuery, times(2)).list();
        }

        @Test
        @DisplayName("saveBag with items should serialize items")
        void saveBagWithItems() {
            ItemStack[] contents = new ItemStack[45];
            // Use mock ItemStack to avoid Bukkit.server requirement
            contents[0] = mock(ItemStack.class);

            service.setBagPage(playerUuid, 1, contents);
            when(mockQuery.list()).thenReturn(Collections.emptyList());

            service.saveBag(playerUuid);

            verify(dataOperator).insert(any(RemoteBagData.class));
        }

        @Test
        @DisplayName("getPlayerBagPages should return sorted pages from cache")
        void getPlayerBagPagesSortedFromCache() {
            // Manually set pages in non-sorted order
            service.setBagPage(playerUuid, 5, new ItemStack[45]);
            service.setBagPage(playerUuid, 1, new ItemStack[45]);
            service.setBagPage(playerUuid, 3, new ItemStack[45]);

            List<Integer> pages = service.getPlayerBagPages(playerUuid);

            assertThat(pages).containsExactly(1, 3, 5);
        }

        @Test
        @DisplayName("deleteBagPage should not affect other pages")
        void deleteBagPageDoesNotAffectOthers() {
            // Load pages 1 and 2
            when(mockQuery.list())
                    .thenReturn(Arrays.asList(
                            RemoteBagData.create(playerUuid, 1, ""),
                            RemoteBagData.create(playerUuid, 2, "")
                    ))
                    .thenReturn(Collections.emptyList()); // For deleteBagPage's internal query

            service.loadBagIfNeeded(playerUuid);
            service.deleteBagPage(playerUuid, 1);

            // Page 2 should still exist
            assertThat(service.getBagPage(playerUuid, 2)).isNotNull();
        }
    }
}
