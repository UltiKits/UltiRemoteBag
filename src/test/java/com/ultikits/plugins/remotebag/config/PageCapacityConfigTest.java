package com.ultikits.plugins.remotebag.config;

import com.ultikits.plugins.remotebag.UltiRemoteBag;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Page capacity configuration compatibility (#38)")
class PageCapacityConfigTest {
    @TempDir Path folder;

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    @DisplayName("Content rows are additive and map to real storage capacity")
    void allowedRows(int rows) {
        RemoteBagConfig config = new RemoteBagConfig("unused.yml");
        config.setContentRows(rows);
        assertThat(config.getContentRows()).isEqualTo(rows);
        assertThat(config.getPageCapacity()).isEqualTo(rows * 9);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 7, Integer.MAX_VALUE})
    @DisplayName("Invalid file values refuse loading without overwriting operator values or comments")
    void invalidValuesRemainOnDisk(int invalid) throws Exception {
        UltiRemoteBag plugin = ConfigFileFixture.plugin(folder, "en");
        ConfigFileFixture.load(plugin);
        Path file = folder.resolve(ConfigFileFixture.CONFIG_FILE);
        String edited = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("content_rows: 5", "content_rows: " + invalid)
                + "\n# Operator-owned legacy key stays ignored\nrows_per_page: 2\n";
        Files.write(file, edited.getBytes(StandardCharsets.UTF_8));
        byte[] before = Files.readAllBytes(file);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ConfigFileFixture.load(plugin))
                .hasMessageContaining("refused to load")
                .hasMessageContaining("contentRows");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        RemoteBagConfig config = new RemoteBagConfig("unused.yml");
        config.setContentRows(invalid);
        assertThat(config.getPageCapacity()).isEqualTo(45);
    }

    @Test
    @DisplayName("Occupied rows, not allocation length or AIR, keep overflow capacity")
    void effectiveCapacity() {
        ItemStack[] items = new ItemStack[54];
        assertThat(RemoteBagConfig.effectiveCapacity(18, null)).isEqualTo(18);
        assertThat(RemoteBagConfig.effectiveCapacity(18, items)).isEqualTo(18);
        ItemStack item = org.mockito.Mockito.mock(ItemStack.class);
        items[53] = item;
        org.mockito.Mockito.when(item.getType()).thenReturn(Material.AIR);
        assertThat(RemoteBagConfig.effectiveCapacity(18, items)).isEqualTo(18);
        org.mockito.Mockito.when(item.getType()).thenReturn(Material.DIAMOND);
        org.mockito.Mockito.when(item.getAmount()).thenReturn(0);
        assertThat(RemoteBagConfig.effectiveCapacity(18, items)).isEqualTo(18);
        org.mockito.Mockito.when(item.getAmount()).thenReturn(1);
        assertThat(RemoteBagConfig.effectiveCapacity(18, items)).isEqualTo(54);
        assertThat(RemoteBagConfig.effectiveCapacity(54, new ItemStack[9])).isEqualTo(54);
    }
}
