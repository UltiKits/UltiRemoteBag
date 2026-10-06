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
