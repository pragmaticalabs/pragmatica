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
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterState;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.deployment.SeamPoller;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
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
/// ## Two engines, one property: the pong is what starts the eviction, the reconcile tick is not
///
/// `deployment.reconciliationInterval` is set to [#RECONCILE_INTERVAL] (beyond the test's own `@Timeout`) on every node, through
/// `EmberCluster.withReconciliationIntervalForNewNodes`. Legacy reads it for the CDM's `reconcile()` tick, FSM2 for `Timing.withTick`
/// (`Fsm2System.timing`), so within the test the periodic tick cannot be the producer on either engine. The engine is read from the
/// started nodes (`deploymentWiring().engine()`) and each arm names its own anchors; nothing is skipped.
///
/// - LEGACY: the anchors and the pong-thread attribution above. The pong's entry is synchronous.
/// - FSM2: the pong's `ClusterInput.NodeDraining` is a no-op until the 5 s-sampled seams list the drainee (`Drains.start`); the
///   first effective start is the seam sample that sees the drainee, whose `Lifecycle.onSeams` requests a reconcile at once (not the
///   tick), and `Drains.resume` starts the loop. There is no synchronous entry, so thread attribution does not apply. The precondition is the
///   cluster interpreter's state being `ClusterState.Active` (raised when the rebuild completes, the counterpart of `REBUILT_MARKER`), and
///   the drainee keeps reporting at the ping interval (one pong is forgotten by the readiness sweep within three pings, before a 5 s
///   sample; it stops reporting once the sampled seams list it, so the on-change reconcile request is the only producer of the loop), and the observable is `ClusterEffect.Log` "Drain complete for node <id>" (the bare id, unlike the legacy line) (`Drains.advance`, logged by `EffectTranslator` on
///   [#FSM2_LOGGER]). It must arrive within one seam interval (`SeamPoller.INTERVAL_MS`) plus [#FSM2_SLACK_MS] of the pong.
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
    /// The cluster ping interval (`ClusterTimeouts.pingInterval`, 1 s by default): how often a real drainee reports.
    private static final long PING_MS = 1_000L;
    /// Beyond the test's `@Timeout(300)`: the periodic tick of either engine cannot fire within the test.
    private static final TimeSpan RECONCILE_INTERVAL = TimeSpan.timeSpan(1).hours();
    /// Logger `EffectTranslator` writes `ClusterEffect.Log` to under FSM2.
    private static final String FSM2_LOGGER = "org.pragmatica.aether.deployment.fsm.cluster";
    /// Stated slack on top of one seam interval: the sample's own jitter plus the interpreter's consumer hop.
    private static final long FSM2_SLACK_MS = 10_000L;
    private static final String ACTIVE_LOGGER =
            "org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState$Active";
    /// First line of `ClusterDeploymentState.Active.onEntry`; the FSM state is already Active when it is logged.
    private static final String ACTIVATION_MARKER = "became leader, activating cluster deployment manager";
    /// The last line of `Active.onEntry`: a pong sent before it can meet an `Active` that is still entering and be ignored.
    private static final String REBUILT_MARKER = "Cluster deployment manager activation complete on node";

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
        cluster = EmberTestPorts.startedCluster(PORTS, EmberDrainEvictionWiringTest::tickExcludedCluster, START_BOUND);

        var engine = activeEngine();

        System.out.println("EmberDrainEvictionWiringTest engine=" + engine);

        var drainee = NodeId.nodeId("drainee-evict-" + UUID.randomUUID()).unwrap();
        var pongThread = Thread.currentThread().getName();
        var drainingAtPongThreadEviction = new java.util.concurrent.atomic.AtomicBoolean();
        var leaderRef = new java.util.concurrent.atomic.AtomicReference<AetherNode>();
        var capture = LogCapture.capture(engine == DeploymentEngine.FSM2 ? FSM2_LOGGER : ACTIVE_LOGGER, Level.INFO, true, event -> {
            if (event.getMessage().getFormattedMessage().contains("Starting drain eviction for node " + drainee)
                    && pongThread.equals(event.getThreadName())) {
                drainingAtPongThreadEviction.set(leaderRef.get().metricsCollector().reportedStates().get(drainee) == NodeReportedState.DRAINING);
            }
        });

        try {
            var leader = awaitLeader();

            leaderRef.set(leader);
            if (engine == DeploymentEngine.FSM2) {
                awaitFsm2DrainComplete(leader, capture, drainee);
            } else {
                awaitLegacyEvictionFromPong(leader, capture, drainee, drainingAtPongThreadEviction);
            }
        } finally {
            capture.close();
        }
    }

    private static EmberCluster tickExcludedCluster(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "devi");

        built.withReconciliationIntervalForNewNodes(RECONCILE_INTERVAL);
        return built;
    }

    private DeploymentEngine activeEngine() {
        var engines = cluster.allNodes().stream().map(node -> node.deploymentWiring().engine()).distinct().toList();

        assertThat(engines).describedAs("every node of the cluster runs the one selected engine").hasSize(1);
        return engines.getFirst();
    }

    /// FSM2 arm: the pong is delivered to an `Active` cluster FSM, and the loop must complete within one seam interval plus the slack. The
    /// reconcile tick is [#RECONCILE_INTERVAL] away, so the only producers are the pong and the seam sample's own reconcile request.
    private void awaitFsm2DrainComplete(AetherNode leader, LogCapture capture, NodeId drainee) {
        awaitCondition("the leader's cluster FSM must be Active (rebuild complete) before the pong is sent",
                       System.currentTimeMillis() + 60_000L,
                       () -> clusterFsmActive(leader),
                       () -> List.of(String.valueOf(leader.deploymentWiring().system().map(system -> system.clusterInterpreter().state()))));
        var lastPongMs = new long[]{System.currentTimeMillis()};

        leader.metricsCollector().onClusterSyncPong(drainingPong(drainee));

        // A draining node keeps reporting every ping; one pong would be forgotten by the readiness sweep within three pings, before the
        // 5 s seam sample can see it. So the report is repeated at the ping interval, as a real drainee's is.
        awaitCondition("under FSM2 the DRAINING reports must lead to the drain loop completing within one seam interval ("
                       + SeamPoller.INTERVAL_MS + " ms) plus " + FSM2_SLACK_MS + " ms of the first, with the reconcile tick " + RECONCILE_INTERVAL
                       + " away",
                       System.currentTimeMillis() + SeamPoller.INTERVAL_MS + FSM2_SLACK_MS,
                       () -> repeatReport(leader, drainee, lastPongMs) || contains(capture, "Drain complete for node " + drainee.id()),
                       capture::messages);
    }

    /// Re-sends the DRAINING report once per ping interval until the seam sample lists the drainee; false, so the caller's own condition decides.
    private static boolean repeatReport(AetherNode leader, NodeId drainee, long[] lastPongMs) {
        var now = System.currentTimeMillis();

        if (now - lastPongMs[0] >= PING_MS && !seamsListDrainee(leader, drainee)) {
            lastPongMs[0] = now;
            leader.metricsCollector().onClusterSyncPong(drainingPong(drainee));
        }
        return false;
    }

    /// True once the sampled seams list the drainee. From then on no further report is sent: a later report would pass `Drains.start`'s
    /// seam gate and start the loop itself, so the only producer left is the on-change `Reconcile.request` of `Lifecycle.onSeams`.
    private static boolean seamsListDrainee(AetherNode leader, NodeId drainee) {
        return leader.deploymentWiring()
                     .system()
                     .map(system -> system.clusterInterpreter().state())
                     .filter(state -> state instanceof ClusterState.Active)
                     .map(state -> ((ClusterState.Active) state).model().seams().draining().contains(drainee))
                     .or(false);
    }

    private static boolean clusterFsmActive(AetherNode leader) {
        return leader.deploymentWiring().system().map(system -> system.clusterInterpreter().state() instanceof ClusterState.Active).or(false);
    }

    /// LEGACY arm: the pong's entry is synchronous, so the eviction must start on the pong's own thread (see the class doc).
    private void awaitLegacyEvictionFromPong(AetherNode leader,
                                             LogCapture capture,
                                             NodeId drainee,
                                             java.util.concurrent.atomic.AtomicBoolean drainingAtPongThreadEviction) {
        awaitCondition("the leader's cluster deployment manager must log its activation (it is Active from that line on) before the pong is sent",
                       System.currentTimeMillis() + 60_000L,
                       () -> contains(capture, ACTIVATION_MARKER),
                       capture::messages);
        awaitCondition("the leader's cluster deployment manager must finish its activation before the pong is sent",
                       System.currentTimeMillis() + 60_000L,
                       () -> contains(capture, REBUILT_MARKER),
                       capture::messages);
        leader.metricsCollector().onClusterSyncPong(drainingPong(drainee));

        assertThat(drainingAtPongThreadEviction.get())
            .describedAs("the pong's synchronous callback must start the eviction on the pong's own thread while "
                         + "the DRAINING report is present; false means the registration is missing or the CDM "
                         + "ignored the pong. The pong is sent after the CDM logged the END of its activation, so it meets a fully entered "
                         + "Active CDM")
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
