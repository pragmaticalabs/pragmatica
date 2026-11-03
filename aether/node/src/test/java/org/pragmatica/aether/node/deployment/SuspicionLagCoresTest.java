// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.BackoffConfig;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// The core set the suspicion lag covers (cluster assessment delta 3): SWIM's probe lead grows with the number of cores, so the lag is
/// derived for the configured `coreMax`, and for the configured cluster size when `coreMax` is unset (0, unlimited). Red when the lag is
/// derived for an unset `coreMax` as if the cluster had one core (no probe lead), or ignores a configured `coreMax`.
class SuspicionLagCoresTest {
    private static TopologyConfig topology(int clusterSize, int coreMax) {
        return new TopologyConfig(NodeId.nodeId("core-1").unwrap(),
                                  clusterSize,
                                  TimeSpan.timeSpan(1).seconds(),
                                  TimeSpan.timeSpan(10).seconds(),
                                  TopologyConfig.DEFAULT_HELLO_TIMEOUT,
                                  List.of(),
                                  Option.none(),
                                  BackoffConfig.DEFAULT,
                                  coreMax,
                                  clusterSize);
    }

    @Test
    void maxCores_isTheConfiguredCoreMax() {
        assertThat(Fsm2System.maxCores(topology(5, 7))).isEqualTo(7);
    }

    @Test
    void maxCores_isTheClusterSize_whenCoreMaxIsUnset() {
        assertThat(Fsm2System.maxCores(topology(5, 0))).isEqualTo(5);
    }
}
