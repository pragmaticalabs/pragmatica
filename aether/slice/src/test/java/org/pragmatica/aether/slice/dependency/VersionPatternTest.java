// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

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

    /// #1435 — the three rows the ticket observed through `SharedDependencyLoader`, as caret patterns (the
    /// shape the Maven plugin writes for every `[infra]`/`[shared]` line), read through the verdict that
    /// decides an `[infra]` load. A loaded pre-release never satisfies a requester of the release it precedes.
    @Test
    void caret_qualifierOrdering_decidesCompatibilityPerTheTicketsTable() {
        assertVerdict("^1.0.0", "1.0.0-SNAPSHOT", false);
        assertVerdict("^1.0.0-SNAPSHOT", "1.0.0", true);
        assertVerdict("^1.0.0-rc10", "1.0.0-rc9", false);
    }

    /// #1435 — the ordering itself: a release above its pre-releases, numeric tokens numerically, a numeric
    /// token below a letter token, letters case-insensitively, a prefix first. Each row is checked in both
    /// directions so an ordering that answered 0 or a constant could not pass.
    @Test
    void compareQualifiers_ordersPreReleasesSemantically() {
        assertOrdered("SNAPSHOT", "");
        assertOrdered("rc9", "rc10");
        assertOrdered("rc2", "rc10");
        assertOrdered("alpha", "beta");
        assertOrdered("beta", "rc1");
        assertOrdered("rc1", "SNAPSHOT");
        assertOrdered("1", "alpha");
        assertOrdered("rc", "rc1");
        assertOrdered("rc01", "rc2");
        assertThat(VersionPattern.compareQualifiers("RC1", "rc1")).isZero();
        assertThat(VersionPattern.compareQualifiers("", "")).isZero();
    }

    private static void assertOrdered(String lower, String higher) {
        assertThat(VersionPattern.compareQualifiers(lower, higher)).as(lower + " < " + higher)
                                                                   .isNegative();
        assertThat(VersionPattern.compareQualifiers(higher, lower)).as(higher + " > " + lower)
                                                                   .isPositive();
    }

    private static void assertVerdict(String required, String loaded, boolean compatible) {
        var verdict = VersionPattern.parse(required)
                                    .flatMap(pattern -> Version.version(loaded)
                                                               .map(version -> CompatibilityResult.check(version, pattern)))
                                    .onFailure(cause -> Assertions.fail(cause.message()))
                                    .unwrap();

        assertThat(verdict).as(required + " against loaded " + loaded)
                           .isInstanceOf(compatible
                                         ? CompatibilityResult.Compatible.class
                                         : CompatibilityResult.Conflict.class);
    }
}
