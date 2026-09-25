package com.ultikits.plugins.remotebag.i18n;

import com.ultikits.plugins.remotebag.i18n.UltiRemoteBagLanguageCatalogueTest.Catalogue;
import com.ultikits.ultitools.entities.Language;

import org.mockito.stubbing.Answer;

import java.util.Map;

/**
 * Test support: answers the module's {@code i18n} from the catalogues this module really ships.
 * <p>
 * The catalogues are read exactly as the language guard reads them
 * ({@link UltiRemoteBagLanguageCatalogueTest#loadModuleCatalogues()}: the framework's own {@link Language},
 * from the module's code source), so a test that uses this sees the text an operator sees, in the
 * language it names, and a key missing from that catalogue renders as the key itself, as it does on
 * a server.
 */
public final class CatalogueText {

    private CatalogueText() {
    }

    /** The catalogue for {@code code} ({@code "en"} or {@code "zh"}) as a key-to-text map. */
    public static Map<String, String> entries(String code) {
        try {
            for (Catalogue c : UltiRemoteBagLanguageCatalogueTest.loadModuleCatalogues()) {
                if (c.code.equals(code)) {
                    return c.entries;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot read the module's catalogues", e);
        }
        throw new IllegalArgumentException("no catalogue for language " + code);
    }

    /** The text {@code key} has in {@code code}'s catalogue; fails loudly when the key is missing. */
    public static String text(String code, String key) {
        String text = entries(code).get(key);
        if (text == null) {
            throw new AssertionError("lang/" + code + " has no key " + key);
        }
        return text;
    }

    /** A Mockito answer for {@code i18n(key)} (or {@code i18n(code, key)}) backed by {@code code}'s catalogue. */
    public static Answer<String> answer(String code) {
        final Language language = new Language(entries(code));
        return inv -> language.getLocalizedText(inv.<String>getArgument(inv.getArguments().length - 1));
    }
}
