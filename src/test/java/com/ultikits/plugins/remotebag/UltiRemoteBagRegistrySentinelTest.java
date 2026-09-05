package com.ultikits.plugins.remotebag;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Reopen guard: proves the test tree actually reaches a live Bukkit registry rather than merely
 * declaring the {@code mockbukkit-v1.21} dependency. Every assertion is non-null on a
 * live-server-backed accessor — never a bare registry constant, which would resolve via
 * {@code mockbukkit-v1.21}'s {@code ServiceLoader}-registered {@code RegistryAccess} from the
 * classpath alone, independent of whether {@code MockBukkit.mock()} ever ran.
 */
public class UltiRemoteBagRegistrySentinelTest {

    @Test
    void liveServerIsBootstrapped() {
        assertNotNull(Bukkit.getServer(), "live server bootstrap must be present");
    }

    @Test
    void unsafeValuesResolves() {
        assertNotNull(Bukkit.getUnsafe(), "UnsafeValues must resolve on a live server");
    }

    @Test
    void createProfileDoesNotSilentlyReturnNull() {
        Object profile = Bukkit.createProfile(UUID.randomUUID(), "SentinelPlayer");
        assertNotNull(profile, "createProfile must not silently return null");
    }

    @Test
    void itemStackConstructionResolvesRegistry() {
        ItemStack stack = new ItemStack(Material.DIAMOND);
        assertNotNull(stack);
        assertEquals(Material.DIAMOND, stack.getType());
    }
}
