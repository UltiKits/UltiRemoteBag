package com.ultikits.plugins.remotebag;

import com.ultikits.plugins.remotebag.i18n.CatalogueText;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.TaskManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("Released framework refusal lifecycle")
class FrameworkRefusalTest {
    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    @DisplayName("Refuses before module initialization and the real 6.3.0 refusal branch registers nothing")
    void refusesWithoutRegistrations(String language) throws Exception {
        UltiRemoteBag module = mock(UltiRemoteBag.class);
        PluginLogger log = mock(PluginLogger.class);
        SimpleContainer context = mock(SimpleContainer.class);
        when(module.registerSelf()).thenCallRealMethod();
        when(module.getLogger()).thenReturn(log);
        when(module.getContext()).thenReturn(context);
        when(module.getPluginName()).thenReturn("UltiRemoteBag");
        when(module.getVersion()).thenReturn("2.0.0");
        when(module.i18n(anyString())).thenAnswer(CatalogueText.answer(language));
        org.bukkit.plugin.PluginManager bukkitPlugins = mock(org.bukkit.plugin.PluginManager.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        Plugin corePlugin = mock(Plugin.class);
        when(corePlugin.getDescription()).thenReturn(new PluginDescriptionFile("UltiTools", "6.3.0", "core.Main"));
        when(bukkitPlugins.getPlugin("UltiTools")).thenReturn(corePlugin);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getBukkitVersion).thenReturn("1.20.6-R0.1-SNAPSHOT");
            bukkit.when(Bukkit::getPluginManager).thenReturn(bukkitPlugins);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger("refusal-test"));
            // This assertion is the RED control before a guard exists, not a constructor/lookup failure.
            assertThat(module.registerSelf()).isFalse();
            verifyNoInteractions(context, scheduler);
            verify(log).error(CatalogueText.text(language, "framework_compatibility_refused")
                    .replace("{0}", "1.20.6-R0.1-SNAPSHOT").replace("{1}", "6.3.0"));
            verify(log, never()).info(anyString());
            clearInvocations(module, log);

            // Bypass JavaPlugin bootstrap only: execute the released manager's actual refusal code.
            UltiTools core = new ObjenesisStd().newInstance(UltiTools.class);
            Object previous = UltiRemoteBagTestHelper.getStaticField(UltiTools.class, "ultiTools");
            CommandManager commands = mock(CommandManager.class);
            ListenerManager listeners = mock(ListenerManager.class);
            UltiRemoteBagTestHelper.setField(core, "commandManager", commands);
            UltiRemoteBagTestHelper.setField(core, "listenerManager", listeners);
            com.ultikits.ultitools.manager.PluginManager manager = new com.ultikits.ultitools.manager.PluginManager();
            TaskManager tasks = mock(TaskManager.class);
            setField(manager, "taskManager", tasks);
            UltiRemoteBagTestHelper.setStaticField(UltiTools.class, "ultiTools", core);
            try {
                Method attempt = manager.getClass().getDeclaredMethod("attemptPluginRegistration", UltiToolsPlugin.class);
                attempt.setAccessible(true);
                assertThat(attempt.invoke(manager, module)).isEqualTo(false);
                verify(module).registerSelf();
                verify(context).close();
                verifyNoMoreInteractions(context);
                assertThat(manager.getPluginList()).doesNotContain(module).isEmpty();
                verifyNoInteractions(commands, listeners, tasks, scheduler);
                verify(bukkitPlugins, never()).disablePlugin(any(Plugin.class));
                verify(bukkitPlugins, never()).registerEvents(any(), any());
                verify(module, never()).unregisterSelf();
                verify(log).error(anyString());
                verify(log, never()).info(anyString());
            } finally {
                UltiRemoteBagTestHelper.setStaticField(UltiTools.class, "ultiTools", previous);
            }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "1.20.6,6.4.0,false", "1.20.6,6.4.0-SNAPSHOT,false",
            "1.21.11,6.3.0,false", "garbage,6.3.0,true", "1.20.6,garbage,true",
            "1.20.6,,true"
    })
    @DisplayName("Accepted combinations initialize normally; unknown versions warn and continue")
    void continues(String server, String framework, boolean warning) {
        UltiRemoteBag module = mock(UltiRemoteBag.class);
        SimpleContainer context = mock(SimpleContainer.class);
        PluginLogger log = mock(PluginLogger.class);
        when(module.registerSelf()).thenCallRealMethod();
        when(module.getContext()).thenReturn(context);
        when(module.getLogger()).thenReturn(log);
        when(module.i18n(anyString())).thenAnswer(CatalogueText.answer("en"));
        com.ultikits.plugins.remotebag.service.RemoteBagService bags = mock(com.ultikits.plugins.remotebag.service.RemoteBagService.class);
        com.ultikits.plugins.remotebag.service.BagEditClaimService claims = mock(com.ultikits.plugins.remotebag.service.BagEditClaimService.class);
        when(context.getBean(com.ultikits.plugins.remotebag.service.RemoteBagService.class)).thenReturn(bags);
        when(context.getBean(com.ultikits.plugins.remotebag.service.BagEditClaimService.class)).thenReturn(claims);
        org.bukkit.plugin.PluginManager manager = mock(org.bukkit.plugin.PluginManager.class);
        if (framework != null) {
            Plugin core = mock(Plugin.class);
            when(core.getDescription()).thenReturn(new PluginDescriptionFile("UltiTools", framework, "core.Main"));
            when(manager.getPlugin("UltiTools")).thenReturn(core);
        }
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getBukkitVersion).thenReturn(server);
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            assertThat(module.registerSelf()).isTrue();
            verify(bags).init();
            verify(claims).init();
            verify(log).info(CatalogueText.text("en", "bag_enabled"));
            verify(log, never()).error(anyString());
            if (warning) {
                verify(log).warn(CatalogueText.text("en", "framework_compatibility_unknown")
                        .replace("{0}", server).replace("{1}", String.valueOf(framework)));
            } else {
                verify(log, never()).warn(anyString());
            }
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
