// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #298 — `[cluster] max_nodes` must survive the whole TOML -> ClusterConfig hop.
///
/// The cap is only as good as its weakest link: the guard, the config field, and the parse are three
/// separate places, and a value that stops at any one of them leaves an operator believing a bound is
/// in force when none is. These tests pin the parse hop specifically. (The guard behaviour itself is
/// pinned by `NodeLifecycleManagerCapTest`, and `Main.resolveAutoHeal` joins the two.)
class ClusterMaxNodesConfigTest {
    private static final String MINIMAL_CLUSTER = """
        [cluster]
        environment = "docker"
        nodes = 3
        """;

    @Test
    void maxNodes_isParsed_whenDeclared() {
        var toml = MINIMAL_CLUSTER + """
            max_nodes = 12
            """;

        ConfigLoader.loadFromString(toml)
                    .onFailure(cause -> fail(cause.message()))
                    .onSuccess(config -> assertThat(config.cluster().maxNodes())
                        .as("[cluster] max_nodes must reach ClusterConfig — a value that stops at the parse "
                            + "boundary leaves the cap silently unset")
                        .isEqualTo(12));
    }

    /// The default has to stay unbounded. A numeric default would refuse provisioning on any existing
    /// cluster already larger than it — a silent outage on upgrade, not a guardrail.
    @Test
    void maxNodes_isUnbounded_whenAbsent() {
        ConfigLoader.loadFromString(MINIMAL_CLUSTER)
                    .onFailure(cause -> fail(cause.message()))
                    .onSuccess(config -> assertThat(config.cluster().maxNodes())
                        .as("absent max_nodes must mean unbounded, preserving existing behaviour")
                        .isEqualTo(ClusterConfig.UNBOUNDED));
    }

    /// `max_nodes` must not disturb its neighbour in the same section — both use the 0-means-unset
    /// convention and are applied through the same override chain.
    @Test
    void maxNodes_andCoreMax_coexist_inTheSameSection() {
        var toml = MINIMAL_CLUSTER + """
            core_max = 5
            max_nodes = 20
            """;

        ConfigLoader.loadFromString(toml)
                    .onFailure(cause -> fail(cause.message()))
                    .onSuccess(config -> {
                        assertThat(config.cluster().coreMax()).isEqualTo(5);
                        assertThat(config.cluster().maxNodes()).isEqualTo(20);
                    });
    }
}
