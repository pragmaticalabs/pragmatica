// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.ClusterIncarnation;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// TW-O9 and TW-O12 of the worker-replica-feed design (W-OBS) on real nodes: 3 cores and one worker that has installed its metadata
/// projection. Each is an ENABLED test of today's behaviour with the real assertion of the target design `@Disabled` beside it.
///
/// - TW-O12: the cluster incarnation a worker's OWN store reports is 0 (a worker holds no committed state), although the
///   projection it installed carries incarnation 1. The design gives a worker the full replica, so after W3/W4 the two agree.
/// - TW-O9 (positive control, the negative half is `ReplicaReadinessWiringTripwireTest`): a worker whose projection IS fresh reports `READY`,
///   so the readiness computation can reach `READY` on a worker and the unit-level SYNCING is the missing projection.
@PortBudget
class EmberReplicaTripwireTest {
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
    private static final long GENESIS_INCARNATION = 1L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    private AetherNode startedWorker() {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                base -> emberCluster(CORES, base, base + MGMT_OFFSET, base + APP_OFFSET, "rtw"),
                                                START_BOUND);
        awaitCondition("a leader is elected", () -> cluster.currentLeader().isPresent());
        awaitCondition("genesis commits incarnation 1", () -> ClusterIncarnation.current(leader().kvStore()) == GENESIS_INCARNATION);

        var added = cluster.addWorkerNode().await(START_BOUND);

        assertThat(added.isSuccess()).as("the worker process starts").isTrue();
        var worker = cluster.getNode(added.unwrap().id()).unwrap();

        awaitCondition("the worker installs a metadata projection of incarnation 1",
                       () -> worker.metadataResourceMetrics().getOrDefault("clientInstalledIncarnation", 0L) == GENESIS_INCARNATION);

        return worker;
    }

    @Test
    @Timeout(420)
    void workerStore_reportsIncarnationZero_whileTheCoresReportOne_today() {
        var worker = startedWorker();

        assertThat(ClusterIncarnation.current(leader().kvStore())).as("control: the cores hold incarnation 1").isEqualTo(GENESIS_INCARNATION);
        assertThat(ClusterIncarnation.current(worker.kvStore())).as("TW-O12: the worker's own store reports 0 although its installed projection is incarnation 1").isZero();
    }

    @Disabled("W3/W4 (replica): enable when a worker holds the full KV replica; its store then reports the cores' incarnation")
    @Test
    @Timeout(420)
    void workerStore_reportsTheCoresIncarnation() {
        var worker = startedWorker();

        awaitCondition("the worker's store catches up to the cluster incarnation",
                       () -> ClusterIncarnation.current(worker.kvStore()) == ClusterIncarnation.current(leader().kvStore()));
    }

    @Test
    @Timeout(420)
    void workerWithAFreshProjection_reportsReady_today() {
        var worker = startedWorker();

        awaitCondition("TW-O9 control: a worker with a fresh projection reports READY", () -> reportedState(worker) == NodeReportedState.READY);
    }

    private static NodeReportedState reportedState(AetherNode node) {
        try {
            var collector = node.metricsCollector();
            var field = collector.getClass().getDeclaredField("nodeReportedStateSupplier");

            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var reference = (AtomicReference<Option<Supplier<NodeReportedState>>>) field.get(collector);

            return reference.get().unwrap().get();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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
