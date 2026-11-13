// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #3158: a node stops itself on its OWN `MembershipDecision.NodeShuttingDown`, under both deployment engines, through an assembled
/// three-node cluster. The only self-stop used to be `NodeDeploymentManager`'s, reached through a LEGACY route that FSM2 counts as a
/// drop, so under FSM2 the decision was ignored. The decision is delivered through the node's router (`AetherNode.route`), the
/// way the projector's output would be: `MembershipDecision` documents the variant as no longer emitted, so there is no production
/// producer to drive it.
///
/// The observable is the first line of `AetherNode.stop()` (`Stopping Aether node <id>`), attributed to the node's id. The same
/// decision, naming one node, is delivered to all three: that node stops, and the two that were handed a decision about someone
/// else do not.
@PortBudget
class EmberSelfStopOnShuttingDownTest {
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                               EmberTestPorts.POOL_LAST,
                                                                               EmberTestPorts.POOL_STEP,
                                                                               6,
                                                                               40,
                                                                               80);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(90).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final String STOP_LOGGER = "org.pragmatica.aether.node.AetherNode$aetherNode";
    private static final String STOP_LINE = "Stopping Aether node ";
    /// Time given to a stop that must NOT happen, after the one that must has been seen.
    private static final long QUIET_MS = 3_000L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(240)
    void legacy_ownShuttingDown_stopsThatNodeOnly() {
        assertOwnDecisionStopsOnlyTheTarget(DeploymentEngine.LEGACY, "ssl");
    }

    @Test
    @Timeout(240)
    void fsm2_ownShuttingDown_stopsThatNodeOnly() {
        assertOwnDecisionStopsOnlyTheTarget(DeploymentEngine.FSM2, "ssf");
    }

    private void assertOwnDecisionStopsOnlyTheTarget(DeploymentEngine engine, String prefix) {
        cluster = EmberTestPorts.startedCluster(PORTS, base -> engineCluster(base, prefix, engine), START_BOUND);

        var nodes = cluster.allNodes();
        var victim = nodes.getFirst();
        var view = nodes.stream().map(AetherNode::self).toList();

        assertThat(victim.deploymentWiring().engine()).as("arming: the cluster runs the engine under test").isEqualTo(engine);

        try (var capture = LogCapture.capture(STOP_LOGGER, Level.INFO, true)) {
            // The decision is the same on every node, as a consensus-committed one would be: it names the victim, and reaches all three.
            nodes.forEach(node -> node.route(MembershipDecision.nodeShuttingDown(victim.self(), view)));
            awaitStopLine(capture, victim.self().id());
            sleepQuietly(QUIET_MS);

            assertThat(stoppedIds(capture, nodes)).as("only the node the decision names stops, under %s", engine)
                                                  .containsExactly(victim.self().id());
        }
    }

    private static void awaitStopLine(LogCapture capture, String nodeId) {
        var deadline = System.currentTimeMillis() + 30_000L;

        while (System.currentTimeMillis() < deadline) {
            if (capture.messages().stream().anyMatch(message -> message.contains(STOP_LINE) && message.contains(nodeId))) {
                return;
            }
            sleepQuietly(100L);
        }
        throw new AssertionError("node " + nodeId + " never logged its stop; observed " + capture.messages());
    }

    private static List<String> stoppedIds(LogCapture capture, List<AetherNode> nodes) {
        return nodes.stream()
                    .map(node -> node.self().id())
                    .filter(id -> capture.messages().stream().anyMatch(message -> message.contains(STOP_LINE) && message.contains(id)))
                    .toList();
    }

    private static EmberCluster engineCluster(int base, String prefix, DeploymentEngine engine) {
        var built = emberCluster(3, base, base + 40, base + 80, prefix);

        built.withDeploymentEngine(engine);
        return built;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
