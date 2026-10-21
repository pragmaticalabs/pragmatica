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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #688 round 2 — the LEADER-SIDE half of the drain report, pinned THROUGH THE ASSEMBLED NODE.
///
/// `AetherNode.assembleNode` registers ONE listener on the pong fan for both halves of a drain:
/// `pongSignalFan.onDrainingReported(drainReportListener(membershipFsm, clusterDeploymentManager))`.
/// That single line is the ENTIRE production effect of #688 — the CDM's legacy
/// `MembershipDecision.NodeDraining` arm is never emitted, so nothing else reaches
/// `ClusterDeploymentState.startDrainEviction`.
///
/// `AetherNodeDrainReportListenerTest` (aether/node) calls `drainReportListener(fsm, cdm)` itself and
/// so pins the helper's BODY, never its USE: reverting the registration line left that test green,
/// which is the review finding this class answers. `EmberDrainAcknowledgementWiringTest` next door
/// covers the membership-FSM half of the same line and stays green when the CDM half is dropped.
/// Nothing between the pong and the eviction loop is a test double here.
///
/// ## Why the assertion is a log line, and why it is attributed rather than timed
///
/// A drain eviction writes no KV command when the drainee holds no slice — it runs
/// `startDrainEviction` → `evictNextSliceFromNode` → `completeDrain` — so the loop's own INFO lines
/// are the observable. Both are asserted, because the first alone would be satisfied by a loop that
/// logged and then bailed at its own draining-set check.
///
/// The loop has more entries than the pong: `resumeDrainEvictions` runs from `Active.onEntry` (the leader's
/// activation) and from every `reconcile()` tick (`deployment.reconciliationInterval`, 30s), and reads the same
/// readiness view, so either would eventually log the same line. The pong's own entry is SYNCHRONOUS — the pong fan
/// calls the listener, which dispatches into the CDM FSM, on the thread that delivered the pong — whereas both resume
/// entries run on the scheduler or leader-change thread. The test therefore attributes by THREAD: the eviction must
/// start on the thread that delivered the pong, with the DRAINING report still present. With the registration
/// reverted the only producers are the resume entries, which never run on the test thread, so the assertion fails.
///
/// The pong is sent only after the leader's CDM has logged its activation: `LeaderManager` reports the leader
/// before the CDM's leader-change handler has moved it from Dormant to Active, and a pong dispatched to a Dormant CDM
/// is ignored (the eviction then starts from `Active.onEntry`'s resume, on another thread). The resume inside
/// `onEntry` may still interleave with the pong; it starts the eviction first (and, with no slice on the drainee,
/// completes it in the same call), after which the pong's own call starts a second episode on the pong thread, which
/// is what the thread attribution observes.
///
/// A fabricated worker id is used as the drainee (as in `EmberDrainAcknowledgementWiringTest`) so the
/// reconciler neither drains nor dials a real peer; it holds no slice, which is what makes
/// `completeDrain` the terminal step.
@PortBudget
class EmberDrainEvictionWiringTest {
    private static final int CLUSTER_SIZE = 3;
    /// `EmberCluster.start` builds a slot pool of `2 * clusterSize`.
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    /// Disjoint from every other Ember test's candidate range (25600, 25700-27500, 27700-29500).
    private static final int FIRST_CANDIDATE_BASE = 29700;
    private static final int LAST_CANDIDATE_BASE = 31500;
    private static final int CANDIDATE_STEP = 200;
    /// #1667: probed through the shared EmberTestPorts, which also probes each node's SWIM UDP port.
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(FIRST_CANDIDATE_BASE,
                                                                                LAST_CANDIDATE_BASE,
                                                                                CANDIDATE_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    /// Well inside the 30s reconcile interval, so the tick is not a plausible producer within it.
    private static final long EVICTION_BOUND_MS = 10_000L;
    private static final long POLL_MS = 100;
    private static final String ACTIVE_LOGGER =
            "org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState$Active";
    /// First line of `ClusterDeploymentState.Active.onEntry`; the FSM state is already Active when it is logged.
    private static final String ACTIVATION_MARKER = "became leader, activating cluster deployment manager";

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
    void leaderNode_drainingPong_startsTheLeaderSideEviction_andNotViaTheReconcileTick() {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                basePort -> emberCluster(CLUSTER_SIZE,
                                                                         basePort,
                                                                         basePort + MGMT_OFFSET,
                                                                         basePort + APP_HTTP_OFFSET,
                                                                         "devi"),
                                                START_BOUND);

        var drainee = NodeId.nodeId("drainee-evict-" + UUID.randomUUID()).unwrap();
        var pongThread = Thread.currentThread().getName();
        var drainingAtPongThreadEviction = new java.util.concurrent.atomic.AtomicBoolean();
        var leaderRef = new java.util.concurrent.atomic.AtomicReference<AetherNode>();
        var capture = LogCapture.capture(ACTIVE_LOGGER, Level.INFO, true, event -> {
            if (event.getMessage().getFormattedMessage().contains("Starting drain eviction for node " + drainee)
                    && pongThread.equals(event.getThreadName())) {
                drainingAtPongThreadEviction.set(leaderRef.get().metricsCollector().reportedStates().get(drainee) == NodeReportedState.DRAINING);
            }
        });

        try {
            var leader = awaitLeader();

            awaitCondition("the leader's cluster deployment manager must log its activation (it is Active from that line on) before the pong is sent",
                           System.currentTimeMillis() + 60_000L,
                           () -> contains(capture, ACTIVATION_MARKER),
                           capture::messages);
            leaderRef.set(leader);
            leader.metricsCollector().onClusterSyncPong(drainingPong(drainee));

            assertThat(drainingAtPongThreadEviction.get())
                .describedAs("the pong's synchronous callback must start the eviction on the pong's own thread while "
                             + "the DRAINING report is present; false means the registration is missing or the CDM "
                             + "ignored the pong. Timing assumption: the pong is sent after the CDM logged its activation, "
                             + "so it meets an Active CDM. Residual window: that line is the first of Active.onEntry, so a "
                             + "pong landing while onEntry is still running can be ignored and the eviction then starts "
                             + "from onEntry's resume on another thread; a failure here with the registration intact "
                             + "points at that window, not at the product")
                .isTrue();

            var started = "Starting drain eviction for node " + drainee;
            var completed = "Drain complete for node " + drainee;

            awaitCondition("the DRAINING pong must start the leader-side drain eviction — the registration in "
                           + "assembleNode is the only production path to it",
                           System.currentTimeMillis() + EVICTION_BOUND_MS,
                           () -> contains(capture, started),
                           capture::messages);
            awaitCondition("the loop must RUN, not merely log its entry: with no slice on the drainee its "
                           + "terminal step is completeDrain",
                           System.currentTimeMillis() + EVICTION_BOUND_MS,
                           () -> contains(capture, completed),
                           capture::messages);
        } finally {
            capture.close();
        }
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
                                       Supplier<List<String>> observed) {
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
        return new ClusterSyncPong(sender, new MetricObservation(1L, System.nanoTime(), System.currentTimeMillis(), Map.of()),
                                   1L, 0L, 0L, 0L, 0L, NodeReportedState.DRAINING.name(), List.of(), List.of(), List.of(), Option.none());
    }

    private static boolean contains(LogCapture capture, String text) {
        return capture.messages().stream().anyMatch(message -> message.contains(text));
    }
}
