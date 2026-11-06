package com.ultikits.plugins.remotebag.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.ultikits.plugins.remotebag.util.FrameworkCompatibility.Decision.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Framework compatibility version matrix")
class FrameworkCompatibilityTest {
    @ParameterizedTest
    @CsvSource({
            "1.19.2-R0.1-SNAPSHOT,6.3.0,REFUSE",
            "1.20.6-R0.1-SNAPSHOT,6.3.1-SNAPSHOT,REFUSE",
            "1.20.6,6.4.0,ALLOW", "1.20.6,6.4.0-SNAPSHOT,ALLOW",
            "1.21,6.3.0,ALLOW", "1.21.11-R0.1-SNAPSHOT,6.3.0,ALLOW",
            "1.20.6,6.10.0,ALLOW", "1.20.6,7.0.0,ALLOW",
            "1.20.6,6.4.1,ALLOW", "1.20.6,6.2.99,REFUSE",
            "2.0.0,6.3.0,ALLOW", "1.9.4,6.3.0,REFUSE",
            "garbage,6.3.0,UNKNOWN", "1.20.6,garbage,UNKNOWN",
            "1.21.11,garbage,UNKNOWN", "1.20.6,6.3,UNKNOWN",
            "1.20.6,6.4.0-rc1,UNKNOWN", "1.20.6,6.4.0junk,UNKNOWN",
            "1.20.6.1,6.3.0,UNKNOWN", "1.20.6,999999999999.0.0,UNKNOWN"
    })
    @DisplayName("Compares numeric versions, accepts snapshots and never guesses malformed versions")
    void matrix(String server, String framework, FrameworkCompatibility.Decision expected) {
        assertThat(FrameworkCompatibility.evaluate(server, framework)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"garbage", "999999999999.20.6"})
    @DisplayName("Unknown input on either side continues with an explicit unknown decision")
    void unknown(String version) {
        assertThat(FrameworkCompatibility.evaluate(version, "6.3.0")).isEqualTo(UNKNOWN);
        assertThat(FrameworkCompatibility.evaluate("1.20.6", version)).isEqualTo(UNKNOWN);
    }
}
