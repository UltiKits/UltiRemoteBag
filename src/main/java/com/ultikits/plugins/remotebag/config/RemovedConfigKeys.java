package com.ultikits.plugins.remotebag.config;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Settings this module used to declare in {@code config/remotebag.yml} and no longer reads, and the
 * warning that tells an operator whose file still holds one.
 *
 * <p><b>Why a module needs this at all.</b> Deleting a {@code @ConfigEntry} field removes the key
 * from the code, not from anybody's disk. The framework writes a missing key's declared default
 * into the operator's file on first boot and never deletes a key it no longer declares
 * ({@code AbstractConfigEntity#init}), so every server that has ever run this module still has all
 * of the keys below in its file, carrying whatever value its operator last set. Without this
 * warning the only observable consequence of the removal, for that operator, is that a setting they
 * had edited quietly stops being mentioned anywhere — which is indistinguishable from it still
 * working.
 *
 * <p>Each line names the module, the file and the key, and says where the setting's job went
 * instead, because "this no longer does anything" without "here is what does" only tells half of
 * what the operator has to act on.
 *
 * <p>The check is deliberately scoped to an explicit list rather than derived by comparing the file
 * against the declared fields: an unknown key in the file is not necessarily a key this module ever
 * had (a typo, a hand-written note, another tool's key), and reporting all of them would make the
 * real removals harder to see, not easier.
 */
public final class RemovedConfigKeys {

    /**
     * The removed keys, as each path appears in the file, in the order their warnings are logged.
     * What became of each one is the language file's text, chosen in {@link #reasonFor}.
     */
    private static final String[] REMOVED = {
            "auto_save_interval",
            "gui_title",
            "messages.no_permission",
            "messages.page_locked",
            "rows_per_page",
            "save_on_close",
            "messages.bag_saved",
    };

    private RemovedConfigKeys() {
    }

    /**
     * Where one removed key's job went, from the language file. Each removed key names its own entry,
     * so a key added to {@link #REMOVED} without a case fails loudly instead of being given another
     * key's explanation.
     */
    private static String reasonFor(String removedKey, UltiToolsPlugin plugin) {
        switch (removedKey) {
            case "auto_save_interval":
                return plugin.i18n("removed_key_reason_auto_save_interval");
            case "gui_title":
                return plugin.i18n("removed_key_reason_gui_title");
            case "messages.no_permission":
                return plugin.i18n("removed_key_reason_no_permission");
            case "messages.page_locked":
                return plugin.i18n("removed_key_reason_page_locked");
            case "rows_per_page":
                return plugin.i18n("removed_key_reason_rows_per_page");
            case "save_on_close":
                return plugin.i18n("removed_key_reason_save_on_close");
            case "messages.bag_saved":
                return plugin.i18n("removed_key_reason_bag_saved");
            default:
                throw new IllegalStateException("No guidance for removed key " + removedKey);
        }
    }

    /**
     * Logs one warning per removed key that is still present in the operator's file, in the server's
     * language.
     *
     * <p>Reads {@code config.getConfig()}, which is the parsed file as it is on disk — including
     * keys this entity no longer declares, which is exactly what a residual key is. A fresh install
     * has none of them, so a clean server logs nothing.
     *
     * @param config the module's configuration entity, after the framework has loaded it; may be
     *               null, or hold no parsed file or plugin yet, in which case nothing is reported
     * @param logger the module's logger; may be null, in which case nothing is reported
     */
    public static void warnIfStillPresent(RemoteBagConfig config, PluginLogger logger) {
        if (config == null || logger == null) {
            return;
        }
        YamlConfiguration onDisk = config.getConfig();
        if (onDisk == null) {
            return;
        }
        // The plugin the framework bound the configuration to when it loaded the file; it is set
        // before the file is read, so a parsed file without it is not one the framework loaded.
        UltiToolsPlugin plugin = config.getUltiToolsPlugin();
        if (plugin == null) {
            return;
        }
        String file = config.getConfigFilePath();
        for (String removed : REMOVED) {
            if (onDisk.contains(removed)) {
                logger.warn(plugin.i18n("removed_key_warning")
                        .replace("{FILE}", String.valueOf(file))
                        .replace("{REASON}", reasonFor(removed, plugin))
                        .replace("{KEY}", removed));
            }
        }
    }
}
