package org.pragmatica.aether.slice.dependency;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Version;

import static org.assertj.core.api.Assertions.assertThat;

class VersionPatternTest {

    @Test
    void exact_version_matches_only_same_version() {
        VersionPattern.parse("1.2.3")
                      .onFailureRun(Assertions::fail)
                      .onSuccess(p -> {
                          testMatch(p, "1.2.3", true);
                          testMatch(p, "1.2.4", false);
                          testMatch(p, "1.3.0", false);
                          testMatch(p, "2.0.0", false);
                      });
    }

    @Test
    void range_inclusive_both_ends() {
        VersionPattern.parse("[1.0.0,2.0.0]")
                      .onFailureRun(Assertions::fail)
                      .onSuccess(p -> {
                          testMatch(p, "0.9.9", false);
                          testMatch(p, "1.0.0", true);
                          testMatch(p, "1.5.0", true);
                          testMatch(p, "2.0.0", true);
                          testMatch(p, "2.0.1", false);
                      });
    }

    @Test
    void range_exclusive_upper_bound() {
        VersionPattern.parse("[1.0.0,2.0.0)")
                      .onFailureRun(Assertions::fail)
                      .onSuccess(p -> {
                          testMatch(p, "1.0.0", true);
                          testMatch(p, "1.9.9", true);
                          testMatch(p, "2.0.0", false);
                      });
    }

    @Test
    void comparison_greater_than_or_equal() {
        VersionPattern.parse(">=1.5.0")
                      .onFailureRun(Assertions::fail)
                      .onSuccess(p -> {
                          testMatch(p, "1.4.9", false);
                          testMatch(p, "1.5.0", true);
                          testMatch(p, "1.5.1", true);
                          testMatch(p, "2.0.0", true);
                      });
    }

    @Test
    void comparison_less_than() {
        VersionPattern.parse("<2.0.0")
                      .onFailureRun(Assertions::fail)
                      .onSuccess(p -> {
                          testMatch(p, "1.9.9", true);
                          testMatch(p, "2.0.0", false);
                          testMatch(p, "2.0.1", false);
                      });
    }

    @Test
    void tilde_allows_patch_level_changes() {
        VersionPattern.parse("~1.2.3")
                      .onFailureRun(Assertions::fail)
                      .onSuccess(p -> {
                          testMatch(p, "1.2.2", false);
                          testMatch(p, "1.2.3", true);
                          testMatch(p, "1.2.4", true);
                          testMatch(p, "1.2.99", true);
                          testMatch(p, "1.3.0", false);
                      });
    }

    @Test
    void caret_allows_minor_level_changes() {
        VersionPattern.parse("^1.2.3")
                      .onFailureRun(Assertions::fail)
                      .onSuccess(p -> {
                          testMatch(p, "1.2.2", false);
                          testMatch(p, "1.2.3", true);
                          testMatch(p, "1.2.4", true);
                          testMatch(p, "1.3.0", true);
                          testMatch(p, "1.99.0", true);
                          testMatch(p, "2.0.0", false);
                      });
    }

    @Test
    void invalid_pattern_returns_failure() {
        VersionPattern.parse("")
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause.message()).contains("cannot be empty"));

        VersionPattern.parse("[1.0.0,]")
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause.message()).contains("Invalid"));
    }

    @Test
    void pattern_roundtrip_preserves_semantics() {
        testRoundtrip("1.2.3");
        testRoundtrip("[1.0.0,2.0.0)");
        testRoundtrip(">=1.5.0");
        testRoundtrip("~1.2.3");
        testRoundtrip("^1.2.3");
    }

    private void testRoundtrip(String original) {
        VersionPattern.parse(original)
                      .map(VersionPattern::asString)
                      .flatMap(VersionPattern::parse)
                      .onFailureRun(Assertions::fail)
                      .onSuccess(reparsed -> {
                          // Same pattern type and semantics
                          VersionPattern.parse(original)
                                        .onFailureRun(Assertions::fail)
                                        .onSuccess(originalPattern -> {
                                            testMatch(reparsed, "1.0.0", matches(originalPattern, "1.0.0"));
                                            testMatch(reparsed, "2.0.0", matches(originalPattern, "2.0.0"));
                                        });
                      });
    }

    private void testMatch(VersionPattern pattern, String versionStr, boolean expected) {
        Version.version(versionStr)
               .onFailureRun(Assertions::fail)
               .onSuccess(v -> assertThat(pattern.matches(v)).isEqualTo(expected));
    }

    private boolean matches(VersionPattern pattern, String versionStr) {
        return Version.version(versionStr)
                      .map(pattern::matches)
                      .onFailure(cause -> Assertions.fail("Failed to parse version: " + versionStr))
                      .unwrap();
    }
}
