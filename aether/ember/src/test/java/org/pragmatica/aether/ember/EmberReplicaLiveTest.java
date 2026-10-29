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
import org.pragmatica.aether.node.ReplicaCutover;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// W-OBS W3 on real nodes, with the worker built LIVE through the factory seam (test code only): 3 cores and one worker that follows the
/// replica feed instead of the metadata projection. The worker's own store then holds the cluster's incarnation (the real assertion of
/// TW-O12, which `EmberReplicaTripwireTest` pins at 0 for a projection worker) and the node reports ready from the replica readiness.
@PortBudget
class EmberReplicaLiveTest {
    private static final int CORES = 3;
    private static final int SLOTS = 2 * CORES;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                                EmberTestPorts.POOL_LAST,
                                                                                EmberTestPorts.POOL_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long READY_BOUND_MS = 120_000L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    /// Mutation: leave the worker DARK (`withReplicaCutover` not honoured by `createNode`), or drop the client's routes.
    @Test
    @Timeout(420)
    void aLiveWorker_holdsTheCoresIncarnation_andReportsReadyFromTheReplica() {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                base -> startedWithLiveWorkers(emberCluster(CORES, base, base + MGMT_OFFSET, base + APP_OFFSET, "rlw")),
                                                START_BOUND);
        awaitCondition("a leader is elected", () -> cluster.currentLeader().isPresent());
        awaitCondition("genesis commits incarnation 1", () -> ClusterIncarnation.current(leader().kvStore()) >= 1L);

        var added = cluster.addWorkerNode().await(START_BOUND);

        assertThat(added.isSuccess()).as("the worker process starts").isTrue();
        var worker = cluster.getNode(added.unwrap().id()).unwrap();

        awaitCondition("the worker's own store holds the cluster incarnation (TW-O12, real assertion)",
                       () -> ClusterIncarnation.current(worker.kvStore()) == ClusterIncarnation.current(leader().kvStore()));
        awaitCondition("the worker reports ready from the replica readiness", worker::isReady);
        assertThat(worker.metadataResourceMetrics().getOrDefault("clientInstalledIncarnation", 0L))
            .as("the projection client never ran: it installed nothing")
            .isZero();
    }

    private static EmberCluster startedWithLiveWorkers(EmberCluster created) {
        created.withReplicaCutover(ReplicaCutover.LIVE);

        return created;
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
