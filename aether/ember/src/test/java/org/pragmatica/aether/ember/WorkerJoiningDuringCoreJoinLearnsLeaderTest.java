// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #3414. A worker that joins while a core is still joining never learns the leader: its `leader()` view stays empty, and the
/// worker then treats every leader ping as not authoritative (the ping authority check compares the sender against that view),
/// so a commanded drain, and every other leader command on the same path, is silently ignored.
///
/// Sequence: five seed cores; add one core (surplus over the configured five, so it is retired later); add one worker as soon
/// as the core's start resolves; wait until the worker is ready; then, within a bounded wait, the worker's leader view must
/// equal the cluster's leader. The control adds the worker with no core joining.
@PortBudget
class WorkerJoiningDuringCoreJoinLearnsLeaderTest {
    private static final int SEEDS = 5;
    private static final int SLOTS = 2 * SEEDS + 4;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_OFFSET = 80;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long READY_BOUND_MS = 120_000L;
    private static final long LEADER_VIEW_BOUND_MS = 30_000L;
    private static final long TRIPWIRE_SETTLE_MS = 10_000L;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                               EmberTestPorts.POOL_LAST,
                                                                               EmberTestPorts.POOL_STEP,
                                                                               SLOTS,
                                                                               MGMT_OFFSET,
                                                                               APP_OFFSET);

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    /// ENABLED tripwire pinning the CURRENT (defective) behaviour: the worker's leader view stays empty after it is ready, because
    /// its leader state machine was Dormant when the committed leader arrived (dropped) and entering Passive never re-reads it.
    @Test
    @Timeout(600)
    void workerLeaderView_workerAddedWhileCoreJoins_staysEmptyUntil3414IsFixed() {
        startSettledCluster();
        cluster.addNode().await(START_BOUND).unwrap();

        var worker = readyWorker();

        sleep(TRIPWIRE_SETTLE_MS);
        assertThat(worker.leader().isPresent()).as("#3414 appears fixed: the worker learned the leader. Enable "
                                       + "workerLeaderView_workerAddedWhileCoreJoins_equalsClusterLeader and delete this tripwire; " + views(worker))
                                          .isFalse();
    }

    @Disabled("#3414: enable when a worker joining during a core join learns the leader; replaces the tripwire above")
    @Test
    @Timeout(600)
    void workerLeaderView_workerAddedWhileCoreJoins_equalsClusterLeader() {
        startSettledCluster();
        cluster.addNode().await(START_BOUND).unwrap();

        var worker = readyWorker();

        awaitCondition("the worker's leader view equals the cluster's leader", LEADER_VIEW_BOUND_MS, () -> knowsLeader(worker), () -> views(worker));
    }

    @Test
    @Timeout(600)
    void workerLeaderView_noCoreJoining_equalsClusterLeader() {
        startSettledCluster();

        var worker = readyWorker();

        awaitCondition("the worker's leader view equals the cluster's leader", LEADER_VIEW_BOUND_MS, () -> knowsLeader(worker), () -> views(worker));
    }

    private void startSettledCluster() {
        cluster = EmberTestPorts.startedCluster(PORTS, WorkerJoiningDuringCoreJoinLearnsLeaderTest::seedCluster, START_BOUND);
        awaitCondition("a leader is elected", READY_BOUND_MS, () -> cluster.currentLeader().isPresent());
        awaitCondition("all seeds ready", READY_BOUND_MS, () -> cluster.allNodes().stream().allMatch(AetherNode::isReady));
    }

    private static EmberCluster seedCluster(int base) {
        var built = emberCluster(SEEDS, base, base + MGMT_OFFSET, base + APP_OFFSET, "wjl");

        built.withAdditionalNodeSlots(2).unwrap();

        return built;
    }

    private AetherNode readyWorker() {
        var worker = cluster.getNode(cluster.addWorkerNode().await(START_BOUND).unwrap().id()).unwrap();

        awaitCondition("the worker becomes ready", READY_BOUND_MS, worker::isReady);

        return worker;
    }

    private boolean knowsLeader(AetherNode worker) {
        var view = worker.leader().map(NodeId::id);
        var leader = cluster.currentLeader();

        return view.isPresent() && view.equals(leader);
    }

    private String views(AetherNode worker) {
        return "worker " + worker.self().id() + " sees " + worker.leader() + ", cluster leader " + cluster.currentLeader()
               + ", nodes " + cluster.nodeCount();
    }

    private void awaitCondition(String what, long boundMs, BooleanSupplier condition) {
        awaitCondition(what, boundMs, condition, () -> "");
    }

    private void awaitCondition(String what, long boundMs, BooleanSupplier condition, Supplier<String> detail) {
        var started = System.currentTimeMillis();
        var deadline = started + boundMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out after " + boundMs + " ms waiting: " + what + "; " + detail.get());
            }

            sleep(250);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
