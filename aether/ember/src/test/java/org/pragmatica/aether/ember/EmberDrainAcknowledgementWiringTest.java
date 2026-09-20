// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.pragmatica.cluster.metrics.MetricObservation;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #1054 round 2 — the drain acknowledgement pinned THROUGH THE ASSEMBLED LEADER NODE (review B1 + S4).
///
/// A real three-node in-JVM cluster; the assertions run on its elected leader. The DEPARTING timeout is the
/// node's own `splitTimeout` (the membership default, 15s), the readiness view is the node's own pong fan with
/// its own periodic stale-sweep (`pingInterval × 3`), and the acknowledgement arrives the way production
/// delivers it: a `DRAINING` pong into `ManageableNode.metricsCollector().onClusterSyncPong`. Nothing between
/// that pong and the membership FSM is a test double, so removing the AetherNode wiring between them turns
/// this test red. A single self-formed node cannot stand in: it never elects a leader (the election guard
/// refuses an empty transport view), and the fan records only on the leader.
///
/// The failure pinned (B1): a drainee that acknowledged the DRAIN and then HALTED stops ponging, and the sweep
/// drops its `DRAINING` entry seconds later — before the DEPARTING timeout and before QUIC or SWIM death
/// evidence can land. The acknowledgement must outlive that sweep, so the halted drainee terminalizes rather
/// than being withdrawn to MEMBER and re-added to the DHT ring. The never-acknowledged drainee runs on the SAME
/// leader in the SAME window: a green acknowledged arm cannot come from a timeout that always terminalizes.
///
/// The two drainees are labelled `worker`. Unlabelled, they count as core members, and the leader's reconciler
/// answers the apparent surplus by draining REAL peers (observed on the first red run: two
/// `OVERPROVISION_PARTITION_HEAL` drains ~24s in). Workers are outside the core surplus count and outside the
/// core dial set, so the leader neither drains real peers nor dials the fakes (a failed dial would inject
/// liveness-loss death evidence). The final check also asserts every real peer is still `Member`, so any
/// reconciler interference turns this test red instead of hiding.
class EmberDrainAcknowledgementWiringTest {
    private static final int CLUSTER_SIZE = 3;
    /// `EmberCluster.start` builds a slot pool of `2 * clusterSize`.
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final int FIRST_CANDIDATE_BASE = 27700;
    private static final int LAST_CANDIDATE_BASE = 29500;
    private static final int CANDIDATE_STEP = 200;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    /// Ember nodes run the membership default unless SWIM timeouts are raised; a raised 60s window would make
    /// the final wait below fail loudly rather than pass for the wrong reason.
    private static final long DEPARTING_TIMEOUT_MS = MembershipConfig.DEFAULT_SPLIT_TIMEOUT.millis();
    private static final long POLL_MS = 200;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped"))
                .describedAs("cluster stop must complete within %s", STOP_BOUND)
                .isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(300)
    void leaderNode_acknowledgedThenHaltedDrainee_terminalizesAfterSweep_whileUnacknowledgedDraineeIsWithdrawn() {
        var basePort = freeBasePort();
        cluster = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "dack");
        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started"))
            .describedAs("a three-node cluster on a verified-free port block at %d must form", basePort)
            .isEqualTo("started");

        var leader = awaitLeader();
        var fsm = leader.membershipFsm();
        var acknowledged = NodeId.nodeId("drainee-acknowledged-" + UUID.randomUUID()).unwrap();
        var unacknowledged = NodeId.nodeId("drainee-unacknowledged-" + UUID.randomUUID()).unwrap();

        fsm.onMemberDescriptor(workerInfo(acknowledged));
        fsm.onMemberDescriptor(workerInfo(unacknowledged));
        fsm.onSwimHealthy(acknowledged, 1L);
        fsm.onSwimHealthy(unacknowledged, 1L);
        fsm.onDrainRequested(acknowledged);
        fsm.onDrainRequested(unacknowledged);
        var drainedAtMs = System.currentTimeMillis();
        assertThat(fsm.memberStates()).containsEntry(acknowledged, "Departing")
                                      .containsEntry(unacknowledged, "Departing");

        // The real synchronous pong callback may wait behind a consensus/KV application. Observe
        // readiness while ingress is running, before the independent stale sweep can expire it.
        var ingress = org.pragmatica.lang.Promise.lift(() -> {
            leader.metricsCollector().onClusterSyncPong(drainingPong(acknowledged));
            return org.pragmatica.lang.Unit.unit();
        });
        awaitCondition("arming: readiness recorded while the actual pong ingress executes",
                       drainedAtMs + 2_000,
                       () -> leader.metricsCollector().reportedStates().get(acknowledged) == NodeReportedState.DRAINING,
                       () -> leader.metricsCollector().reportedStates().toString());
        assertThat(leader.metricsCollector().reportedStates())
            .describedAs("arming: the leader's own readiness view recorded the acknowledgement")
            .containsEntry(acknowledged, NodeReportedState.DRAINING);

        // The acknowledged drainee halts: no further pongs. The leader's periodic sweep must remove the entry
        // while the member is still DEPARTING, or this test does not exercise B1 at all.
        awaitCondition("arming: the real stale-sweep dropped the acknowledgement before the DEPARTING timeout",
                       drainedAtMs + DEPARTING_TIMEOUT_MS - 2_000,
                       () -> !leader.metricsCollector().reportedStates().containsKey(acknowledged),
                       () -> leader.metricsCollector().reportedStates().toString());
        assertThat(fsm.memberStates()).describedAs("arming: the sweep landed before expiry")
                                      .containsEntry(acknowledged, "Departing");

        awaitCondition("the acknowledged drainee terminalizes and the unacknowledged one is withdrawn at expiry",
                       drainedAtMs + DEPARTING_TIMEOUT_MS + 10_000,
                       () -> "Dead".equals(fsm.memberStates().get(acknowledged))
                             && "Member".equals(fsm.memberStates().get(unacknowledged)),
                       () -> fsm.memberStates().toString());
        assertThat(ingress.await(STOP_BOUND).isSuccess()).as("actual pong callback completed").isTrue();
        assertThat(fsm.countedMembers()).doesNotContain(acknowledged)
                                        .contains(unacknowledged);
        assertThat(cluster.allNodes()).allSatisfy(peer -> assertThat(fsm.memberStates().get(peer.self()))
            .describedAs("control: real peer %s was not drained by the reconciler during the run", peer.self())
            .isEqualTo("Member"));
    }

    private static NodeInfo workerInfo(NodeId id) {
        return NodeInfo.nodeInfo(id, nodeAddress("localhost", 1).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "worker"), null);
    }

    private AetherNode awaitLeader() {
        var deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            var leader = cluster.currentLeader()
                                .flatMap(cluster::getNode)
                                .filter(AetherNode::isLeader);
            if (leader.isPresent()) {
                return leader.unwrap();
            }
            sleep();
        }
        throw new AssertionError("arming: no node reported itself leader within 60s — the pong fan records only on the leader");
    }

    private static void awaitCondition(String description,
                                       long deadlineMs,
                                       BooleanSupplier condition,
                                       Supplier<String> observed) {
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadlineMs) {
                throw new AssertionError(description + " — not reached by the deadline; observed " + observed.get());
            }
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", e);
        }
    }

    private static ClusterSyncPong drainingPong(NodeId sender) {
        return new ClusterSyncPong(sender, new MetricObservation(1L, System.nanoTime(), System.currentTimeMillis(), Map.of()), 0L, 0L, 0L, NodeReportedState.DRAINING.name(), List.of(), List.of(), List.of(), Option.none());
    }

    /// The first candidate base whose whole block (QUIC UDP + TCP cluster ports, management and app-HTTP
    /// ports) binds free right now — the same shared-box guard `EmberClusterObservedNodeStateTest` uses, on a
    /// disjoint candidate range so the two classes never probe the same block.
    private static int freeBasePort() {
        for (int base = FIRST_CANDIDATE_BASE; base <= LAST_CANDIDATE_BASE; base += CANDIDATE_STEP) {
            if (blockIsFree(base)) {
                return base;
            }
        }
        throw new AssertionError("no free block of " + SLOTS + " consecutive ports found between "
                                 + FIRST_CANDIDATE_BASE + " and " + LAST_CANDIDATE_BASE);
    }

    private static boolean blockIsFree(int base) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (!udpFree(base + slot)
                || !tcpFree(base + slot)
                || !tcpFree(base + MGMT_OFFSET + slot)
                || !tcpFree(base + APP_HTTP_OFFSET + slot)) {
                return false;
            }
        }
        return true;
    }

    private static boolean tcpFree(int port) {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(loopback(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean udpFree(int port) {
        try (var socket = new DatagramSocket(null)) {
            socket.setReuseAddress(false);
            socket.bind(loopback(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }
}
