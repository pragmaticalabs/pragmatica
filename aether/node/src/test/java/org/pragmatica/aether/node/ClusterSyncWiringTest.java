// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Wiring pins for the cluster sync assembly (`ClusterSyncAssembly`). Removing any of these lines left every
/// behavioural test in `aether/node` and `aether/ember` green (measured while splitting the assembly: 86 boot testcases
/// and ten Ember classes, 0 red); only the assembly trace noticed. Following `ClusterEventRedeliveryWiringTest`, each
/// pin reads the assembly source with comments stripped and whitespace removed and must match exactly once.
class ClusterSyncWiringTest {
    /// The collector is published to the holder the consensus node's leader-lease predicate reads.
    @Test
    void assembly_publishesTheMetricsCollectorToTheLeaseHolder() {
        assertThat(assemblyCode()).containsOnlyOnce("metricsCollectorRef.set(metricsCollector);");
    }

    /// The node's own readiness and drain state ride on every pong.
    @Test
    void assembly_reportsTheNodeStateOnPongs() {
        assertThat(assemblyCode()).containsOnlyOnce("metricsCollector.setNodeReportedStateSupplier(nodeReportedStateHolder::current);");
    }

    /// The pong fan receives the pongs and is swept for stale readiness.
    @Test
    void assembly_feedsPongsToTheSignalFan() {
        var code = assemblyCode();

        assertThat(code).containsOnlyOnce("metricsCollector.setPongSignalFan(pongSignalFan);");
        assertThat(code).containsOnlyOnce("pongSignalFan.sweepStale(readinessSweepMaxAgeNanos)");
    }

    /// A received pong reaches the scheduler, and the scheduler carries the leader's drain commands.
    @Test
    void assembly_feedsPongsToTheSchedulerAndCarriesDrainTargets() {
        var code = assemblyCode();

        assertThat(code).containsOnlyOnce("metricsCollector.addPongListener(pong->metricsScheduler.onPongReceived(pong.sender()));");
        assertThat(code).containsOnlyOnce("metricsScheduler.setDrainTargets(drainCommandRegistry::drainTargets);");
    }

    /// The cluster deployment manager reads draining nodes from the pong fan.
    @Test
    void assembly_bindsTheDrainingNodesToTheDeploymentManagerSupplier() {
        assertThat(assemblyCode()).containsOnlyOnce("cdmDrainingNodesRef.set(()->nodesReporting(pongSignalFan,nodeReportedStateHolder,config.self(),NodeReportedState.DRAINING));");
    }

    /// The generation counter advances once per tick on the leader.
    @Test
    void assembly_ticksTheGenerationCounterOnTheLeader() {
        assertThat(assemblyCode()).containsOnlyOnce("defer.accept(()->SharedScheduler.scheduleAtFixedRate(()->bumpGenerationIfLeader(isLeaderSupplier,");
    }

    /// The assembly source (`AetherNode.java` plus `node/assembly/*.java`) with line comments removed and all whitespace
    /// stripped. See [AssemblySource].
    private static String assemblyCode() {
        return AssemblySource.stripped();
    }
}
