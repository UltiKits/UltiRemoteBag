package com.ultikits.plugins.remotebag;

import org.bukkit.Bukkit;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.reflect.Field;

/**
 * Defensive MockBukkit singleton-cleanup helper, copied from the logic of
 * {@code Framework/UltiTools-Reborn/src/test/java/com/ultikits/ultitools/utils/MockBukkitHelper.java}
 * per {@code 14-CONTEXT.md}'s "no shared artifact" decision — the logic is copied, not a dependency
 * on that class, which lives in a different git repository.
 * <p>
 * Named {@code MockBukkitSupport}, deliberately not {@code MockBukkitHelper}: that name is already
 * used by four other classes in this monorepo (the framework's own live helper, and legacy
 * {@code be.seeseemelk.mockbukkit}-importing copies in {@code UltiMail} and {@code UltiEssentials}),
 * and a fifth collision would make a cross-repo grep for one module's guard unable to distinguish it
 * from another's.
 * <p>
 * Public, not package-private: unlike {@code UltiTradeTestHelper} (which owns {@code Bukkit.server}
 * centrally and is the only caller of its own {@code MockBukkitSupport}), this module's affected test
 * classes span four subpackages ({@code commands}, {@code gui}, {@code service}, {@code util}) and
 * call this helper directly from each of their own {@code @BeforeEach}/{@code @AfterEach} methods.
 */
public final class MockBukkitSupport {

    private MockBukkitSupport() {
    }

    /**
     * Call before {@code MockBukkit.mock()} in {@code @BeforeEach}. Force-clears MockBukkit's and
     * Bukkit's static singleton fields, tolerating exceptions, so a prior test's failed teardown
     * cannot leave {@code MockBukkit.mock()} throwing {@code IllegalStateException: A mock server
     * is already running!} for the next class in the same reused Surefire fork.
     */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // test helper requires reflection for singleton cleanup
    public static void ensureCleanState() {
        try {
            if (MockBukkit.isMocked()) {
                MockBukkit.unmock();
            }
        } catch (Exception ignored) {
            // best-effort cleanup only
        }

        try {
            Field mockedField = MockBukkit.class.getDeclaredField("mocked");
            mockedField.setAccessible(true);
            mockedField.setBoolean(null, false);
        } catch (Exception ignored) {
            // best-effort cleanup only
        }

        if (Bukkit.getServer() != null) {
            try {
                Field serverField = Bukkit.class.getDeclaredField("server");
                serverField.setAccessible(true);
                serverField.set(null, null);
            } catch (Exception ignored) {
                // best-effort cleanup only
            }
        }
    }

    /**
     * Call in {@code @AfterEach}. Unmocks, tolerating exceptions, then force-clears again so the
     * next test class starts from a known-clean state regardless of how this test's teardown went.
     */
    public static void safeUnmock() {
        try {
            MockBukkit.unmock();
        } catch (Exception ignored) {
            // best-effort cleanup only
        }
        ensureCleanState();
    }
}
