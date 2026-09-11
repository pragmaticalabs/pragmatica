// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #718 shape 4 — the cluster start budget was a hard-coded 60-second literal at the single `await`
/// site, so a host where formation is merely slow had no way to buy more time and Forge exited.
class EmberConfigStartTimeoutTest {
    private static String toml(String clusterBody) {
        return "[cluster]\n" + clusterBody + "\n";
    }

    private static EmberConfig parse(String content) {
        return EmberConfig.loadFromString(content)
                          .fold(cause -> fail("must parse: " + cause.message()), config -> config);
    }

    private static String errorOf(String content) {
        return EmberConfig.loadFromString(content)
                          .fold(cause -> cause.message(), _ -> fail("expected a failure, got success"));
    }

    @Nested
    class Defaults {
        /// 60 s must stay the default, or every existing forge.toml silently changes its budget.
        @Test
        void startTimeoutSeconds_defaultsToSixty_whenKeyAbsent() {
            assertThat(parse(toml("nodes = 5")).startTimeoutSeconds()).isEqualTo(60);
            assertThat(EmberConfig.DEFAULT.startTimeoutSeconds()).isEqualTo(60);
        }
    }

    @Nested
    class Parsing {
        @Test
        void startTimeoutSeconds_isReadFromCluster_whenPresent() {
            assertThat(parse(toml("nodes = 3\nstart_timeout_seconds = 180")).startTimeoutSeconds()).isEqualTo(180);
        }

        /// The budget is independent of the QUIC range: #1008 and #718 shape 4 are separate knobs
        /// that happen to live in the same section.
        @Test
        void startTimeoutSeconds_isIndependentOfBasePort() {
            var config = parse(toml("""
                                    nodes = 3
                                    base_port = 7100
                                    start_timeout_seconds = 120"""));

            assertThat(config.startTimeoutSeconds()).isEqualTo(120);
            assertThat(config.basePort()).isEqualTo(7100);
        }
    }

    @Nested
    class Validation {
        /// A non-positive budget makes the await expire before the cluster could possibly form,
        /// turning every start into the timeout this value exists to govern.
        @Test
        void startTimeoutSeconds_isRejected_whenNotPositive() {
            assertThat(errorOf(toml("nodes = 3\nstart_timeout_seconds = 0"))).contains("start_timeout_seconds");
            assertThat(errorOf(toml("nodes = 3\nstart_timeout_seconds = -5"))).contains("start_timeout_seconds");
        }

        @Test
        void startTimeoutSeconds_isAccepted_atOne() {
            assertThat(parse(toml("nodes = 3\nstart_timeout_seconds = 1")).startTimeoutSeconds()).isEqualTo(1);
        }
    }
}
