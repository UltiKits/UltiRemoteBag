package com.ultikits.plugins.remotebag;

import org.bukkit.Bukkit;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.lang.reflect.Field;

/**
 * Defensive MockBukkit singleton-cleanup helper. Its logic mirrors the equivalent helper in the
 * UltiTools-API framework's own test tree, but is deliberately duplicated rather than shared: that
 * class lives in a different git repository and is not published as a test artifact, so there is
 * nothing for this module to depend on.
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
     * Call before {@code MockBukkit.mock()} in {@code @BeforeEach}. Unmocks if a mock server is
     * still running, then force-clears MockBukkit's {@code mock} field and Bukkit's {@code server}
     * field by reflection, tolerating exceptions at every step.
     * <p>
     * The two reflective steps are redundant on the happy path: {@code MockBukkit.unmock()} ends in
     * {@code setServerInstanceToNull()}, which nulls both fields itself. They exist for the failure
     * path. In MockBukkit 4.101.0, {@code unmock()} guards only the scheduler shutdown with a
     * catch-all that repeats the cleanup; a throw from {@code PluginManagerMock.disablePlugins()}
     * or from the later {@code unload()}/{@code LifecycleEventRunnerMock.reset()} pair skips
     * {@code setServerInstanceToNull()} outright and leaves the static {@code mock} non-null. The
     * next class in the same reused Surefire fork would then see {@code MockBukkit.mock()} throw
     * {@code IllegalStateException: Already mocking}, because that guard tests exactly this field
     * for null.
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
            Field mockField = MockBukkit.class.getDeclaredField("mock");
            mockField.setAccessible(true);
            mockField.set(null, null);
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

    /**
     * This module's single shared test-time live-server bootstrap entry point. Calls
     * {@link #ensureCleanState()} then {@code MockBukkit.mock()}, returning the resulting
     * {@link ServerMock} so callers that need to wrap it (e.g. in a Mockito {@code spy()})
     * still can.
     * <p>
     * Every test class in this module that needs a live Bukkit server -- including
     * {@code UltiRemoteBagRegistrySentinelTest} -- must call this method rather than
     * {@code MockBukkit.mock()} directly. A sentinel that called {@code MockBukkit.mock()} on its
     * own line would build an unrelated live server of its own and stay green even if this
     * module's real tests silently lost their bootstrap; routing through this one method means
     * deleting or breaking it fails every caller, sentinel included.
     */
    public static ServerMock bootstrapLiveServer() {
        ensureCleanState();
        return MockBukkit.mock();
    }
}
