// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.ClusterIncarnation;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;


/// #1529: on real nodes, every epoch a node mints or observes carries the committed cluster incarnation,
/// not a literal 0. Unit tests construct these epochs from values they hand in, so the `AetherNode` wiring
/// that reads `ClusterIncarnation.current` was unpinned: forcing it to 0 left every module green (v1635's
/// M10). After genesis the committed incarnation is 1, and this pins it at three places, each through a
/// different supplier:
///   - the leader's current generation epoch: `leaderEpochSupplier`;
///   - every follower core's current generation epoch: the leader's authority pings, observed;
///   - a worker's installed metadata projection: the core metadata server's incarnation supplier.
/// `generationEpoch` itself (M10) is pinned at its seam by `ClusterIncarnationTest.GenerationEpochSeam`, not
/// here: its only consumer reachable without a workload is the DHT `core` ownership record, and that record
/// cannot pin it. Bootstrap commits it just BEFORE the registrar commits the genesis incarnation (observed
/// 52 ms apart), so it carries incarnation 0 whatever the wiring does, and a takeover rewrite runs only on a
/// later leader gain (killing the owner did not produce one within 120 s).
class EmberIncarnationWiringTest {
    private static final int CORES = 3;
    private static final int SLOTS = 2 * CORES;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_OFFSET = 80;
    /// The shared Ember pool below the ephemeral floor (EmberTestPorts.POOL_FIRST).
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long READY_BOUND_MS = 120_000L;
    private static final long GENESIS_INCARNATION = 1L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(420)
    void generationEpochs_andWorkerProjection_carryTheCommittedIncarnation_afterGenesis() {
        // Probed through the shared EmberTestPorts: it also probes each node's SWIM UDP port, which this class's own
        // loop did not, and a start that loses a port between the probe and the bind retries on a fresh block.
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                base -> emberCluster(CORES,
                                                                     base,
                                                                     base + MGMT_OFFSET,
                                                                     base + APP_OFFSET,
                                                                     "inc"),
                                                START_BOUND);
        awaitCondition("a leader is elected", () -> cluster.currentLeader().isPresent());
        awaitCondition("genesis commits incarnation 1",
                       () -> ClusterIncarnation.current(leader().kvStore()) == GENESIS_INCARNATION);

        awaitCondition("the leader mints its generation epoch at incarnation 1",
                       () -> leader().currentGenerationEpoch().incarnation() == GENESIS_INCARNATION);
        awaitCondition("every follower core observes incarnation 1 from the leader's pings",
                       () -> cluster.allNodes().stream().allMatch(node -> node.currentGenerationEpoch().incarnation() == GENESIS_INCARNATION));

        var added = cluster.addWorkerNode().await(START_BOUND);
        assertThat(added.isSuccess()).as("the worker process starts").isTrue();
        var worker = cluster.getNode(added.unwrap().id()).unwrap();

        awaitCondition("the worker installs a metadata projection of incarnation 1",
                       () -> worker.metadataResourceMetrics().getOrDefault("clientInstalledIncarnation", 0L) == GENESIS_INCARNATION);
    }

    private AetherNode leader() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .unwrap();
    }

    private static void awaitCondition(String what, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + READY_BOUND_MS;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            sleep(250);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
