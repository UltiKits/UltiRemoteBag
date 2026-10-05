package com.ultikits.plugins.remotebag.config;

import com.ultikits.plugins.remotebag.UltiRemoteBag;
import com.ultikits.plugins.remotebag.i18n.CatalogueText;
import com.ultikits.ultitools.annotations.ConfigEntry;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UltiKits/UltiRemoteBag#48: under {@code language: en} a fresh install writes {@code config/remotebag.yml}
 * with English comments. Every comment that used to be Chinese is now one {@code {key}} token that the
 * framework resolves through this module's catalogue (UltiTools-Reborn#542), in the server's language.
 * <p>
 * These cases load the real {@link RemoteBagConfig} through the framework's own {@code init} against the
 * module's real catalogues, so a token whose key is missing, misspelt or empty in a catalogue shows up as the
 * token itself in the file, which is what the assertions below look for.
 */
@DisplayName("config/remotebag.yml comments follow the server language (UltiRemoteBag#48)")
class RemoteBagConfigCommentsTest {

    /** The thirteen settings whose comments were Chinese, by path: the adoption's scope, counted at master 4cac234. */
    private static final int TOKEN_COMMENTS = 13;

    @TempDir
    Path tempDir;

    /** Every {@code @ConfigEntry} of the entity: path -> declared comment. */
    private static Map<String, String> declaredComments() {
        Map<String, String> comments = new LinkedHashMap<>();
        for (Field f : RemoteBagConfig.class.getDeclaredFields()) {
            ConfigEntry entry = f.getAnnotation(ConfigEntry.class);
            if (entry != null) {
                comments.put(entry.path(), entry.comment());
            }
        }
        return comments;
    }

    private static boolean isToken(String comment) {
        return comment.trim().matches("\\{[^{}]+}");
    }

    private static String keyOf(String token) {
        String trimmed = token.trim();
        return trimmed.substring(1, trimmed.length() - 1);
    }

    private YamlConfiguration fileOnDisk() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        yaml.load(new File(tempDir.toFile(), ConfigFileFixture.CONFIG_FILE));
        return yaml;
    }

    @Test
    @DisplayName("control: the entity declares thirteen token comments, and none of its comments is Chinese")
    void thirteenTokensAndNoChineseComment() {
        Map<String, String> comments = declaredComments();
        int tokens = 0;
        for (Map.Entry<String, String> e : comments.entrySet()) {
            if (isToken(e.getValue())) {
                tokens++;
            }
            assertThat(containsHan(e.getValue())).as("the comment of " + e.getKey()).isFalse();
        }
        assertThat(tokens).isEqualTo(TOKEN_COMMENTS);
    }

    private static boolean containsHan(String text) {
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
                || (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0xFF00 && cp <= 0xFFEF));
    }

    private void assertCommentsIn(String language) throws Exception {
        YamlConfiguration yaml = fileOnDisk();
        int checked = 0;
        for (Map.Entry<String, String> e : declaredComments().entrySet()) {
            if (!isToken(e.getValue())) {
                continue;
            }
            String expected = CatalogueText.text(language, keyOf(e.getValue()));
            assertThat(yaml.getComments(e.getKey())).as("comment of " + e.getKey() + " under " + language)
                    .containsExactly(expected);
            checked++;
        }
        assertThat(checked).isEqualTo(TOKEN_COMMENTS);
    }

    @Test
    @DisplayName("a fresh install under language: en writes every one of the thirteen comments in English")
    void freshInstallWritesEnglishComments() throws Exception {
        ConfigFileFixture.load(ConfigFileFixture.plugin(tempDir, "en"));

        assertCommentsIn("en");
        for (String line : Files.readAllLines(new File(tempDir.toFile(), ConfigFileFixture.CONFIG_FILE).toPath(),
                StandardCharsets.UTF_8)) {
            assertThat(containsHan(line)).as("the file under en holds no Chinese: " + line).isFalse();
        }
    }

    @Test
    @DisplayName("a fresh install under language: zh writes every one of the thirteen comments in Chinese")
    void freshInstallWritesChineseComments() throws Exception {
        ConfigFileFixture.load(ConfigFileFixture.plugin(tempDir, "zh"));

        assertCommentsIn("zh");
    }

    /** The comment every earlier version wrote above {@code lock.timeout_seconds}: English, then Chinese (master 4cac234). */
    private static final String OLD_LOCK_TIMEOUT_COMMENT = "Bag lock recovery timeout in seconds. Reclaims a lock whose holder's session "
            + "ended without releasing it; a holder who is online with the page open keeps "
            + "the lock however long they idle. "
            + "\u80cc\u5305\u9501\u7684\u56de\u6536\u8d85\u65f6\u65f6\u95f4\uff08\u79d2\uff09\u3002"
            + "\u4ec5\u7528\u4e8e\u56de\u6536\u6301\u6709\u8005\u4f1a\u8bdd\u5f02\u5e38\u7ed3\u675f\u800c\u672a\u91ca\u653e\u7684\u9501\uff1b"
            + "\u6301\u6709\u8005\u5728\u7ebf\u4e14\u9875\u9762\u4ecd\u6253\u5f00\u65f6\uff0c"
            + "\u65e0\u8bba\u7a7a\u95f2\u591a\u4e45\u90fd\u4f1a\u4fdd\u7559\u8be5\u9501";

    @Test
    @DisplayName("an upgraded file holding the bilingual lock.timeout_seconds comment earlier versions wrote gets the server language's comment (#52)")
    void upgradedLockTimeoutCommentFollowsTheLanguage() throws Exception {
        for (String language : new String[] {"en", "zh"}) {
            YamlConfiguration old = new YamlConfiguration();
            old.options().parseComments(true);
            old.set("lock.timeout_seconds", 120);
            old.setComments("lock.timeout_seconds", Collections.singletonList(OLD_LOCK_TIMEOUT_COMMENT));
            File file = new File(tempDir.toFile(), ConfigFileFixture.CONFIG_FILE);
            Files.createDirectories(file.getParentFile().toPath());
            old.save(file);
            assertThat(fileOnDisk().getComments("lock.timeout_seconds")).as("the old comment, as written")
                    .containsExactly(OLD_LOCK_TIMEOUT_COMMENT);

            RemoteBagConfig config = ConfigFileFixture.load(ConfigFileFixture.plugin(tempDir, language));

            assertThat(fileOnDisk().getComments("lock.timeout_seconds")).as(language)
                    .containsExactly(CatalogueText.text(language, keyOf(declaredComments().get("lock.timeout_seconds"))));
            assertThat(config.getLockTimeout()).as("the operator's value").isEqualTo(120);
            Files.delete(file.toPath());
        }
    }

    @Test
    @DisplayName("an upgraded file written with the old Chinese comments gets English ones at the next start, keeps its values, and then stays byte-identical")
    void upgradedFileSwitchesToTheServerLanguageAndKeepsValues() throws Exception {
        // The file the previous release wrote: the old Chinese comments, and an operator's edited values.
        YamlConfiguration old = new YamlConfiguration();
        old.options().parseComments(true);
        old.set("economy.base_price", 777);
        old.setComments("economy.base_price", Collections.singletonList("购买背包的基础价格"));
        old.set("sound.volume", 0.25);
        old.setComments("sound.volume", Collections.singletonList("音量 (0.0-1.0)"));
        File file = new File(tempDir.toFile(), ConfigFileFixture.CONFIG_FILE);
        Files.createDirectories(file.getParentFile().toPath());
        old.save(file);
        UltiRemoteBag plugin = ConfigFileFixture.plugin(tempDir, "en");

        RemoteBagConfig first = ConfigFileFixture.load(plugin);

        assertThat(first.getBasePrice()).as("an operator's value is untouched").isEqualTo(777);
        assertThat(first.getSoundVolume()).isEqualTo(0.25);
        assertThat(fileOnDisk().getComments("economy.base_price"))
                .containsExactly(CatalogueText.text("en", keyOf(declaredComments().get("economy.base_price"))));
        assertCommentsIn("en");
        byte[] afterFirstStart = Files.readAllBytes(file.toPath());

        ConfigFileFixture.load(plugin);

        assertThat(Files.readAllBytes(file.toPath())).as("the second start rewrites nothing").isEqualTo(afterFirstStart);
    }
}
