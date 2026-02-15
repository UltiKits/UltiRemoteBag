package com.ultikits.plugins.remotebag.config;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("RemoteBagConfig Tests")
class RemoteBagConfigTest {

    @Nested
    @DisplayName("Default Values")
    class DefaultValues {

        @Test
        @DisplayName("Should have default pages = 1")
        void defaultPages() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getDefaultPages()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should have max pages = 10")
        void maxPages() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getMaxPages()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should have rows per page = 6")
        void rowsPerPage() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getRowsPerPage()).isEqualTo(6);
        }

        @Test
        @DisplayName("Should have permission based pages enabled by default")
        void permissionBasedPages() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.isPermissionBasedPages()).isTrue();
        }

        @Test
        @DisplayName("Should have permission prefix = ultibag.pages.")
        void permissionPrefix() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getPermissionPrefix()).isEqualTo("ultibag.pages.");
        }

        @Test
        @DisplayName("Should have auto save interval = 300 seconds")
        void autoSaveInterval() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getAutoSaveInterval()).isEqualTo(300);
        }

        @Test
        @DisplayName("Should have save on close enabled by default")
        void saveOnClose() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.isSaveOnClose()).isTrue();
        }

        @Test
        @DisplayName("Should have economy enabled by default")
        void economyEnabled() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.isEconomyEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have base price = 10000")
        void basePrice() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getBasePrice()).isEqualTo(10000);
        }

        @Test
        @DisplayName("Should have price increase enabled by default")
        void priceIncreaseEnabled() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.isPriceIncreaseEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have price increase rate = 0.1")
        void priceIncreaseRate() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getPriceIncreaseRate()).isEqualTo(0.1);
        }

        @Test
        @DisplayName("Should have sound enabled by default")
        void soundEnabled() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.isSoundEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have lock timeout = 300 seconds")
        void lockTimeout() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getLockTimeout()).isEqualTo(300);
        }

        @Test
        @DisplayName("Should have notify readonly viewers enabled by default")
        void notifyReadonlyViewers() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.isNotifyReadonlyViewers()).isTrue();
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("Should update default pages")
        void setDefaultPages() {
            RemoteBagConfig config = createRealConfig();
            config.setDefaultPages(2);
            assertThat(config.getDefaultPages()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should update max pages")
        void setMaxPages() {
            RemoteBagConfig config = createRealConfig();
            config.setMaxPages(20);
            assertThat(config.getMaxPages()).isEqualTo(20);
        }

        @Test
        @DisplayName("Should update rows per page")
        void setRowsPerPage() {
            RemoteBagConfig config = createRealConfig();
            config.setRowsPerPage(3);
            assertThat(config.getRowsPerPage()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should update permission based pages")
        void setPermissionBasedPages() {
            RemoteBagConfig config = createRealConfig();
            config.setPermissionBasedPages(false);
            assertThat(config.isPermissionBasedPages()).isFalse();
        }

        @Test
        @DisplayName("Should update permission prefix")
        void setPermissionPrefix() {
            RemoteBagConfig config = createRealConfig();
            config.setPermissionPrefix("bag.pages.");
            assertThat(config.getPermissionPrefix()).isEqualTo("bag.pages.");
        }

        @Test
        @DisplayName("Should update auto save interval")
        void setAutoSaveInterval() {
            RemoteBagConfig config = createRealConfig();
            config.setAutoSaveInterval(600);
            assertThat(config.getAutoSaveInterval()).isEqualTo(600);
        }

        @Test
        @DisplayName("Should update save on close")
        void setSaveOnClose() {
            RemoteBagConfig config = createRealConfig();
            config.setSaveOnClose(false);
            assertThat(config.isSaveOnClose()).isFalse();
        }

        @Test
        @DisplayName("Should update economy enabled")
        void setEconomyEnabled() {
            RemoteBagConfig config = createRealConfig();
            config.setEconomyEnabled(false);
            assertThat(config.isEconomyEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update base price")
        void setBasePrice() {
            RemoteBagConfig config = createRealConfig();
            config.setBasePrice(20000);
            assertThat(config.getBasePrice()).isEqualTo(20000);
        }

        @Test
        @DisplayName("Should update price increase enabled")
        void setPriceIncreaseEnabled() {
            RemoteBagConfig config = createRealConfig();
            config.setPriceIncreaseEnabled(false);
            assertThat(config.isPriceIncreaseEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update price increase rate")
        void setPriceIncreaseRate() {
            RemoteBagConfig config = createRealConfig();
            config.setPriceIncreaseRate(0.2);
            assertThat(config.getPriceIncreaseRate()).isEqualTo(0.2);
        }

        @Test
        @DisplayName("Should update sound enabled")
        void setSoundEnabled() {
            RemoteBagConfig config = createRealConfig();
            config.setSoundEnabled(false);
            assertThat(config.isSoundEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update lock timeout")
        void setLockTimeout() {
            RemoteBagConfig config = createRealConfig();
            config.setLockTimeout(600);
            assertThat(config.getLockTimeout()).isEqualTo(600);
        }

        @Test
        @DisplayName("Should update notify readonly viewers")
        void setNotifyReadonlyViewers() {
            RemoteBagConfig config = createRealConfig();
            config.setNotifyReadonlyViewers(false);
            assertThat(config.isNotifyReadonlyViewers()).isFalse();
        }
    }

    @Nested
    @DisplayName("Message Defaults")
    class MessageDefaults {

        @Test
        @DisplayName("Should have default no permission message")
        void noPermissionMessage() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getNoPermissionMessage()).contains("没有权限");
        }

        @Test
        @DisplayName("Should have default page locked message")
        void pageLockedMessage() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getPageLockedMessage()).contains("{PAGE}");
        }

        @Test
        @DisplayName("Should have default bag saved message")
        void bagSavedMessage() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getBagSavedMessage()).contains("保存");
        }
    }

    @Nested
    @DisplayName("Sound Defaults")
    class SoundDefaults {

        @Test
        @DisplayName("Should have default open sound")
        void openSound() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getOpenSound()).isEqualTo("BLOCK_CHEST_OPEN");
        }

        @Test
        @DisplayName("Should have default close sound")
        void closeSound() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getCloseSound()).isEqualTo("BLOCK_CHEST_CLOSE");
        }

        @Test
        @DisplayName("Should have default purchase sound")
        void purchaseSound() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getPurchaseSound()).isEqualTo("ENTITY_PLAYER_LEVELUP");
        }

        @Test
        @DisplayName("Should have default error sound")
        void errorSound() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getErrorSound()).isEqualTo("ENTITY_VILLAGER_NO");
        }

        @Test
        @DisplayName("Should have default sound volume")
        void soundVolume() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getSoundVolume()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Should have default sound pitch")
        void soundPitch() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getSoundPitch()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Message Setters")
    class MessageSetters {

        @Test
        @DisplayName("Should update no permission message")
        void setNoPermissionMessage() {
            RemoteBagConfig config = createRealConfig();
            config.setNoPermissionMessage("&cNo Permission!");
            assertThat(config.getNoPermissionMessage()).isEqualTo("&cNo Permission!");
        }

        @Test
        @DisplayName("Should update page locked message")
        void setPageLockedMessage() {
            RemoteBagConfig config = createRealConfig();
            config.setPageLockedMessage("&cLocked!");
            assertThat(config.getPageLockedMessage()).isEqualTo("&cLocked!");
        }

        @Test
        @DisplayName("Should update bag saved message")
        void setBagSavedMessage() {
            RemoteBagConfig config = createRealConfig();
            config.setBagSavedMessage("&aSaved!");
            assertThat(config.getBagSavedMessage()).isEqualTo("&aSaved!");
        }
    }

    @Nested
    @DisplayName("Sound Setters")
    class SoundSetters {

        @Test
        @DisplayName("Should update open sound")
        void setOpenSound() {
            RemoteBagConfig config = createRealConfig();
            config.setOpenSound("CUSTOM_SOUND");
            assertThat(config.getOpenSound()).isEqualTo("CUSTOM_SOUND");
        }

        @Test
        @DisplayName("Should update close sound")
        void setCloseSound() {
            RemoteBagConfig config = createRealConfig();
            config.setCloseSound("CUSTOM_CLOSE");
            assertThat(config.getCloseSound()).isEqualTo("CUSTOM_CLOSE");
        }

        @Test
        @DisplayName("Should update purchase sound")
        void setPurchaseSound() {
            RemoteBagConfig config = createRealConfig();
            config.setPurchaseSound("CUSTOM_PURCHASE");
            assertThat(config.getPurchaseSound()).isEqualTo("CUSTOM_PURCHASE");
        }

        @Test
        @DisplayName("Should update error sound")
        void setErrorSound() {
            RemoteBagConfig config = createRealConfig();
            config.setErrorSound("CUSTOM_ERROR");
            assertThat(config.getErrorSound()).isEqualTo("CUSTOM_ERROR");
        }

        @Test
        @DisplayName("Should update sound volume")
        void setSoundVolume() {
            RemoteBagConfig config = createRealConfig();
            config.setSoundVolume(0.5);
            assertThat(config.getSoundVolume()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("Should update sound pitch")
        void setSoundPitch() {
            RemoteBagConfig config = createRealConfig();
            config.setSoundPitch(1.5);
            assertThat(config.getSoundPitch()).isEqualTo(1.5);
        }
    }

    @Nested
    @DisplayName("GUI Title")
    class GuiTitleTests {

        @Test
        @DisplayName("Should have default GUI title with placeholders")
        void defaultGuiTitle() {
            RemoteBagConfig config = createRealConfig();
            assertThat(config.getGuiTitle()).contains("{PAGE}");
            assertThat(config.getGuiTitle()).contains("{MAX}");
        }

        @Test
        @DisplayName("Should update GUI title")
        void setGuiTitle() {
            RemoteBagConfig config = createRealConfig();
            config.setGuiTitle("&bCustom Title");
            assertThat(config.getGuiTitle()).isEqualTo("&bCustom Title");
        }
    }

    /**
     * Create a real RemoteBagConfig using a mock path to avoid AbstractConfigEntity I/O.
     * We use Mockito spy to bypass the superclass constructor's file loading.
     */
    private RemoteBagConfig createRealConfig() {
        RemoteBagConfig config = mock(RemoteBagConfig.class, withSettings().useConstructor("config/remotebag.yml").defaultAnswer(CALLS_REAL_METHODS));
        return config;
    }
}
