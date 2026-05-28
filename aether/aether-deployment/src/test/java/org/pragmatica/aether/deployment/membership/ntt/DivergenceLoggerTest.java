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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.ntt.DivergenceLogger.divergenceLogger;
import static org.pragmatica.aether.deployment.membership.ntt.FsmDecisionEvent.fsmDecisionEvent;
import static org.pragmatica.aether.deployment.membership.ntt.ReconcileIntent.reconcileIntent;
import static org.pragmatica.lang.Option.some;


/// Unit tests for [`DivergenceLogger`] — Stage 5 / E1 observation-only correlation.
/// Asserts on the structured log strings produced by the injected emit-callback rather
/// than intercepting SLF4J.
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
        void observeNttIntent_logsStructuredLine_andBuffers() {
            logger.observeNttIntent(provisionIntentFor(PEER_A));

            assertThat(logger.bufferedNttCount()).isEqualTo(1);
            assertThat(logger.bufferedFsmCount()).isZero();
            assertThat(collector.linesContaining("source=NTT")).hasSize(1);
            assertThat(collector.lastLine()).contains("[v2-divergence]")
                                            .contains("source=NTT")
                                            .contains("peer=" + PEER_A.id())
                                            .contains("trigger=NTT_DRAIN")
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
        void sweep_bothBufferedSamePeerWithinWindow_emitsAligned_andClearsBuffers() {
            logger.observeNttIntent(provisionIntentFor(PEER_A));
            timeSource.advanceMillis(500L);
            logger.observeFsmDecision(provisionDecisionFor(PEER_A));

            collector.clear();
            logger.runCorrelationSweep();

            assertThat(collector.linesContaining("verdict=ALIGNED")).hasSize(1);
            assertThat(collector.lastLine()).contains("peer=" + PEER_A.id())
                                            .contains("delta_ms=500")
                                            .contains("fsm_type=PROVISION");
            assertThat(logger.bufferedNttCount()).isZero();
            assertThat(logger.bufferedFsmCount()).isZero();
        }

        @Test
        void sweep_onlyNttBufferedPastWindow_emitsDivergentNttOnly_andDropsThatSide() {
            logger.observeNttIntent(provisionIntentFor(PEER_A));
            timeSource.advanceNanos(SECONDS_31);

            collector.clear();
            logger.runCorrelationSweep();

            assertThat(collector.linesContaining("verdict=DIVERGENT")).hasSize(1);
            assertThat(collector.lastLine()).contains("peer=" + PEER_A.id())
                                            .contains("side=NTT_ONLY")
                                            .contains("buffered_for_ms=31000")
                                            .contains("trigger=NTT_DRAIN");
            assertThat(logger.bufferedNttCount()).isZero();
            assertThat(logger.bufferedFsmCount()).isZero();
        }

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
            assertThat(logger.bufferedNttCount()).isZero();
            assertThat(logger.bufferedFsmCount()).isZero();
        }

        @Test
        void sweep_alignedThenSweepAgain_emitsNothingSecondTime() {
            logger.observeNttIntent(provisionIntentFor(PEER_A));
            logger.observeFsmDecision(provisionDecisionFor(PEER_A));

            logger.runCorrelationSweep();
            collector.clear();
            logger.runCorrelationSweep();

            assertThat(collector.allLines()).isEmpty();
            assertThat(logger.bufferedNttCount()).isZero();
            assertThat(logger.bufferedFsmCount()).isZero();
        }

        @Test
        void sweep_withinWindow_bufferedOnlyOnOneSide_emitsNothing_keepsBuffer() {
            logger.observeNttIntent(provisionIntentFor(PEER_A));
            timeSource.advanceMillis(1_000L);

            collector.clear();
            logger.runCorrelationSweep();

            assertThat(collector.allLines()).isEmpty();
            assertThat(logger.bufferedNttCount()).isEqualTo(1);
        }

        @Test
        void sweep_twoPeers_independentlyEvaluated() {
            logger.observeNttIntent(provisionIntentFor(PEER_A));
            logger.observeFsmDecision(provisionDecisionFor(PEER_A));
            logger.observeNttIntent(provisionIntentFor(PEER_B));
            timeSource.advanceNanos(SECONDS_31);

            collector.clear();
            logger.runCorrelationSweep();

            assertThat(collector.linesContaining("verdict=ALIGNED")).hasSize(1);
            assertThat(collector.linesContaining("peer=" + PEER_A.id())).anyMatch(line -> line.contains("ALIGNED"));
            assertThat(collector.linesContaining("verdict=DIVERGENT")).hasSize(1);
            assertThat(collector.linesContaining("peer=" + PEER_B.id())).anyMatch(line -> line.contains("DIVERGENT")
                                                                                          && line.contains("side=NTT_ONLY"));
            assertThat(logger.bufferedNttCount()).isZero();
            assertThat(logger.bufferedFsmCount()).isZero();
        }
    }

    private static ReconcileIntent provisionIntentFor(NodeId peer) {
        return reconcileIntent(0L, ReconcileTrigger.NTT_DRAIN, 3, 5, Set.of(peer), Set.of(), 0);
    }

    private static FsmDecisionEvent decommissionDecisionFor(NodeId peer) {
        return fsmDecisionEvent(0L, FsmDecisionType.DECOMMISSION, some(peer), "OnDuty", "Stopped", "SwimFaulty");
    }

    private static FsmDecisionEvent provisionDecisionFor(NodeId peer) {
        return fsmDecisionEvent(0L, FsmDecisionType.PROVISION, some(peer), "Joining", "OnDuty", "SwimHealthy");
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

    /// Controllable time source — advances only on explicit method calls. Matches the
    /// shape used by [`LeaderReconcilerTest`].
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
