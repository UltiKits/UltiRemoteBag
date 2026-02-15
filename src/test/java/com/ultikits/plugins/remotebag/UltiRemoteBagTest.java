package com.ultikits.plugins.remotebag;

import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("UltiRemoteBag Main Class Tests")
class UltiRemoteBagTest {

    @AfterEach
    void tearDown() throws Exception {
        UltiRemoteBagTestHelper.tearDown();
    }

    // ==================== registerSelf ====================

    @Nested
    @DisplayName("registerSelf")
    class RegisterSelf {

        @Test
        @DisplayName("Should return true when beans are null")
        void returnsTrueWhenBeansNull() throws Exception {
            UltiRemoteBag plugin = mock(UltiRemoteBag.class);
            PluginLogger logger = mock(PluginLogger.class);
            when(plugin.getLogger()).thenReturn(logger);

            SimpleContainer mockContext = mock(SimpleContainer.class);
            when(plugin.getContext()).thenReturn(mockContext);
            when(mockContext.getBean(any(Class.class))).thenReturn(null);

            when(plugin.registerSelf()).thenCallRealMethod();

            boolean result = plugin.registerSelf();

            assertThat(result).isTrue();
            verify(logger).info("UltiRemoteBag has been enabled!");
        }

        @Test
        @DisplayName("Should initialize bagService when available")
        void initializesBagService() throws Exception {
            UltiRemoteBag plugin = mock(UltiRemoteBag.class);
            PluginLogger logger = mock(PluginLogger.class);
            when(plugin.getLogger()).thenReturn(logger);

            SimpleContainer mockContext = mock(SimpleContainer.class);
            when(plugin.getContext()).thenReturn(mockContext);

            RemoteBagService bagService = mock(RemoteBagService.class);
            when(mockContext.getBean(RemoteBagService.class)).thenReturn(bagService);
            when(mockContext.getBean(BagLockService.class)).thenReturn(null);

            when(plugin.registerSelf()).thenCallRealMethod();

            boolean result = plugin.registerSelf();

            assertThat(result).isTrue();
            verify(bagService).init();
        }

        @Test
        @DisplayName("Should set lock timeout when lockService and config available")
        void setsLockTimeout() throws Exception {
            UltiRemoteBag plugin = mock(UltiRemoteBag.class);
            PluginLogger logger = mock(PluginLogger.class);
            when(plugin.getLogger()).thenReturn(logger);

            SimpleContainer mockContext = mock(SimpleContainer.class);
            when(plugin.getContext()).thenReturn(mockContext);

            RemoteBagService bagService = mock(RemoteBagService.class);
            BagLockService lockService = mock(BagLockService.class);
            RemoteBagConfig config = mock(RemoteBagConfig.class);
            when(config.getLockTimeout()).thenReturn(600);

            when(mockContext.getBean(RemoteBagService.class)).thenReturn(bagService);
            when(mockContext.getBean(BagLockService.class)).thenReturn(lockService);
            when(mockContext.getBean(RemoteBagConfig.class)).thenReturn(config);

            when(plugin.registerSelf()).thenCallRealMethod();

            boolean result = plugin.registerSelf();

            assertThat(result).isTrue();
            verify(lockService).setLockTimeout(600);
        }

        @Test
        @DisplayName("Should not set lock timeout when config is null")
        void doesNotSetLockTimeoutWhenConfigNull() throws Exception {
            UltiRemoteBag plugin = mock(UltiRemoteBag.class);
            PluginLogger logger = mock(PluginLogger.class);
            when(plugin.getLogger()).thenReturn(logger);

            SimpleContainer mockContext = mock(SimpleContainer.class);
            when(plugin.getContext()).thenReturn(mockContext);

            BagLockService lockService = mock(BagLockService.class);
            when(mockContext.getBean(RemoteBagService.class)).thenReturn(null);
            when(mockContext.getBean(BagLockService.class)).thenReturn(lockService);
            when(mockContext.getBean(RemoteBagConfig.class)).thenReturn(null);

            when(plugin.registerSelf()).thenCallRealMethod();

            plugin.registerSelf();

            verify(lockService, never()).setLockTimeout(anyInt());
        }
    }

    // ==================== unregisterSelf ====================

    @Nested
    @DisplayName("unregisterSelf")
    class UnregisterSelf {

        @Test
        @DisplayName("Should log message when service is null")
        void logMessageWhenServiceNull() throws Exception {
            UltiRemoteBag plugin = mock(UltiRemoteBag.class);
            PluginLogger logger = mock(PluginLogger.class);
            when(plugin.getLogger()).thenReturn(logger);

            SimpleContainer mockContext = mock(SimpleContainer.class);
            when(plugin.getContext()).thenReturn(mockContext);
            when(mockContext.getBean(any(Class.class))).thenReturn(null);

            doCallRealMethod().when(plugin).unregisterSelf();

            plugin.unregisterSelf();

            verify(logger).info("UltiRemoteBag has been disabled!");
        }

        @Test
        @DisplayName("Should save all bags when service available")
        void savesAllBagsWhenServiceAvailable() throws Exception {
            UltiRemoteBag plugin = mock(UltiRemoteBag.class);
            PluginLogger logger = mock(PluginLogger.class);
            when(plugin.getLogger()).thenReturn(logger);

            SimpleContainer mockContext = mock(SimpleContainer.class);
            when(plugin.getContext()).thenReturn(mockContext);

            RemoteBagService bagService = mock(RemoteBagService.class);
            when(mockContext.getBean(RemoteBagService.class)).thenReturn(bagService);

            doCallRealMethod().when(plugin).unregisterSelf();

            plugin.unregisterSelf();

            verify(bagService).saveAllBags();
            verify(logger).info("UltiRemoteBag has been disabled!");
        }
    }

    // ==================== reloadSelf ====================

    @Test
    @DisplayName("reloadSelf should log message")
    void reloadSelf() throws Exception {
        UltiRemoteBag plugin = mock(UltiRemoteBag.class);
        PluginLogger logger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(logger);
        doCallRealMethod().when(plugin).reloadSelf();

        plugin.reloadSelf();

        verify(logger).info("UltiRemoteBag configuration reloaded!");
    }

    // ==================== supported ====================

    @Nested
    @DisplayName("supported")
    class Supported {

        @Test
        @DisplayName("Should return zh and en")
        void returnsZhAndEn() throws Exception {
            UltiRemoteBag plugin = mock(UltiRemoteBag.class);
            when(plugin.supported()).thenCallRealMethod();

            List<String> langs = plugin.supported();

            assertThat(langs).containsExactly("zh", "en");
        }

        @Test
        @DisplayName("Should return exactly 2 languages")
        void returnsTwoLanguages() throws Exception {
            UltiRemoteBag plugin = mock(UltiRemoteBag.class);
            when(plugin.supported()).thenCallRealMethod();

            List<String> langs = plugin.supported();

            assertThat(langs).hasSize(2);
        }
    }
}
