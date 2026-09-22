package com.ultikits.plugins.remotebag.config;

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

    /** The module name each warning names, matching this module's own runtime name. */
    private static final String MODULE = "UltiRemoteBag";

    /**
     * One entry per removed key: the key path as it appears in the file, then where its job went.
     * The second element completes the sentence "... and can be deleted from the file — %s."
     */
    private static final String[][] REMOVED = {
            {"auto_save_interval",
                    "the periodic auto-save it named was removed because it had nothing to do; "
                            + "every write to a bag page is already persisted in the same action "
                            + "(UltiKits/UltiRemoteBag#13, UltiKits/UltiRemoteBag#23)"},
            {"gui_title",
                    "a bag page's title comes from this module's language files, key 'bag_name' in "
                            + "lang/en.yml and lang/zh.yml (UltiKits/UltiRemoteBag#14)"},
            {"messages.no_permission",
                    "a permission refusal comes from UltiTools' own translated message, so it "
                            + "already follows the server's 'language' setting "
                            + "(UltiKits/UltiRemoteBag#15)"},
            {"messages.page_locked",
                    "asking for a page you may not open answers with this module's language files, "
                            + "key 'page_out_of_range' (UltiKits/UltiRemoteBag#16)"},
            {"rows_per_page",
                    "a bag page holds a fixed 45 slots; the setting was removed rather than "
                            + "documented because it did not decide that in either direction -- it "
                            + "was the denominator of the main menu's 'Slots Used' line, which at "
                            + "its own default read 45/54 for a full page, and "
                            + "UltiKits/UltiRemoteBag#38 records making capacity genuinely "
                            + "configurable (UltiKits/UltiRemoteBag#24)"},
            {"save_on_close",
                    "closing a bag page in edit mode always saves it, which is what this module has "
                            + "always done; the switch was removed rather than wired because turning "
                            + "it off destroyed items the player had dragged into the window, and "
                            + "UltiKits/UltiRemoteBag#37 records what an implementation would have to "
                            + "do instead (UltiKits/UltiRemoteBag#18)"},
            {"messages.bag_saved",
                    "the '/bag save' confirmation comes from this module's language files, key "
                            + "'bag_saved_manually' (UltiKits/UltiRemoteBag#17)"},
    };

    private RemovedConfigKeys() {
    }

    /**
     * Logs one warning per removed key that is still present in the operator's file.
     *
     * <p>Reads {@code config.getConfig()}, which is the parsed file as it is on disk — including
     * keys this entity no longer declares, which is exactly what a residual key is. A fresh install
     * has none of them, so a clean server logs nothing.
     *
     * @param config the module's configuration entity, after the framework has loaded it; may be
     *               null, or hold no parsed file yet, in which case nothing is reported
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
        String file = config.getConfigFilePath();
        for (String[] removed : REMOVED) {
            if (onDisk.contains(removed[0])) {
                logger.warn(String.format(
                        "%s: '%s' in %s no longer has any effect and can be deleted from the file"
                                + " -- %s.",
                        MODULE, removed[0], file, removed[1]));
            }
        }
    }
}
