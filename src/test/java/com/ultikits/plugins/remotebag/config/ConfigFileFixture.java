package com.ultikits.plugins.remotebag.config;

import com.ultikits.plugins.remotebag.UltiRemoteBag;
import com.ultikits.plugins.remotebag.i18n.CatalogueText;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test support: a module plugin the framework will load {@link RemoteBagConfig} against, with a real
 * configuration folder and the module's real catalogue for one language.
 * <p>
 * {@code UltiToolsPlugin#getConfigFile} is {@code protected final}, so a plain mock cannot be told where
 * the file is; it is stubbed through reflection instead, which leaves the framework's own
 * {@code AbstractConfigEntity#init} to read and write the file exactly as it does on a server.
 */
final class ConfigFileFixture {

    static final String CONFIG_FILE = "config/remotebag.yml";

    private ConfigFileFixture() {
    }

    /** A mock module whose configuration folder is {@code folder} and whose {@code i18n} answers in {@code language}. */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // getConfigFile is protected final on the framework base class
    static UltiRemoteBag plugin(Path folder, String language) {
        try {
            UltiRemoteBag plugin = mock(UltiRemoteBag.class);
            when(plugin.getPluginName()).thenReturn("UltiRemoteBag");
            when(plugin.i18n(anyString())).thenAnswer(CatalogueText.answer(language));
            Method configFile = UltiToolsPlugin.class.getDeclaredMethod("getConfigFile", String.class);
            configFile.setAccessible(true);
            when(configFile.invoke(plugin, anyString())).thenAnswer(
                    inv -> new File(folder.toFile(), inv.<String>getArgument(0)));
            return plugin;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot stub the module's configuration folder", e);
        }
    }

    /** Loads (and, on a fresh folder, writes) the configuration file as the framework does at module start. */
    static RemoteBagConfig load(UltiToolsPlugin plugin) {
        try {
            RemoteBagConfig config = new RemoteBagConfig(CONFIG_FILE);
            config.init(plugin);
            return config;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot load " + CONFIG_FILE, e);
        }
    }
}
