package com.ultikits.plugins.remotebag.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Version-only decision for the server API compatibility bridge. */
public final class FrameworkCompatibility {
    private static final Pattern SERVER = Pattern.compile(
            "^(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-R\\d+\\.\\d+-SNAPSHOT)?$");
    private static final Pattern FRAMEWORK = Pattern.compile(
            "^(\\d+)\\.(\\d+)\\.(\\d+)(?:-SNAPSHOT)?$");

    private FrameworkCompatibility() {}

    public enum Decision { ALLOW, REFUSE, UNKNOWN }

    /** UNKNOWN deliberately allows startup, with a warning emitted by the caller. */
    public static Decision evaluate(String serverVersion, String frameworkVersion) {
        int[] server = parse(serverVersion, SERVER);
        int[] framework = parse(frameworkVersion, FRAMEWORK);
        if (server == null || framework == null) {
            return Decision.UNKNOWN;
        }
        return before(server, 1, 21, 0) && before(framework, 6, 4, 0)
                ? Decision.REFUSE : Decision.ALLOW;
    }

    private static int[] parse(String value, Pattern pattern) {
        if (value == null) {
            return null;
        }
        Matcher match = pattern.matcher(value);
        if (!match.matches()) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                    match.group(3) == null ? 0 : Integer.parseInt(match.group(3))};
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static boolean before(int[] actual, int major, int minor, int patch) {
        int[] required = {major, minor, patch};
        for (int i = 0; i < required.length; i++) {
            if (actual[i] != required[i]) {
                return actual[i] < required[i];
            }
        }
        return false;
    }
}
