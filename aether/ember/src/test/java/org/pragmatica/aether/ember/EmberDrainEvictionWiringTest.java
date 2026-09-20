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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
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
/// The loop has a SECOND entry: `resumeDrainEvictions`, called from every `reconcile()` tick
/// (`deployment.reconciliationInterval`, 30s). It reads the same readiness view, so a tick would
/// eventually log the same line and a purely time-bounded assertion would be a race against it.
/// `resumeDrainEvictions` logs `Resuming drain evictions for N nodes` immediately before it calls
/// `startDrainEviction`, so the test attributes by PRODUCER instead: no such line may precede the
/// eviction. With the registration reverted the tick becomes the only producer, and both halves of
/// the assertion fail — the line is absent inside the bound, and when it does arrive it is preceded
/// by the `Resuming` marker.
///
/// A fabricated worker id is used as the drainee (as in `EmberDrainAcknowledgementWiringTest`) so the
/// reconciler neither drains nor dials a real peer; it holds no slice, which is what makes
/// `completeDrain` the terminal step.
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
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    /// Well inside the 30s reconcile interval, so the tick is not a plausible producer within it.
    private static final long EVICTION_BOUND_MS = 10_000L;
    private static final long POLL_MS = 100;
    private static final String ACTIVE_LOGGER =
            "org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState$Active";
    private static final String RESUME_MARKER = "Resuming drain evictions";

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
        var basePort = freeBasePort();
        cluster = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "devi");
        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started"))
            .describedAs("a three-node cluster on a verified-free port block at %d must form", basePort)
            .isEqualTo("started");

        var leader = awaitLeader();
        var drainee = NodeId.nodeId("drainee-evict-" + UUID.randomUUID()).unwrap();
        var drainingAtEvictionEntry = new java.util.concurrent.atomic.AtomicBoolean();
        var capture = EvictionLogCapture.attach(message -> {
            if (message.contains("Starting drain eviction for node " + drainee)) {
                drainingAtEvictionEntry.set(leader.metricsCollector().reportedStates().get(drainee) == NodeReportedState.DRAINING);
            }
        });

        try {
            leader.metricsCollector().onClusterSyncPong(drainingPong(drainee));

            assertThat(drainingAtEvictionEntry.get())
                .describedAs("the DRAINING report must be present when its synchronous eviction callback starts; "
                             + "a later readiness sweep may expire it while that callback waits for the KV monitor")
                .isTrue();

            var started = "Starting drain eviction for node " + drainee;
            var completed = "Drain complete for node " + drainee;

            awaitCondition("the DRAINING pong must start the leader-side drain eviction — the registration in "
                           + "assembleNode is the only production path to it",
                           System.currentTimeMillis() + EVICTION_BOUND_MS,
                           () -> capture.contains(started),
                           capture::messages);
            awaitCondition("the loop must RUN, not merely log its entry: with no slice on the drainee its "
                           + "terminal step is completeDrain",
                           System.currentTimeMillis() + EVICTION_BOUND_MS,
                           () -> capture.contains(completed),
                           capture::messages);

            assertThat(capture.messagesBefore(started))
                .describedAs("the eviction must be attributed to the pong, not to a reconcile tick that "
                             + "happened to land inside the bound")
                .noneMatch(message -> message.contains(RESUME_MARKER));
        } finally {
            capture.detach();
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
        return new ClusterSyncPong(sender, new MetricObservation(1L, System.nanoTime(), System.currentTimeMillis(), Map.of()), 0L, 0L, 0L, NodeReportedState.DRAINING.name(), List.of(), List.of(), List.of(), Option.none());
    }

    /// Log4j2 programmatic appender over the eviction loop's own logger, capturing INFO in arrival
    /// order — the order is what carries the producer attribution above.
    private static final class EvictionLogCapture extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final LoggerConfig loggerConfig;
        private final Level originalLevel;
        private final java.util.function.Consumer<String> observation;

        private EvictionLogCapture(Layout<?> layout, LoggerConfig loggerConfig, Level originalLevel,
                                   java.util.function.Consumer<String> observation) {
            super(ACTIVE_LOGGER + "-eviction-capture", (Filter) null, layout, true, Property.EMPTY_ARRAY);
            this.loggerConfig = loggerConfig;
            this.originalLevel = originalLevel;
            this.observation = observation;
        }

        static EvictionLogCapture attach(java.util.function.Consumer<String> observation) {
            var ctx = (LoggerContext) LogManager.getContext(false);
            var loggerConfig = getOrCreateLoggerConfig(ctx.getConfiguration());
            var capture = new EvictionLogCapture(PatternLayout.createDefaultLayout(),
                                                 loggerConfig,
                                                 loggerConfig.getLevel(), observation);

            capture.start();
            loggerConfig.addAppender(capture, Level.INFO, null);
            loggerConfig.setLevel(Level.INFO);
            ctx.updateLoggers();

            return capture;
        }

        void detach() {
            var ctx = (LoggerContext) LogManager.getContext(false);

            loggerConfig.removeAppender(getName());
            loggerConfig.setLevel(originalLevel);
            ctx.updateLoggers();
            stop();
        }

        List<String> messages() {
            return List.copyOf(messages);
        }

        boolean contains(String text) {
            return messages().stream()
                             .anyMatch(message -> message.contains(text));
        }

        /// Every message logged before the first occurrence of `text`; empty if it has not arrived.
        List<String> messagesBefore(String text) {
            var snapshot = messages();

            for (var index = 0; index < snapshot.size(); index++) {
                if (snapshot.get(index)
                            .contains(text)) {
                    return snapshot.subList(0, index);
                }
            }

            return List.of();
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.INFO)) {
                var message = event.getMessage().getFormattedMessage();
                observation.accept(message);
                messages.add(message);
            }
        }

        private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
            var existing = configuration.getLoggerConfig(ACTIVE_LOGGER);

            if (ACTIVE_LOGGER.equals(existing.getName())) {
                return existing;
            }

            var fresh = new LoggerConfig(ACTIVE_LOGGER, Level.INFO, true);

            configuration.addLogger(ACTIVE_LOGGER, fresh);

            return fresh;
        }
    }

    /// The first candidate base whose whole block (QUIC UDP + TCP cluster ports, management and app-HTTP
    /// ports) binds free right now — the shared-box guard the other Ember cluster tests use, on a
    /// candidate range disjoint from theirs so two classes never probe the same block.
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
