// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementApiResponses.BackfillMetricsRequest;
import org.pragmatica.aether.api.ManagementApiResponses.BackfillMetricsResponse;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.ClusterSyncCollector.MetricsSnapshot;
import org.pragmatica.aether.metrics.ComprehensiveSnapshotCollector;
import org.pragmatica.aether.metrics.MinuteAggregator;
import org.pragmatica.aether.metrics.consensus.RabiaMetricsCollector;
import org.pragmatica.aether.metrics.eventloop.EventLoopMetricsCollector;
import org.pragmatica.aether.metrics.gc.GCMetricsCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.metrics.observability.ObservabilityRegistry;
import org.pragmatica.aether.metrics.timeout.TimeoutMetricsRegistry;
import org.pragmatica.aether.metrics.timeout.TimeoutSubsystem;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.Phase;
import org.pragmatica.consensus.rabia.StateValue;
import org.pragmatica.lang.Option;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Covers the two new metrics endpoints introduced by P-NEW-A
/// (per-subsystem timeout-fired counter) and P-NEW-D (synthetic-history
/// backfill, dev-mode-only). See
/// `aether/docs/internal/production-readiness-followup-2026-05-21.md`.
///
/// Validates:
///   - `/api/metrics/timeouts` shape (every `TimeoutSubsystem` present),
///     counter increments visible in subsequent reads, isolation between
///     subsystems.
///   - `/api/metrics/backfill` dev-mode gate, validation rules (missing
///     metric, inverted window, zero interval), sample-count math,
///     synthetic samples actually appended to the collector via the
///     interface's new `injectHistoricalSnapshot(...)` hook.
class MetricsRoutesTest {

    private static final NodeId LOCAL_NODE = new NodeId("node-1");

    private TimeoutMetricsRegistry timeouts;
    private RecordingCollector collector;
    private RecordingNode node;

    @BeforeEach
    void setUp() {
        timeouts = TimeoutMetricsRegistry.timeoutMetricsRegistry();
        collector = new RecordingCollector();
        node = new RecordingNode(LOCAL_NODE, collector.asClusterSyncCollector());
    }

    private MetricsRoutes routesWithDevMode(boolean devMode) {
        BooleanSupplier devModeFlag = () -> devMode;
        return MetricsRoutes.metricsRoutes(() -> node.asManageableNode(),
                                            ObservabilityRegistry.prometheus(),
                                            timeouts,
                                            devModeFlag);
    }

    @Nested
    class TimeoutsEndpoint {

        @Test
        void timeouts_initial_allSubsystemsZero() {
            var routes = routesWithDevMode(false);

            var response = routes.buildTimeoutMetricsForTest();

            assertNotNull(response);
            assertEquals(TimeoutSubsystem.values().length, response.subsystems().size(),
                          "Every subsystem must be present in the response");
            for (var subsystem : TimeoutSubsystem.values()) {
                var entry = response.subsystems().get(subsystem.id());
                assertNotNull(entry, "Missing entry for subsystem " + subsystem.id());
                assertEquals(0L, entry.firedCount(),
                              "Counter for " + subsystem.id() + " must start at 0");
            }
        }

        @Test
        void timeouts_reflectsRegistryIncrements() {
            var routes = routesWithDevMode(false);
            timeouts.recordTimeout(TimeoutSubsystem.CONSENSUS);
            timeouts.recordTimeout(TimeoutSubsystem.CONSENSUS);
            timeouts.recordTimeout(TimeoutSubsystem.DHT);

            var response = routes.buildTimeoutMetricsForTest();

            assertEquals(2L, response.subsystems().get("consensus").firedCount());
            assertEquals(1L, response.subsystems().get("dht").firedCount());
            assertEquals(0L, response.subsystems().get("swim").firedCount(),
                          "Untouched subsystem must remain at 0");
        }

        @Test
        void routes_includesTimeoutsRoute() {
            var routes = routesWithDevMode(false);

            var names = routes.routes().map(r -> r.name()).toList();

            assertThat(names).contains("METRICS_TIMEOUTS");
        }
    }

    @Nested
    class BackfillDevModeGate {

        @Test
        void backfill_rejected_whenDevModeDisabled() {
            var routes = routesWithDevMode(false);

            var result = routes.handleBackfillForTest(new BackfillMetricsRequest("cpu.usage", 0, 10, 1, "constant:1.0"))
                                .onSuccess(_ -> fail("Backfill must be rejected when AETHER_INSECURE_DEV_MODE is not set"))
                                .await();

            assertTrue(result.isFailure(), "Result must be failure when dev-mode disabled");
            result.onFailure(cause -> assertTrue(cause.message().contains("AETHER_INSECURE_DEV_MODE"),
                                                  "Failure must mention the gate env var: " + cause.message()));
            assertThat(collector.injectedSnapshots).isEmpty();
        }
    }

    @Nested
    class BackfillValidation {

        @Test
        void backfill_missingMetric_returnsFailure() {
            var routes = routesWithDevMode(true);

            var result = routes.handleBackfillForTest(new BackfillMetricsRequest("", 0, 10, 1, "constant:1.0"))
                                .onSuccess(_ -> fail("Blank metric must be rejected"))
                                .await();

            assertTrue(result.isFailure());
            result.onFailure(cause -> assertTrue(cause.message().toLowerCase().contains("metric"),
                                                  "Failure must mention missing metric: " + cause.message()));
        }

        @Test
        void backfill_invertedWindow_returnsFailure() {
            var routes = routesWithDevMode(true);

            var result = routes.handleBackfillForTest(new BackfillMetricsRequest("cpu.usage", 100, 50, 1, "linear"))
                                .onSuccess(_ -> fail("Inverted window must be rejected"))
                                .await();

            assertTrue(result.isFailure());
            result.onFailure(cause -> assertTrue(cause.message().toLowerCase().contains("starttime"),
                                                  "Failure must mention the bad window: " + cause.message()));
        }

        @Test
        void backfill_zeroInterval_returnsFailure() {
            var routes = routesWithDevMode(true);

            var result = routes.handleBackfillForTest(new BackfillMetricsRequest("cpu.usage", 0, 10, 0, "linear"))
                                .onSuccess(_ -> fail("Zero interval must be rejected"))
                                .await();

            assertTrue(result.isFailure());
            result.onFailure(cause -> assertTrue(cause.message().toLowerCase().contains("interval"),
                                                  "Failure must mention bad interval: " + cause.message()));
        }
    }

    @Nested
    class BackfillHappyPath {

        @Test
        void backfill_writesExpectedSampleCount() {
            var routes = routesWithDevMode(true);

            var response = routes.handleBackfillForTest(new BackfillMetricsRequest("cpu.usage", 0L, 1_000L, 100L, "constant:0.42"))
                                  .onFailure(cause -> fail("Backfill must succeed: " + cause.message()))
                                  .await()
                                  .or((BackfillMetricsResponse) null);

            assertNotNull(response);
            assertEquals(LOCAL_NODE.id(), response.nodeId());
            assertEquals("cpu.usage", response.metric());
            // 11 samples: t=0,100,200,...,1000 inclusive.
            assertEquals(11L, response.samplesWritten());
            assertEquals(11, collector.injectedSnapshots.size(),
                          "Collector must receive 11 inject calls (one per sample)");

            var first = collector.injectedSnapshots.get(0);
            assertEquals(LOCAL_NODE, first.nodeId);
            assertEquals(0L, first.snapshot.timestamp());
            assertEquals(0.42, first.snapshot.metrics().get("cpu.usage"), 1e-9);
        }

        @Test
        void backfill_linearGenerator_producesRamp() {
            var routes = routesWithDevMode(true);

            routes.handleBackfillForTest(new BackfillMetricsRequest("cpu.usage", 0L, 10L, 5L, "linear"))
                  .onFailure(cause -> fail("Backfill must succeed: " + cause.message()))
                  .await();

            assertEquals(3, collector.injectedSnapshots.size(),
                          "11 samples expected for [0,10] @ step 5 inclusive");
            assertEquals(0.0, collector.injectedSnapshots.get(0).snapshot.metrics().get("cpu.usage"), 1e-9,
                          "Linear: progress 0.0 at startTime");
            assertEquals(0.5, collector.injectedSnapshots.get(1).snapshot.metrics().get("cpu.usage"), 1e-9,
                          "Linear: progress 0.5 at midpoint");
            assertEquals(1.0, collector.injectedSnapshots.get(2).snapshot.metrics().get("cpu.usage"), 1e-9,
                          "Linear: progress 1.0 at endTime");
        }

        @Test
        void routes_includesBackfillRoute() {
            var routes = routesWithDevMode(true);

            var names = routes.routes().map(r -> r.name()).toList();

            assertThat(names).contains("METRICS_BACKFILL");
        }
    }

    // --- helpers ---

    private record InjectCall(NodeId nodeId, MetricsSnapshot snapshot) {}

    private static final class RecordingCollector {
        final List<InjectCall> injectedSnapshots = new CopyOnWriteArrayList<>();

        ClusterSyncCollector asClusterSyncCollector() {
            return (ClusterSyncCollector) Proxy.newProxyInstance(
                ClusterSyncCollector.class.getClassLoader(),
                new Class[]{ClusterSyncCollector.class},
                (_, method, args) -> {
                    if ("injectHistoricalSnapshot".equals(method.getName()) && args != null && args.length == 2) {
                        injectedSnapshots.add(new InjectCall((NodeId) args[0], (MetricsSnapshot) args[1]));
                        return null;
                    }
                    throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
                }
            );
        }
    }

    @Nested
    class ComprehensiveConsensusBlock {

        /// The #674 pin at the route boundary: the consensus block rides the comprehensive response
        /// with LIVE collector totals — including on the empty-minute-aggregate branch, which is
        /// exactly the state a freshly-started node serves its first request from. Before the fix
        /// the DTO had no consensus field at all and the collected block was dropped here.
        @Test
        void comprehensive_carriesLiveConsensusBlock_evenBeforeFirstMinuteAggregate() {
            var rabia = RabiaMetricsCollector.rabiaMetricsCollector();

            rabia.recordProposal(LOCAL_NODE, new Phase(1));
            rabia.recordVoteRound1(LOCAL_NODE, new Phase(1), StateValue.V1);
            rabia.recordVoteRound1(LOCAL_NODE, new Phase(1), StateValue.V0);
            rabia.recordVoteRound2(LOCAL_NODE, new Phase(1), StateValue.V1);
            rabia.updateRole(true, Option.some("node-1"));

            var snapshotCollector = ComprehensiveSnapshotCollector.comprehensiveSnapshotCollector(
                GCMetricsCollector.gcMetricsCollector(),
                EventLoopMetricsCollector.eventLoopMetricsCollector(),
                rabia,
                InvocationMetricsCollector.invocationMetricsCollector(),
                MinuteAggregator.minuteAggregator());
            var routes = MetricsRoutes.metricsRoutes(() -> nodeWithSnapshotCollector(snapshotCollector),
                                                     ObservabilityRegistry.prometheus(),
                                                     timeouts,
                                                     () -> false);

            var response = routes.buildComprehensiveMetricsResponseForTest();

            assertEquals(0, response.minuteTimestamp(), "no minute bucket exists yet — the aggregate half is zeros");
            assertEquals("LEADER", response.consensus().role());
            assertEquals(1, response.consensus().proposalsCount());
            assertEquals(2, response.consensus().voteRound1Count());
            assertEquals(1, response.consensus().voteRound2Count());
            assertEquals(0, response.consensus().fastPathCount());
        }

        private ManageableNode nodeWithSnapshotCollector(ComprehensiveSnapshotCollector snapshotCollector) {
            return (ManageableNode) Proxy.newProxyInstance(
                ManageableNode.class.getClassLoader(),
                new Class[]{ManageableNode.class},
                (_, method, _) -> switch (method.getName()) {
                    case "self" -> LOCAL_NODE;
                    case "snapshotCollector" -> snapshotCollector;
                    default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
                }
            );
        }
    }

    private static final class RecordingNode {
        final NodeId self;
        final ClusterSyncCollector collector;
        final List<String> calls = new ArrayList<>();

        RecordingNode(NodeId self, ClusterSyncCollector collector) {
            this.self = self;
            this.collector = collector;
        }

        ManageableNode asManageableNode() {
            return (ManageableNode) Proxy.newProxyInstance(
                ManageableNode.class.getClassLoader(),
                new Class[]{ManageableNode.class},
                (_, method, args) -> {
                    calls.add(method.getName());
                    return switch (method.getName()) {
                        case "self" -> self;
                        case "metricsCollector" -> collector;
                        default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
                    };
                }
            );
        }
    }
}
