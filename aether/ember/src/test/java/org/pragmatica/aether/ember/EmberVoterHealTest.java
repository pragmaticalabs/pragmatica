// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #2098 on a REAL in-JVM cluster: a voter that dies is replaced by the ready spare. The liveness half of "a voter is removed only on evidence":
/// the evidence (membership Dead) must reach the core voter reconciler through the assembled node, because a voter without evidence is
/// assumed healthy for ever. This pins the assembly (the `FailedVoterEvidence.forNode(...)` call site in `AetherNode`), which no unit test reaches:
/// emptying that argument leaves the dead voter in the electorate and this test red.
@PortBudget
class EmberVoterHealTest {
    private static final int SIZE = 5;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    /// How long the dead voter may stay in the electorate: failure detection plus one heal reconfiguration.
    private static final long HEAL_BOUND_MS = 240_000L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(900)
    void aVoterThatDies_isReplacedByTheReadySpare_withinTheBound() {
        cluster = EmberTestPorts.startedCluster(new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                          EmberTestPorts.POOL_LAST,
                                                                          EmberTestPorts.POOL_STEP,
                                                                          2 * SIZE + 6,
                                                                          MGMT_OFFSET,
                                                                          APP_HTTP_OFFSET),
                                                basePort -> emberCluster(SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "vh"),
                                                START_BOUND);
        var leader = awaitLeader();
        var victim = cluster.allNodes().stream().filter(node -> !node.self().equals(leader.self())).findFirst().orElseThrow().self();
        var originals = EmberNodeReplacementTest.installedVoters(leader);

        assertThat(originals).as("five voters before the kill").hasSize(SIZE).contains(victim);

        var started = System.currentTimeMillis();

        cluster.blackhole(victim.id()).await(START_BOUND);
        awaitCondition("the dead voter leaves the electorate and a node that was not a voter joins it",
                       () -> {
                           var voters = EmberNodeReplacementTest.installedVoters(survivor(victim));

                           return !voters.contains(victim) && voters.size() == SIZE && voters.stream().anyMatch(voter -> !originals.contains(voter));
                       });
        System.out.println("EmberVoterHealTest: healed in " + (System.currentTimeMillis() - started) + " ms; voters="
                           + EmberNodeReplacementTest.installedVoters(survivor(victim)));
    }

    private AetherNode survivor(NodeId dead) {
        return cluster.allNodes().stream().filter(node -> !node.self().equals(dead)).filter(AetherNode::isLeader).findFirst()
                      .orElseGet(() -> cluster.allNodes().stream().filter(node -> !node.self().equals(dead)).findFirst().orElseThrow());
    }

    private AetherNode awaitLeader() {
        var found = new AtomicReference<AetherNode>();

        awaitCondition("a leader is elected", () -> {
            found.set(cluster.allNodes().stream().filter(AetherNode::isLeader).findFirst().orElse(null));

            return found.get() != null;
        });

        return found.get();
    }

    private static void awaitCondition(String what, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + HEAL_BOUND_MS;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
