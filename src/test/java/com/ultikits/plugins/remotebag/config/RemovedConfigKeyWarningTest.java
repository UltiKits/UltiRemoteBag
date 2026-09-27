package com.ultikits.plugins.remotebag.config;

import com.ultikits.plugins.remotebag.UltiRemoteBag;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.assertj.core.api.Condition;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Five settings were removed from {@code config/remotebag.yml} because none of them ever took
 * effect (UltiKits/UltiRemoteBag#13, #14, #15, #16, #17, #23). Removing a key from the code does
 * NOT remove it from an operator's file: the framework writes a missing key's default into the
 * file on first boot and never deletes a key it no longer declares
 * ({@code AbstractConfigEntity#init}), so every server that has ever run this module still has all
 * five on disk, with the value the operator may have edited.
 *
 * <p>These cases pin the warning that tells that operator. Each one must name the module, the file
 * and the key, and say where the setting went instead.
 *
 * <h2>Controls</h2>
 * A check that never fires and a server with no residual key look identical in the log, so two
 * controls sit beside the five positive cases:
 * <ul>
 *   <li>{@link #warnsNothingWhenNoRemovedKeyIsLeftInTheFile()} — a file holding only keys that are
 *       still declared produces no warning at all, so a warning is evidence of a residual key
 *       rather than of boot;</li>
 *   <li>{@link #doesNotWarnAboutAKeyThatIsStillDeclared()} — a file holding {@code max_pages}, a
 *       key this module still reads, produces no warning, so the check discriminates between "this
 *       key is in your file" and "this key is in your file and is dead".</li>
 * </ul>
 * Both controls would also pass against a check that does nothing; the five positive cases are
 * what make that impossible, and the two controls are what make the positive cases meaningful.
 */
@DisplayName("Residual removed-key warning (UltiRemoteBag#13/#14/#15/#16/#17)")
class RemovedConfigKeyWarningTest {

    private static final String CONFIG_FILE = "config/remotebag.yml";

    @Test
    @DisplayName("Should name auto_save_interval when it is still in the operator's file")
    void warnsAboutResidualAutoSaveInterval() {
        assertWarnedAbout("auto_save_interval", 300);
    }

    @Test
    @DisplayName("Should name gui_title when it is still in the operator's file")
    void warnsAboutResidualGuiTitle() {
        assertWarnedAbout("gui_title", "&6a title");
    }

    @Test
    @DisplayName("Should name messages.no_permission when it is still in the operator's file")
    void warnsAboutResidualNoPermissionMessage() {
        assertWarnedAbout("messages.no_permission", "&cnope");
    }

    @Test
    @DisplayName("Should name messages.page_locked when it is still in the operator's file")
    void warnsAboutResidualPageLockedMessage() {
        assertWarnedAbout("messages.page_locked", "&cnope");
    }

    @Test
    @DisplayName("Should name messages.bag_saved when it is still in the operator's file")
    void warnsAboutResidualBagSavedMessage() {
        assertWarnedAbout("messages.bag_saved", "&asaved");
    }

    @Test
    @DisplayName("Should warn once per residual key when several are left in the file")
    void warnsOncePerResidualKey() {
        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.set("auto_save_interval", 300);
        onDisk.set("gui_title", "&6a title");
        onDisk.set("messages.no_permission", "&cnope");
        onDisk.set("messages.page_locked", "&cnope");
        onDisk.set("messages.bag_saved", "&asaved");

        List<String> warnings = bootWith(onDisk);

        assertThat(warnings).hasSize(5);
        assertThat(warnings)
                .areExactly(1, containing("auto_save_interval"))
                .areExactly(1, containing("gui_title"))
                .areExactly(1, containing("messages.no_permission"))
                .areExactly(1, containing("messages.page_locked"))
                .areExactly(1, containing("messages.bag_saved"));
    }

    // ==================== controls ====================

    @Test
    @DisplayName("Control: a file with no removed key left in it produces no warning")
    void warnsNothingWhenNoRemovedKeyIsLeftInTheFile() {
        assertThat(bootWith(new YamlConfiguration())).isEmpty();
    }

    @Test
    @DisplayName("Control: a key this module still reads is not reported as removed")
    void doesNotWarnAboutAKeyThatIsStillDeclared() {
        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.set("max_pages", 10);

        assertThat(bootWith(onDisk)).isEmpty();
    }

    @Test
    @DisplayName("Under language: en the warning is, word for word, the English line earlier versions printed")
    void englishWarningIsWordForWordUnchanged() {
        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.set("auto_save_interval", 300);

        assertThat(bootWith(onDisk)).containsExactly("UltiRemoteBag: 'auto_save_interval' in config/remotebag.yml"
                + " no longer has any effect and can be deleted from the file -- the periodic auto-save it named"
                + " was removed because it had nothing to do; every write to a bag page is already persisted in"
                + " the same action (UltiKits/UltiRemoteBag#13, UltiKits/UltiRemoteBag#23).");
    }

    @Test
    @DisplayName("Under language: zh the warning is the Chinese catalogue text, naming the file and the key")
    void warningFollowsTheLanguageSetting() {
        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.set("gui_title", "anything");
        String expected = com.ultikits.plugins.remotebag.i18n.CatalogueText.text("zh", "removed_key_warning").replace("{FILE}", CONFIG_FILE)
                .replace("{REASON}", com.ultikits.plugins.remotebag.i18n.CatalogueText.text("zh", "removed_key_reason_gui_title"))
                .replace("{KEY}", "gui_title");

        assertThat(bootWith(onDisk, "zh")).containsExactly(expected);
    }

    // ==================== helpers ====================

    private void assertWarnedAbout(String removedKey, Object valueOnDisk) {
        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.set(removedKey, valueOnDisk);

        List<String> warnings = bootWith(onDisk);

        assertThat(warnings)
                .withFailMessage("expected exactly one warning naming '%s', got %s",
                        removedKey, warnings)
                .hasSize(1);
        assertThat(warnings.get(0))
                .contains("UltiRemoteBag")
                .contains(CONFIG_FILE)
                .contains(removedKey);
    }

    /**
     * Boots the module against an operator file whose contents are {@code onDisk}, and returns
     * every line the module logged at WARN level while doing so.
     *
     * <p>{@code AbstractConfigEntity#getConfig()} is the parsed operator file, including keys the
     * entity no longer declares — which is exactly what a residual key is — so stubbing it is how
     * a unit test presents "this key is still on disk".
     */
    private List<String> bootWith(YamlConfiguration onDisk) {
        return bootWith(onDisk, "en");
    }

    private List<String> bootWith(YamlConfiguration onDisk, String language) {
        UltiRemoteBag plugin = mock(UltiRemoteBag.class);
        PluginLogger logger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(logger);

        RemoteBagConfig config = mock(RemoteBagConfig.class);
        when(config.getConfig()).thenReturn(onDisk);
        when(config.getConfigFilePath()).thenReturn(CONFIG_FILE);
        // The framework binds the configuration to the plugin that loaded it; the warning's text comes
        // from that plugin's language file, answered here from the real catalogue for `language`.
        when(config.getUltiToolsPlugin()).thenReturn(plugin);
        when(plugin.i18n(org.mockito.ArgumentMatchers.anyString())).thenAnswer(com.ultikits.plugins.remotebag.i18n.CatalogueText.answer(language));

        SimpleContainer context = mock(SimpleContainer.class);
        when(plugin.getContext()).thenReturn(context);
        when(context.getBean(RemoteBagService.class)).thenReturn(null);
        when(context.getBean(BagLockService.class)).thenReturn(null);
        when(context.getBean(RemoteBagConfig.class)).thenReturn(config);

        when(plugin.registerSelf()).thenCallRealMethod();
        plugin.registerSelf();

        ArgumentCaptor<String> warned = ArgumentCaptor.forClass(String.class);
        verify(logger, atLeast(0)).warn(warned.capture());
        return warned.getAllValues();
    }

    private static Condition<String> containing(String needle) {
        return new Condition<String>(line -> line.contains(needle), "a line naming %s", needle);
    }
}
