package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.entity.RemoteBagData;
import com.ultikits.plugins.remotebag.entity.RemoteBagEditClaim;
import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers;
import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.Server;
import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.Window;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers.pageWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Upgrading to the edit claim needs no migration (UltiKits/UltiRemoteBag#54; plan 17-84 design): the claims
 * live in their own table, {@code remote_bag_claims}, which the framework creates on the first start of the
 * new jar; no existing table changes; a page with no claim row is unclaimed.
 */
@DisplayName("Upgrading: the claims table is new, every page opens for editing, no bag row changes (UltiRemoteBag#54)")
class BagClaimUpgradeTest {

    @TempDir
    Path dir;

    private SharedDatabaseServers servers;

    @AfterEach
    void tearDown() throws Exception {
        if (servers != null) {
            servers.stop();
        }
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.valueOf(text).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    @DisplayName("The claim service asks the framework for its own table at start, and for no other")
    void theClaimsTableIsRequestedAtStart() throws Exception {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        @SuppressWarnings("unchecked")
        DataOperator<RemoteBagEditClaim> claims = mock(DataOperator.class);
        when(plugin.getDataOperator(RemoteBagEditClaim.class)).thenReturn(claims);
        BagEditClaimService service = new BagEditClaimService();
        UltiRemoteBagTestHelper.setField(service, "plugin", plugin);

        service.init();

        verify(plugin).getDataOperator(RemoteBagEditClaim.class);
        assertThat(RemoteBagEditClaim.class.getAnnotation(com.ultikits.ultitools.annotations.Table.class).value())
                .isEqualTo("remote_bag_claims");
        assertThat(RemoteBagData.class.getAnnotation(com.ultikits.ultitools.annotations.Table.class).value())
                .as("the bag table is unchanged").isEqualTo("remote_bags");
    }

    private static java.util.List<String> sqlite(java.nio.file.Path file, String sql) throws Exception {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             java.sql.ResultSet rows = c.createStatement().executeQuery(sql)) {
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                StringBuilder line = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    line.append(i > 1 ? "|" : "").append(rows.getString(i));
                }
                out.add(line.toString());
            }
        }
        return out;
    }

    @Test
    @DisplayName("Real SQLite: a database with remote_bags only gets remote_bag_claims at start; remote_bags' columns and rows are byte-identical; every page can be claimed")
    void upgradeOnRealSqlite() throws Exception {
        com.ultikits.plugins.remotebag.MockBukkitSupport.bootstrapLiveServer();
        try {
            upgradeOnRealSqliteWithALiveServer();
        } finally {
            com.ultikits.plugins.remotebag.MockBukkitSupport.safeUnmock();
        }
    }

    private void upgradeOnRealSqliteWithALiveServer() throws Exception {
        java.nio.file.Path file = dir.resolve("upgrade.db");
        org.sqlite.SQLiteDataSource source = new org.sqlite.SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        // What the current master wrote: the remote_bags table and its rows, nothing else.
        DataOperator<RemoteBagData> bags =
                new com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator<>(source, RemoteBagData.class);
        UUID owner = UUID.randomUUID();
        for (int page = 1; page <= 3; page++) {
            bags.insert(RemoteBagData.create(owner, page, SharedDatabaseServers.serialize(
                    pageWith(page, new ItemStack(Material.DIAMOND, page)))));
        }
        java.util.List<String> columnsBefore = sqlite(file, "PRAGMA table_info(remote_bags)");
        java.util.List<String> rowsBefore = sqlite(file, "SELECT * FROM remote_bags ORDER BY id");
        assertThat(sqlite(file, "SELECT name FROM sqlite_master WHERE type='table' AND name='remote_bag_claims'"))
                .as("precondition: no claims table").isEmpty();

        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        when(plugin.getDataOperator(RemoteBagEditClaim.class)).thenAnswer(inv ->
                new com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator<>(source, RemoteBagEditClaim.class));
        when(plugin.getLogger()).thenReturn(mock(com.ultikits.ultitools.interfaces.impl.logger.PluginLogger.class));
        BagEditClaimService service = new BagEditClaimService();
        UltiRemoteBagTestHelper.setField(service, "plugin", plugin);
        UltiRemoteBagTestHelper.setField(service, "config", UltiRemoteBagTestHelper.createDefaultConfig());
        try {
            service.init();

            assertThat(sqlite(file, "SELECT name FROM sqlite_master WHERE type='table' AND name='remote_bag_claims'"))
                    .as("the claims table is created at start").containsExactly("remote_bag_claims");
            for (int page = 1; page <= 3; page++) {
                assertThat(service.claim(owner, page, owner)).as("page %d can be claimed", page)
                        .isEqualTo(BagEditClaimService.Outcome.CLAIMED);
            }
            assertThat(sqlite(file, "PRAGMA table_info(remote_bags)")).as("remote_bags' columns are unchanged")
                    .isEqualTo(columnsBefore);
            assertThat(sqlite(file, "SELECT * FROM remote_bags ORDER BY id")).as("remote_bags' rows are unchanged")
                    .isEqualTo(rowsBefore);
        } finally {
            service.releaseAllHeld();
            UltiRemoteBagTestHelper.setFieldIfPresent(service, "renewer", null);
        }
    }

    @Test
    @DisplayName("Data written by the current master (bag rows, no claims): every page opens for editing and no bag row's contents change")
    void upgradeWithoutMigration() throws Exception {
        servers = SharedDatabaseServers.start(dir);
        Server upgraded = servers.newServer("upgraded");
        PlayerMock owner = servers.live().addPlayer("Owner");
        UUID ownerId = owner.getUniqueId();
        // What the current master wrote: bag rows only.
        servers.seedPage(ownerId, 1, pageWith(0, new ItemStack(Material.DIAMOND)));
        servers.seedPage(ownerId, 2, pageWith(3, new ItemStack(Material.EMERALD, 5)));
        servers.seedPage(ownerId, 3, new ItemStack[SharedDatabaseServers.PAGE_SIZE]);
        Map<Integer, String> before = new HashMap<>();
        for (int page = 1; page <= 3; page++) {
            before.put(page, sha256(servers.storedRow(ownerId, page).getContents()));
        }
        assertThat(servers.claimStore().getAll()).as("precondition: no claim exists").isEmpty();

        for (int page = 1; page <= 3; page++) {
            Window window = upgraded.openAsOwner(owner, page);
            assertThat(window.isEdit()).as("page %d opens for editing", page).isTrue();
            assertThat(servers.claimRow(ownerId, page)).as("page %d is claimed in the new table", page).isNotNull();
            assertThat(sha256(servers.storedRow(ownerId, page).getContents()))
                    .as("page %d's stored contents are unchanged by opening it", page).isEqualTo(before.get(page));
            owner.closeInventory();
        }
        for (int page = 1; page <= 3; page++) {
            assertThat(servers.storedRowCount(ownerId, page)).as("page %d still has one row", page).isEqualTo(1);
        }
    }
}
