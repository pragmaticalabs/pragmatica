// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.utils.TimeSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.ntt.DivergenceLogger.divergenceLogger;
import static org.pragmatica.aether.deployment.membership.ntt.FsmDecisionEvent.fsmDecisionEvent;
import static org.pragmatica.aether.deployment.membership.ntt.ReconcileIntent.reconcileIntent;
import static org.pragmatica.lang.Option.some;


/// Unit tests for [`DivergenceLogger`] (E2 Phase 1.5) — NTT-side observations
/// are now cluster-wide-only (intents no longer carry per-peer payload); FSM-side
/// per-peer correlation continues to surface FSM_ONLY divergent verdicts.
class DivergenceLoggerTest {
    private static final NodeId PEER_A = NodeId.randomNodeId();
    private static final NodeId PEER_B = NodeId.randomNodeId();
    private static final long SECONDS_31 = TimeUnit.SECONDS.toNanos(31L);

    private TestTimeSource timeSource;
    private LineCollector collector;
    private DivergenceLogger logger;

    @BeforeEach
    void setUp() {
        timeSource = new TestTimeSource();
        collector = new LineCollector();
        logger = divergenceLogger(timeSource, collector);
    }

    @Nested
    class Observation {
        @Test
        void observeNttIntent_logsClusterWideStructuredLine_noBuffering() {
            logger.observeNttIntent(provisionIntent());

            assertThat(logger.bufferedNttCount()).isZero();
            assertThat(logger.bufferedFsmCount()).isZero();
            assertThat(collector.linesContaining("source=NTT")).hasSize(1);
            assertThat(collector.lastLine()).contains("[v2-divergence]")
                                            .contains("source=NTT")
                                            .contains("peer=<cluster-wide>")
                                            .contains("trigger=NTT_FIRE")
                                            .contains("configured=5")
                                            .contains("observed=3")
                                            .contains("action=provision");
        }

        @Test
        void observeFsmDecision_logsStructuredLine_andBuffers() {
            logger.observeFsmDecision(decommissionDecisionFor(PEER_A));

            assertThat(logger.bufferedFsmCount()).isEqualTo(1);
            assertThat(logger.bufferedNttCount()).isZero();
            assertThat(collector.linesContaining("source=FSM")).hasSize(1);
            assertThat(collector.lastLine()).contains("[v2-divergence]")
                                            .contains("source=FSM")
                                            .contains("peer=" + PEER_A.id())
                                            .contains("type=DECOMMISSION")
                                            .contains("reason=SwimFaulty")
                                            .contains("stateBefore=OnDuty")
                                            .contains("stateAfter=Stopped");
        }
    }

    @Nested
    class CorrelationSweep {
        @Test
        void sweep_onlyFsmBufferedPastWindow_emitsDivergentFsmOnly_andDropsThatSide() {
            logger.observeFsmDecision(decommissionDecisionFor(PEER_A));
            timeSource.advanceNanos(SECONDS_31);

            collector.clear();
            logger.runCorrelationSweep();

            assertThat(collector.linesContaining("verdict=DIVERGENT")).hasSize(1);
            assertThat(collector.lastLine()).contains("peer=" + PEER_A.id())
                                            .contains("side=FSM_ONLY")
                                            .contains("buffered_for_ms=31000")
                                            .contains("type=DECOMMISSION")
                                            .contains("reason=SwimFaulty");
            assertThat(logger.bufferedFsmCount()).isZero();
        }

        @Test
        void sweep_fsmWithinWindow_emitsNothing_keepsBuffer() {
            logger.observeFsmDecision(decommissionDecisionFor(PEER_A));
            timeSource.advanceMillis(1_000L);

            collector.clear();
            logger.runCorrelationSweep();

            assertThat(collector.allLines()).isEmpty();
            assertThat(logger.bufferedFsmCount()).isEqualTo(1);
        }

        @Test
        void sweep_twoFsmPeers_independentlyEvaluated() {
            logger.observeFsmDecision(decommissionDecisionFor(PEER_A));
            timeSource.advanceMillis(1_000L);
            logger.observeFsmDecision(decommissionDecisionFor(PEER_B));
            timeSource.advanceNanos(SECONDS_31);

            collector.clear();
            logger.runCorrelationSweep();

            assertThat(collector.linesContaining("verdict=DIVERGENT")).hasSize(2);
            assertThat(collector.linesContaining("peer=" + PEER_A.id())).anyMatch(line -> line.contains("FSM_ONLY"));
            assertThat(collector.linesContaining("peer=" + PEER_B.id())).anyMatch(line -> line.contains("FSM_ONLY"));
            assertThat(logger.bufferedFsmCount()).isZero();
        }

        @Test
        void sweep_emptyBuffers_emitsNothing() {
            logger.runCorrelationSweep();

            assertThat(collector.allLines()).isEmpty();
        }
    }

    private static ReconcileIntent provisionIntent() {
        return reconcileIntent(0L, ReconcileTrigger.NTT_FIRE, 3, 5, 2, 0, 0);
    }

    private static FsmDecisionEvent decommissionDecisionFor(NodeId peer) {
        return fsmDecisionEvent(0L, FsmDecisionType.DECOMMISSION, some(peer), "OnDuty", "Stopped", "SwimFaulty");
    }

    /// Captures every line passed to the emit-callback for assertion-driven tests.
    private static final class LineCollector implements Consumer<String> {
        private final List<String> lines = new CopyOnWriteArrayList<>();

        @Override
        @Contract
        public void accept(String line) {
            lines.add(line);
        }

        List<String> allLines() {
            return List.copyOf(lines);
        }

        List<String> linesContaining(String fragment) {
            return lines.stream().filter(line -> line.contains(fragment)).toList();
        }

        String lastLine() {
            return lines.getLast();
        }

        @Contract
        void clear() {
            lines.clear();
        }
    }

    /// Controllable time source — advances only on explicit method calls.
    private static final class TestTimeSource implements TimeSource {
        private volatile long nanos = 0L;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Contract
        void advanceMillis(long millis) {
            nanos += TimeUnit.MILLISECONDS.toNanos(millis);
        }

        @Contract
        void advanceNanos(long delta) {
            nanos += delta;
        }
    }
}
