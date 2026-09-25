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
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Loading a stored bag page whose slot indices do not fit the array the loader would size for it
 * (<a href="https://github.com/UltiKits/UltiRemoteBag/issues/24">UltiRemoteBag#24</a>).
 *
 * <p>History, because it is what these cases are guarding: the array used to be sized from
 * {@code rows_per_page}, while {@code RemoteBagContentGUI} exposed and saved 45 slots regardless of
 * it. A server running {@code rows_per_page: 1} stored indices up to 44 and then, on the next load,
 * allocated a 9-element array and wrote index 40 into it. The
 * {@code ArrayIndexOutOfBoundsException} was caught, logged and swallowed, and the page came back
 * empty — every item on it gone. {@code rows_per_page} has since been deleted outright and page
 * capacity is the fixed {@code RemoteBagConfig#PAGE_CAPACITY}, so that particular mismatch cannot
 * recur; what these cases still pin is the invariant that outlived it — loading never destroys a
 * stored page, and no stored key can size the allocation.
 *
 * <h2>What makes a vacuous pass impossible here</h2>
 * The service is real and its store is a real {@link InMemoryRemoteBagStore} seeded with YAML in
 * exactly the format {@code RemoteBagService#serializeItems} writes (asserted by
 * {@link #serializedFormatIsWhatTheseCasesSeed()}, so these cases cannot be testing a format the
 * production code never produces), and every assertion reads an {@link ItemStack} back out of
 * {@code getBagPage} rather than checking that no exception was thrown. A swallowed failure returns
 * an empty page, which is exactly what the assertions reject.
 */
@DisplayName("Loading a stored page whose indices exceed the page capacity (UltiRemoteBag#24)")
class RemoteBagDeserializationTest {

    private static final int PAGE = 1;

    private RemoteBagService service;
    private RemoteBagConfig config;
    private InMemoryRemoteBagStore store;
    private PluginLogger pluginLogger;
    private UltiToolsPlugin mockPlugin;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitSupport.bootstrapLiveServer();
        UltiRemoteBagTestHelper.setUp();

        config = UltiRemoteBagTestHelper.createDefaultConfig();
        store = new InMemoryRemoteBagStore();

        UltiToolsPlugin mockPlugin = mock(UltiToolsPlugin.class);
        pluginLogger = mock(PluginLogger.class);
        lenient().when(mockPlugin.getLogger()).thenReturn(pluginLogger);
        // The console lines come from the language file; the assertions quote its English text.
        lenient().when(mockPlugin.i18n(anyString())).thenAnswer(com.ultikits.plugins.remotebag.i18n.CatalogueText.answer("en"));
        this.mockPlugin = mockPlugin;

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
    @DisplayName("An item stored past the page capacity survives the load")
    void slotBeyondThePageCapacityIsNotDiscarded() {
        // 50 is past PAGE_CAPACITY (45) and inside the loader's legacy tolerance band, so this is
        // the grow path. Slot 40 -- the index this case used while rows_per_page: 1 sized the array
        // at 9 -- is now inside the fixed capacity and would exercise nothing.
        store.seed(playerUuid.toString(), PAGE, yamlWith(50, new ItemStack(Material.DIAMOND)));

        service.loadBagIfNeeded(playerUuid);
        ItemStack[] page = service.getBagPage(playerUuid, PAGE);

        assertThat(page).isNotNull();
        assertThat(page.length)
                .as("the array has to be long enough to hold the stored index")
                .isGreaterThan(50);
        assertThat(page[50])
                .as("the diamond stored at slot 50 came back")
                .isEqualTo(new ItemStack(Material.DIAMOND));
    }

    @Test
    @DisplayName("Items inside the capacity survive alongside one beyond it")
    void oneOutOfRangeSlotDoesNotDiscardTheRestOfThePage() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("items.0", new ItemStack(Material.EMERALD));
        yaml.set("items.50", new ItemStack(Material.DIAMOND));
        store.seed(playerUuid.toString(), PAGE, yaml.saveToString());

        service.loadBagIfNeeded(playerUuid);
        ItemStack[] page = service.getBagPage(playerUuid, PAGE);

        assertThat(page[0])
                .as("the in-capacity emerald must not be lost because a later index was beyond it")
                .isEqualTo(new ItemStack(Material.EMERALD));
        assertThat(page[50]).isEqualTo(new ItemStack(Material.DIAMOND));
    }

    @Test
    @DisplayName("A skipped slot is warned about through the module's own logger, not an inline one")
    void aSkippedSlotWarnsThroughTheModuleLogger() {
        // Both warnings used an inline java.util.logging.Logger while this class holds a plugin whose
        // getLogger() is the framework PluginLogger used everywhere else in the module, so the lines
        // appeared without the [UltiTools] [UltiRemoteBag] prefix that
        // ultiremotebag.bag.persistence.small-rows-per-page's verdict looks for (gate-1 review, IN-13).
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("items.0", new ItemStack(Material.DIAMOND));
        yaml.set("items.not-a-slot", "garbage");
        store.seed(playerUuid.toString(), PAGE, yaml.saveToString());

        service.loadBagIfNeeded(playerUuid);

        ArgumentCaptor<String> warned = ArgumentCaptor.forClass(String.class);
        verify(pluginLogger, atLeastOnce()).warn(warned.capture());
        assertThat(warned.getAllValues())
                .as("the skip is reported through the plugin logger, naming the page and the key")
                .anySatisfy(line -> assertThat(line)
                        .contains("Skipping unreadable slot in bag page 1")
                        .contains("not-a-slot"));
    }

    @Test
    @DisplayName("Under language: zh the skipped-slot line is the Chinese catalogue text")
    void aSkippedSlotWarningFollowsTheLanguageSetting() {
        when(mockPlugin.i18n(anyString())).thenAnswer(com.ultikits.plugins.remotebag.i18n.CatalogueText.answer("zh"));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("items.0", new ItemStack(Material.DIAMOND));
        yaml.set("items.not-a-slot", "garbage");
        store.seed(playerUuid.toString(), PAGE, yaml.saveToString());
        String expected = com.ultikits.plugins.remotebag.i18n.CatalogueText.text("zh", "log_skipped_slot").replace("{PAGE}", String.valueOf(PAGE))
                .replace("{REASON}", com.ultikits.plugins.remotebag.i18n.CatalogueText.text("zh", "log_skipped_slot_not_a_number"))
                .replace("{KEY}", "not-a-slot");

        service.loadBagIfNeeded(playerUuid);

        ArgumentCaptor<String> warned = ArgumentCaptor.forClass(String.class);
        verify(pluginLogger, atLeastOnce()).warn(warned.capture());
        assertThat(warned.getAllValues()).contains(expected);
    }

    @Test
    @DisplayName("Under language: zh every skipped-slot reason, and an unreadable page, is the Chinese catalogue text (gate-1 WR-03)")
    void everyRoutedDeserializationLineIsTheCatalogueText() {
        when(mockPlugin.i18n(anyString())).thenAnswer(com.ultikits.plugins.remotebag.i18n.CatalogueText.answer("zh"));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("items.0", new ItemStack(Material.DIAMOND));
        yaml.set("items.-1", "garbage");
        yaml.set("items.07", "garbage");
        yaml.set("items.99999", "garbage");
        store.seed(playerUuid.toString(), PAGE, yaml.saveToString());
        String line = com.ultikits.plugins.remotebag.i18n.CatalogueText.text("zh", "log_skipped_slot").replace("{PAGE}", String.valueOf(PAGE));
        String negative = line.replace("{REASON}", com.ultikits.plugins.remotebag.i18n.CatalogueText.text("zh", "log_skipped_slot_negative")).replace("{KEY}", "-1");
        String padded = line.replace("{REASON}", com.ultikits.plugins.remotebag.i18n.CatalogueText.text("zh", "log_skipped_slot_not_canonical")).replace("{KEY}", "07");
        String beyond = line.replace("{REASON}", com.ultikits.plugins.remotebag.i18n.CatalogueText.text("zh", "log_skipped_slot_beyond_max")
                .replace("{MAX}", String.valueOf(com.ultikits.plugins.remotebag.config.RemoteBagConfig.MAX_ADDRESSABLE_SLOTS)))
                .replace("{KEY}", "99999");

        service.loadBagIfNeeded(playerUuid);

        ArgumentCaptor<String> warned = ArgumentCaptor.forClass(String.class);
        verify(pluginLogger, atLeastOnce()).warn(warned.capture());
        assertThat(warned.getAllValues()).contains(negative, padded, beyond);
        assertThat(String.join("\n", warned.getAllValues())).doesNotContain("{MAX}");
    }

    @Test
    @DisplayName("Under language: zh a page whose stored data cannot be read is reported with the Chinese catalogue text (gate-1 WR-03)")
    void anUnreadablePageIsReportedInTheServerLanguage() {
        when(mockPlugin.i18n(anyString())).thenAnswer(com.ultikits.plugins.remotebag.i18n.CatalogueText.answer("zh"));
        store.seed(playerUuid.toString(), PAGE, "items: [unclosed\n  0: {");
        String expected = com.ultikits.plugins.remotebag.i18n.CatalogueText.text("zh", "log_bag_deserialize_failed");

        service.loadBagIfNeeded(playerUuid);

        verify(pluginLogger).warn(org.mockito.ArgumentMatchers.any(Throwable.class), org.mockito.ArgumentMatchers.eq(expected));
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
    @DisplayName("An out-of-range slot index is skipped instead of sizing an array from it")
    void anAbsurdSlotIndexDoesNotDriveTheAllocation() {
        // Sizing the array from the highest stored index makes that index an allocation request from
        // untrusted stored data: a hand-edited row carrying items.100000000 would ask for an array of
        // hundreds of megabytes, and the resulting OutOfMemoryError is an Error, so the surrounding
        // catch (Exception) would not contain it. Raised as a P2 on pull request #34; the fixed-size
        // allocation this replaced could not do that.
        //
        // The index is 1,000 rather than 100,000,000 deliberately. The assertion is the INVARIANT --
        // the resulting array's length against the bound -- not the symptom, because whether an
        // allocation actually throws depends on the heap while the defect does not. With an index
        // large enough to exhaust the heap, removing the bound would throw OutOfMemoryError inside the
        // shared Surefire fork instead of failing this one test, so the mutation proof's contents
        // would depend on -Xmx and could destabilise every other class in the fork.
        // aSlotJustBeyondTheCeilingIsSkipped below pins the same invariant one index past the bound.
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("items.1000", new ItemStack(Material.DIAMOND));
        yaml.set("items.2", new ItemStack(Material.EMERALD));
        store.seed(playerUuid.toString(), PAGE, yaml.saveToString());

        service.loadBagIfNeeded(playerUuid);
        ItemStack[] page = service.getBagPage(playerUuid, PAGE);

        assertThat(page).isNotNull();
        assertThat(page.length)
                .as("no stored key may size the page beyond the loader's tolerance band")
                .isLessThanOrEqualTo(54);
        assertThat(page[2])
                .as("the addressable item still loads")
                .isEqualTo(new ItemStack(Material.EMERALD));
    }

    @Test
    @DisplayName("Slot 54 -- the first index the bound must reject -- is rejected")
    void theFirstOutOfRangeIndexIsRejected() {
        // Slot 53 is asserted kept below and slot 1000 above, which an off-by-one in the comparison
        // (> instead of >=) would satisfy both of. 54 is the one index that distinguishes them.
        store.seed(playerUuid.toString(), PAGE, yamlWith(54, new ItemStack(Material.DIAMOND)));

        service.loadBagIfNeeded(playerUuid);
        ItemStack[] page = service.getBagPage(playerUuid, PAGE);

        assertThat(page).isNotNull();
        assertThat(page.length)
                .as("54 is one past the largest addressable slot, so it must not size the page")
                .isLessThanOrEqualTo(54);
    }

    @Test
    @DisplayName("A slot just beyond the ceiling is skipped without a large allocation")
    void aSlotJustBeyondTheCeilingIsSkipped() {
        // Detectable with a 61-element array at worst, so the bound's absence is observable in a
        // mutation run whatever the heap is.
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("items.60", new ItemStack(Material.DIAMOND));
        yaml.set("items.2", new ItemStack(Material.EMERALD));
        store.seed(playerUuid.toString(), PAGE, yaml.saveToString());

        service.loadBagIfNeeded(playerUuid);
        ItemStack[] page = service.getBagPage(playerUuid, PAGE);

        assertThat(page.length)
                .as("the page is sized by the ceiling, not by the stored key")
                .isLessThanOrEqualTo(54);
        assertThat(page[2])
                .as("the addressable item still loads")
                .isEqualTo(new ItemStack(Material.EMERALD));
    }

    @Test
    @DisplayName("A signed slot key is rejected rather than silently colliding with its plain form")
    void aNonCanonicalKeyDoesNotCollideWithItsPlainForm() {
        // Integer.parseInt("+5") is 5, so items.'+5' and items.'5' used to land on one map entry: the
        // second put won and the first item disappeared with no warning, the only path in the
        // deserializer that discarded an entry silently (pull request #34 gate-1 review, IN-10).
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("items.5", new ItemStack(Material.DIAMOND));
        yaml.set("items.+5", new ItemStack(Material.EMERALD));
        String seeded = yaml.saveToString();
        assertThat(yaml.getConfigurationSection("items").getKeys(false))
                .as("precondition: both keys really exist side by side -- if YAML folded '+5' into '5' "
                        + "there would be no collision here to reject")
                .contains("5", "+5");
        store.seed(playerUuid.toString(), PAGE, seeded);

        service.loadBagIfNeeded(playerUuid);
        ItemStack[] page = service.getBagPage(playerUuid, PAGE);

        assertThat(page[5])
                .as("the canonical key's item is the one that survives, not whichever was iterated last")
                .isEqualTo(new ItemStack(Material.DIAMOND));
        assertThat(page)
                .as("and the non-canonical key contributed nothing anywhere in the page")
                .doesNotContain(new ItemStack(Material.EMERALD));
    }

    @Test
    @DisplayName("A zero-padded slot key is rejected for the same reason")
    void aPaddedKeyIsAlsoRejected() {
        // The sibling of the case above, from the same defect class: '05' parses to 5 too. Fixing only
        // the reported instance would have left this one.
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("items.7", new ItemStack(Material.DIAMOND));
        yaml.set("items.07", new ItemStack(Material.EMERALD));
        assertThat(yaml.getConfigurationSection("items").getKeys(false))
                .as("precondition: both keys really exist side by side")
                .contains("7", "07");
        store.seed(playerUuid.toString(), PAGE, yaml.saveToString());

        service.loadBagIfNeeded(playerUuid);
        ItemStack[] page = service.getBagPage(playerUuid, PAGE);

        assertThat(page[7]).isEqualTo(new ItemStack(Material.DIAMOND));
        assertThat(page).doesNotContain(new ItemStack(Material.EMERALD));
    }

    @Test
    @DisplayName("A slot inside the loader's tolerance band still survives")
    void aSlotWithinTheLargestLegalPageIsKept() {
        store.seed(playerUuid.toString(), PAGE, yamlWith(53, new ItemStack(Material.DIAMOND)));

        service.loadBagIfNeeded(playerUuid);
        ItemStack[] page = service.getBagPage(playerUuid, PAGE);

        assertThat(page[53])
                .as("control: bounding the allocation must not reintroduce the loss it replaced")
                .isEqualTo(new ItemStack(Material.DIAMOND));
    }

    @Test
    @DisplayName("A page stored entirely inside the capacity loads at exactly the capacity")
    void anInRangePageKeepsTheConfiguredSize() {
        store.seed(playerUuid.toString(), PAGE, yamlWith(3, new ItemStack(Material.DIAMOND)));

        service.loadBagIfNeeded(playerUuid);
        ItemStack[] page = service.getBagPage(playerUuid, PAGE);

        assertThat(page.length)
                .as("control: with every index inside the capacity the array is exactly the "
                        + "capacity, so the grow path above is a grow and not the default")
                .isEqualTo(45);
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
