// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #1008 — the QUIC/consensus base port is the fourth Forge port, and it was the only one
/// `EmberConfig` did not expose. Three configurable ports and one pinned constant is what let an
/// operator relocate a Forge instance, believe it was isolated, and still collide on 6000-6004.
class EmberConfigBasePortTest {
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
        /// The default must stay 6000, or every existing forge.toml silently moves its cluster.
        @Test
        void basePort_defaultsToSixThousand_whenKeyAbsent() {
            assertThat(parse(toml("nodes = 5")).basePort()).isEqualTo(6000);
            assertThat(EmberConfig.DEFAULT.basePort()).isEqualTo(6000);
        }

        /// The constant Forge used to pass positionally is still the single source of truth.
        @Test
        void basePort_defaultTracksTheClusterConstant() {
            assertThat(EmberConfig.DEFAULT_BASE_PORT).isEqualTo(EmberCluster.DEFAULT_BASE_PORT);
        }
    }

    @Nested
    class Parsing {
        @Test
        void basePort_isReadFromCluster_whenPresent() {
            assertThat(parse(toml("nodes = 3\nbase_port = 7100")).basePort()).isEqualTo(7100);
        }

        /// The reason the key exists: a second Forge must be able to move off 6000 while keeping
        /// the other three ports independently configurable.
        @Test
        void basePort_isIndependentOfTheOtherPorts() {
            var config = parse(toml("""
                                    nodes = 3
                                    base_port = 7100
                                    management_port = 5250
                                    dashboard_port = 8988
                                    app_http_port = 8170"""));

            assertThat(config.basePort()).isEqualTo(7100);
            assertThat(config.managementPort()).isEqualTo(5250);
            assertThat(config.dashboardPort()).isEqualTo(8988);
            assertThat(config.appHttpPort()).isEqualTo(8170);
        }
    }

    @Nested
    class Validation {
        @Test
        void basePort_isRejected_whenOutOfPortRange() {
            assertThat(errorOf(toml("nodes = 3\nbase_port = 70000"))).contains("base_port");
            assertThat(errorOf(toml("nodes = 3\nbase_port = 0"))).contains("base_port");
        }

        /// A base port that is individually valid can still run the cluster off the end of the port
        /// space, because node `i` binds `base_port + i`.
        @Test
        void basePort_isRejected_whenRangeForNodesExceedsPortSpace() {
            var message = errorOf(toml("nodes = 10\nbase_port = 65530"));

            assertThat(message).contains("base_port")
                               .contains("65535");
        }

        @Test
        void basePort_isAccepted_atTheTopOfTheRange() {
            assertThat(parse(toml("nodes = 5\nbase_port = 65531")).basePort()).isEqualTo(65531);
        }
    }
}
