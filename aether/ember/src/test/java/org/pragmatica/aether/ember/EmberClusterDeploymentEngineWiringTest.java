// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// The deployment engine an `EmberCluster` hands its nodes reaches the constructed node (F1 mutation survivors aember-EmberCluster-00: the
/// cluster-wide `withDeploymentEngine(engine)` assignment deleted, and aember-EmberCluster-01: the per-node override map write deleted).
/// Only the `forge-tests` mixed-cluster arms exercised these two seams before; a real `start()` is needed so `createNode` runs.
@PortBudget
class EmberClusterDeploymentEngineWiringTest {
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST, EmberTestPorts.POOL_LAST, EmberTestPorts.POOL_STEP, 3, 40, 80);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(90).seconds();

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await();
        }
    }

    @Test
    @Timeout(180)
    void clusterEngine_reachesEveryNode() {
        cluster = EmberTestPorts.startedCluster(PORTS, base -> engineCluster(base, "eng-all", DeploymentEngine.FSM2, null), START_BOUND);

        assertThat(cluster.allNodes().stream().map(AetherNode::deploymentWiring).map(wiring -> wiring.engine()).distinct().toList())
                  .containsExactly(DeploymentEngine.FSM2);
    }

    @Test
    @Timeout(180)
    void perNodeEngine_overridesTheClusterEngine_forThatNodeOnly() {
        cluster = EmberTestPorts.startedCluster(PORTS, base -> engineCluster(base, "eng-one", DeploymentEngine.LEGACY, "eng-one-2"), START_BOUND);

        var engines = cluster.allNodes().stream().filter(node -> node.self().id().equals("eng-one-2")).map(node -> node.deploymentWiring().engine()).toList();

        assertThat(engines).containsExactly(DeploymentEngine.FSM2);
    }

    private static EmberCluster engineCluster(int base, String prefix, DeploymentEngine clusterEngine, String overriddenNode) {
        var built = emberCluster(3, base, base + 40, base + 80, prefix);

        built.withDeploymentEngine(clusterEngine);
        if (overriddenNode != null) {
            built.withDeploymentEngine(overriddenNode, DeploymentEngine.FSM2);
        }

        return built;
    }
}
