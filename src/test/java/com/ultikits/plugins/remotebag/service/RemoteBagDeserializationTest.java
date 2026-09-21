package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.MockBukkitSupport;
import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Loading a stored bag page whose slot indices do not fit the array that {@code rows_per_page}
 * would size (<a href="https://github.com/UltiKits/UltiRemoteBag/issues/24">UltiRemoteBag#24</a>,
 * the data-loss half).
 *
 * <p>{@code RemoteBagContentGUI} exposes and saves 45 slots regardless of {@code rows_per_page},
 * so a server running {@code rows_per_page: 1} stores slot indices up to 44 and then, on the next
 * load, allocated a 9-element array and wrote index 40 into it. The
 * {@code ArrayIndexOutOfBoundsException} was caught, logged and swallowed, and the page came back
 * empty — every item on it gone. Whether a smaller {@code rows_per_page} should shrink the page at
 * all is a separate, open question; these cases only pin down that loading never destroys a stored
 * page.
 *
 * <h2>What makes a vacuous pass impossible here</h2>
 * The service is real and its store is a real {@link InMemoryRemoteBagStore} seeded with YAML in
 * exactly the format {@code RemoteBagService#serializeItems} writes (asserted by
 * {@link #serializedFormatIsWhatTheseCasesSeed()}, so these cases cannot be testing a format the
 * production code never produces), and every assertion reads an {@link ItemStack} back out of
 * {@code getBagPage} rather than checking that no exception was thrown. A swallowed failure returns
 * an empty page, which is exactly what the assertions reject.
 */
@DisplayName("Loading a stored page under a small rows_per_page (UltiRemoteBag#24)")
class RemoteBagDeserializationTest {

    private static final int PAGE = 1;

    private RemoteBagService service;
    private RemoteBagConfig config;
    private InMemoryRemoteBagStore store;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitSupport.bootstrapLiveServer();
        UltiRemoteBagTestHelper.setUp();

        config = UltiRemoteBagTestHelper.createDefaultConfig();
        store = new InMemoryRemoteBagStore();

        UltiToolsPlugin mockPlugin = mock(UltiToolsPlugin.class);
        lenient().when(mockPlugin.getLogger()).thenReturn(mock(PluginLogger.class));

        service = new RemoteBagService(mockPlugin, config);
        UltiRemoteBagTestHelper.setField(service, "dataOperator", store);

        playerUuid = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiRemoteBagTestHelper.tearDown();
        MockBukkitSupport.safeUnmock();
    }

    @Test
    @DisplayName("An item stored at slot 40 survives a load with rows_per_page = 1")
    void slotBeyondConfiguredRowsIsNotDiscarded() {
        lenient().when(config.getRowsPerPage()).thenReturn(1);
        store.seed(playerUuid.toString(), PAGE, yamlWith(40, new ItemStack(Material.DIAMOND)));

        service.loadBagIfNeeded(playerUuid);
        ItemStack[] page = service.getBagPage(playerUuid, PAGE);

        assertThat(page).isNotNull();
        assertThat(page.length)
                .as("the array has to be long enough to hold the stored index")
                .isGreaterThan(40);
        assertThat(page[40])
                .as("the diamond stored at slot 40 came back")
                .isEqualTo(new ItemStack(Material.DIAMOND));
    }

    @Test
    @DisplayName("Items below the configured size survive alongside one beyond it")
    void oneOutOfRangeSlotDoesNotDiscardTheRestOfThePage() {
        lenient().when(config.getRowsPerPage()).thenReturn(1);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("items.0", new ItemStack(Material.EMERALD));
        yaml.set("items.40", new ItemStack(Material.DIAMOND));
        store.seed(playerUuid.toString(), PAGE, yaml.saveToString());

        service.loadBagIfNeeded(playerUuid);
        ItemStack[] page = service.getBagPage(playerUuid, PAGE);

        assertThat(page[0])
                .as("the in-range emerald must not be lost because a later index was out of range")
                .isEqualTo(new ItemStack(Material.EMERALD));
        assertThat(page[40]).isEqualTo(new ItemStack(Material.DIAMOND));
    }

    @Test
    @DisplayName("A page with an unparsable slot key keeps the items that do parse")
    void anUnparsableKeyDoesNotDiscardThePage() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("items.0", new ItemStack(Material.DIAMOND));
        yaml.set("items.not-a-slot", "garbage");
        store.seed(playerUuid.toString(), PAGE, yaml.saveToString());

        service.loadBagIfNeeded(playerUuid);
        ItemStack[] page = service.getBagPage(playerUuid, PAGE);

        assertThat(page).isNotNull();
        assertThat(page[0])
                .as("one unreadable key must not cost the whole page")
                .isEqualTo(new ItemStack(Material.DIAMOND));
    }

    @Test
    @DisplayName("A negative slot index is skipped and the rest of the page loads")
    void aNegativeSlotIsSkipped() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("items.-3", new ItemStack(Material.DIAMOND));
        yaml.set("items.2", new ItemStack(Material.EMERALD));
        store.seed(playerUuid.toString(), PAGE, yaml.saveToString());

        service.loadBagIfNeeded(playerUuid);
        ItemStack[] page = service.getBagPage(playerUuid, PAGE);

        assertThat(page).isNotNull();
        assertThat(page[2])
                .as("an impossible index must not cost the items that are fine")
                .isEqualTo(new ItemStack(Material.EMERALD));
    }

    @Test
    @DisplayName("A page stored entirely within rows_per_page still loads at the configured size")
    void anInRangePageKeepsTheConfiguredSize() {
        lenient().when(config.getRowsPerPage()).thenReturn(6);
        store.seed(playerUuid.toString(), PAGE, yamlWith(3, new ItemStack(Material.DIAMOND)));

        service.loadBagIfNeeded(playerUuid);
        ItemStack[] page = service.getBagPage(playerUuid, PAGE);

        assertThat(page.length)
                .as("control: with every index in range the array is still rows_per_page * 9")
                .isEqualTo(54);
        assertThat(page[3]).isEqualTo(new ItemStack(Material.DIAMOND));
    }

    @Test
    @DisplayName("The seeded YAML is the format the service itself writes")
    void serializedFormatIsWhatTheseCasesSeed() {
        ItemStack[] contents = new ItemStack[45];
        contents[40] = new ItemStack(Material.DIAMOND);
        service.setBagPage(playerUuid, PAGE, contents);
        service.saveBag(playerUuid);

        String written = store.storedContents(playerUuid.toString(), PAGE);
        assertThat(written)
                .as("control: the production serializer keys items by slot index, as seeded above")
                .contains("items:")
                .contains("'40':")
                .contains("minecraft:diamond");
    }

    private String yamlWith(int slot, ItemStack item) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("items." + slot, item);
        return yaml.saveToString();
    }
}
