// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// `[timeouts.deployment] apply_deadline` and `apply_schema_pause_max` (R-4.1.7, #970): defaults, parsing and validation.
class ApplyDeadlineConfigTest {
    private static final String MINIMAL_CLUSTER = """
        [cluster]
        environment = "docker"
        nodes = 3
        """;

    private static TimeoutsConfig.DeploymentTimeouts load(String section) {
        return ConfigLoader.loadFromString(MINIMAL_CLUSTER + "\n" + section)
                           .onFailure(cause -> fail(cause.message()))
                           .unwrap()
                           .timeouts()
                           .deployment();
    }

    /// Red when: a default moves off the spec's `[guess]` (15 min deadline, 2 h pause cap).
    @Test
    void absentKeys_takeTheDefaults() {
        var deployment = load("");

        assertThat(deployment.applyDeadline().millis()).isEqualTo(15 * 60_000L);
        assertThat(deployment.applySchemaPauseMax().millis()).isEqualTo(2 * 60 * 60_000L);
    }

    /// Red when: a key present in `[timeouts.deployment]` with the other absent takes the other key's default (the per-key fallback arguments
    /// are swapped). The section is present, so the per-key default arguments are reached, which the empty document never does.
    @Test
    void sectionWithOneKey_takesTheOtherKeysOwnDefault() {
        var onlyPause = load("""
                             [timeouts.deployment]
                             apply_schema_pause_max = "30m"
                             """);
        var onlyDeadline = load("""
                                [timeouts.deployment]
                                apply_deadline = "5m"
                                """);

        assertThat(onlyPause.applyDeadline().millis()).as("deadline absent: its own 15 min default").isEqualTo(15 * 60_000L);
        assertThat(onlyPause.applySchemaPauseMax().millis()).isEqualTo(30 * 60_000L);
        assertThat(onlyDeadline.applyDeadline().millis()).isEqualTo(5 * 60_000L);
        assertThat(onlyDeadline.applySchemaPauseMax().millis()).as("pause cap absent: its own 2 h default").isEqualTo(2 * 60 * 60_000L);
    }

    /// Red when: a key is not read from its TOML name.
    @Test
    void bothKeys_areParsed() {
        var deployment = load("""
                              [timeouts.deployment]
                              apply_deadline = "5m"
                              apply_schema_pause_max = "30m"
                              """);

        assertThat(deployment.applyDeadline().millis()).isEqualTo(5 * 60_000L);
        assertThat(deployment.applySchemaPauseMax().millis()).isEqualTo(30 * 60_000L);
    }

    /// Red when: a zero deadline is accepted (the validator's refusal is removed or not called).
    @Test
    void zeroDeadline_isRefused() {
        var result = ConfigLoader.loadFromString(MINIMAL_CLUSTER + """

                                                 [timeouts.deployment]
                                                 apply_deadline = "0s"
                                                 """);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("timeouts.deployment.apply_deadline"));
    }

    /// Red when: a negative cap is accepted (the key is not parsed as a duration, or its parse is bypassed).
    @Test
    void negativeCap_isRefused() {
        var result = ConfigLoader.loadFromString(MINIMAL_CLUSTER + """

                                                 [timeouts.deployment]
                                                 apply_schema_pause_max = "-5s"
                                                 """);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("timeouts.deployment.apply_schema_pause_max"));
    }

    /// Red when: a zero cap (the pause disabled) is refused.
    @Test
    void zeroCap_isAccepted() {
        assertThat(load("""
                        [timeouts.deployment]
                        apply_schema_pause_max = "0s"
                        """).applySchemaPauseMax().millis()).isZero();
    }
}
