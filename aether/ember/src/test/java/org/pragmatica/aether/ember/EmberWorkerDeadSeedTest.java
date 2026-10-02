// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1601: does a worker bootstrap when its seed list (PEERS) names a core that is dead? It did name one until
/// #1601 — `provisionWorkers` seeded new workers from the installed voter set, which carries no health.
///
/// A worker is added whose configured peers are the three live cores PLUS a core that never started: an
/// address with nothing listening. The worker must still join, become ready and be counted by the leader.
/// Armed by asserting the dead seed really is in the worker's own configured topology.
@PortBudget
class EmberWorkerDeadSeedTest {
    private static final int CORES = 3;
    private static final int SLOTS = 2 * CORES;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_OFFSET = 80;
    /// A port inside the block that no slot ever uses: the dead seed's address.
    private static final int DEAD_OFFSET = 30;
    /// The shared Ember pool below the ephemeral floor (EmberTestPorts.POOL_FIRST).
    private static final int FIRST_CANDIDATE_BASE = EmberTestPorts.POOL_FIRST;
    private static final int LAST_CANDIDATE_BASE = EmberTestPorts.POOL_LAST;
    private static final int CANDIDATE_STEP = EmberTestPorts.POOL_STEP;
    /// #1667: probed through the shared EmberTestPorts, which also probes each node's SWIM UDP port.
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(FIRST_CANDIDATE_BASE,
                                                                                LAST_CANDIDATE_BASE,
                                                                                CANDIDATE_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_OFFSET,
                                                                                java.util.List.of(DEAD_OFFSET));
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

    @Test
    @Timeout(420)
    @SuppressWarnings("unchecked")
    void workerSeededWithOneDeadCore_stillJoinsAndBecomesReady() throws ReflectiveOperationException {
        // The last base handed to the factory is the one that started: a retry builds a fresh cluster on a fresh block.
        var startedBase = new AtomicInteger();

        cluster = EmberTestPorts.startedCluster(PORTS, candidate -> {
            startedBase.set(candidate);
            return emberCluster(CORES, candidate, candidate + MGMT_OFFSET, candidate + APP_OFFSET, "wds");
        }, START_BOUND);
        var base = startedBase.get();
        awaitCondition("a leader is elected", () -> cluster.currentLeader().isPresent());
        var dead = new NodeId("wds-dead");
        var seeds = (Map<String, NodeInfo>) field("nodeInfos");

        seeds.put(dead.id(),
                  NodeInfo.nodeInfo(dead,
                                    NodeAddress.nodeAddress("localhost", base + DEAD_OFFSET).unwrap(),
                                    Map.of(NodeInfo.LABEL_ROLE, "core")));
        var added = cluster.addWorkerNode().await(START_BOUND);

        seeds.remove(dead.id());
        assertThat(added.isSuccess()).as("the worker process starts").isTrue();
        var worker = cluster.getNode(added.unwrap().id()).unwrap();

        assertThat(worker.initialTopology()).as("arming: the worker's seed list names the dead core").contains(dead);
        awaitCondition("the worker becomes ready despite the dead seed", worker::isReady);
        awaitCondition("the leader counts the worker as a member",
                       () -> "Member".equals(leader().membershipFsm().memberStates().get(worker.self())));
    }

    private AetherNode leader() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .unwrap();
    }

    private Object field(String name) throws ReflectiveOperationException {
        var field = EmberCluster.class.getDeclaredField(name);

        field.setAccessible(true);
        return field.get(cluster);
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
