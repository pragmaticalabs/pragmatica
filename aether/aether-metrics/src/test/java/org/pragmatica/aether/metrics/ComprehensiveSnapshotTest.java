package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.consensus.RabiaMetrics;
import org.pragmatica.aether.metrics.eventloop.EventLoopMetrics;
import org.pragmatica.aether.metrics.gc.GCMetrics;
import org.pragmatica.aether.metrics.network.NetworkMetrics;
import org.pragmatica.lang.Option;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ComprehensiveSnapshotTest {

    private ComprehensiveSnapshot snapshot(long timestamp, double cpu, long heapUsed, long heapMax,
                                           long totalInvocations, long successfulInvocations,
                                           long failedInvocations, double avgLatencyMs) {
        return new ComprehensiveSnapshot(timestamp, cpu, heapUsed, heapMax,
            GCMetrics.EMPTY, EventLoopMetrics.EMPTY, NetworkMetrics.EMPTY, RabiaMetrics.EMPTY,
            totalInvocations, successfulInvocations, failedInvocations, avgLatencyMs, Map.of());
    }

    @Nested
    class HeapUsageTests {
        @Test
        void heapUsage_normalValues_calculatesRatioCorrectly() {
            var snap = snapshot(1000L, 0.5, 500L, 1000L, 0, 0, 0, 0.0);

            assertThat(snap.heapUsage()).isCloseTo(0.5, within(0.001));
        }

        @Test
        void heapUsage_zeroHeapMax_returnsZero() {
            var snap = snapshot(1000L, 0.5, 500L, 0L, 0, 0, 0, 0.0);

            assertThat(snap.heapUsage()).isEqualTo(0.0);
        }

        @Test
        void heapUsage_negativeHeapMax_returnsZero() {
            var snap = snapshot(1000L, 0.5, 500L, -1L, 0, 0, 0, 0.0);

            assertThat(snap.heapUsage()).isEqualTo(0.0);
        }

        @Test
        void heapUsage_fullHeap_returnsOne() {
            var snap = snapshot(1000L, 0.5, 1000L, 1000L, 0, 0, 0, 0.0);

            assertThat(snap.heapUsage()).isCloseTo(1.0, within(0.001));
        }
    }

    @Nested
    class SuccessRateTests {
        @Test
        void successRate_normalValues_calculatesCorrectly() {
            var snap = snapshot(1000L, 0.5, 100L, 1000L, 100, 90, 10, 5.0);

            assertThat(snap.successRate()).isCloseTo(0.9, within(0.001));
        }

        @Test
        void successRate_zeroInvocations_returnsOne() {
            var snap = snapshot(1000L, 0.5, 100L, 1000L, 0, 0, 0, 0.0);

            assertThat(snap.successRate()).isEqualTo(1.0);
        }

        @Test
        void successRate_allSuccessful_returnsOne() {
            var snap = snapshot(1000L, 0.5, 100L, 1000L, 50, 50, 0, 1.0);

            assertThat(snap.successRate()).isCloseTo(1.0, within(0.001));
        }
    }

    @Nested
    class ErrorRateTests {
        @Test
        void errorRate_normalValues_calculatesCorrectly() {
            var snap = snapshot(1000L, 0.5, 100L, 1000L, 100, 80, 20, 5.0);

            assertThat(snap.errorRate()).isCloseTo(0.2, within(0.001));
        }

        @Test
        void errorRate_zeroInvocations_returnsZero() {
            var snap = snapshot(1000L, 0.5, 100L, 1000L, 0, 0, 0, 0.0);

            assertThat(snap.errorRate()).isEqualTo(0.0);
        }

        @Test
        void errorRate_noErrors_returnsZero() {
            var snap = snapshot(1000L, 0.5, 100L, 1000L, 50, 50, 0, 1.0);

            assertThat(snap.errorRate()).isCloseTo(0.0, within(0.001));
        }
    }

    @Nested
    class HealthyTests {
        @Test
        void healthy_allCriteriaMet_returnsTrue() {
            var snap = new ComprehensiveSnapshot(1000L, 0.5, 100L, 1000L,
                GCMetrics.EMPTY,
                new EventLoopMetrics(0, 0, 0, true),
                NetworkMetrics.EMPTY,
                new RabiaMetrics("LEADER", Option.some("node-1"), 0, 10, 10, 5, 0, 100_000L),
                100, 99, 1, 5.0, Map.of());

            assertThat(snap.healthy()).isTrue();
        }

        @Test
        void healthy_highErrorRate_returnsFalse() {
            var snap = new ComprehensiveSnapshot(1000L, 0.5, 100L, 1000L,
                GCMetrics.EMPTY,
                new EventLoopMetrics(0, 0, 0, true),
                NetworkMetrics.EMPTY,
                new RabiaMetrics("LEADER", Option.some("node-1"), 0, 10, 10, 5, 0, 100_000L),
                100, 89, 11, 5.0, Map.of());

            assertThat(snap.healthy()).isFalse();
        }

        @Test
        void healthy_highHeapUsage_returnsFalse() {
            var snap = new ComprehensiveSnapshot(1000L, 0.5, 950L, 1000L,
                GCMetrics.EMPTY,
                new EventLoopMetrics(0, 0, 0, true),
                NetworkMetrics.EMPTY,
                new RabiaMetrics("LEADER", Option.some("node-1"), 0, 10, 10, 5, 0, 100_000L),
                100, 100, 0, 5.0, Map.of());

            assertThat(snap.healthy()).isFalse();
        }
    }

    @Nested
    class EmptyConstantTests {
        @Test
        void empty_hasZeroTimestamp() {
            assertThat(ComprehensiveSnapshot.EMPTY.timestamp()).isEqualTo(0);
        }

        @Test
        void empty_hasZeroCpuUsage() {
            assertThat(ComprehensiveSnapshot.EMPTY.cpuUsage()).isEqualTo(0.0);
        }

        @Test
        void empty_hasEmptyCustomMetrics() {
            assertThat(ComprehensiveSnapshot.EMPTY.custom()).isEmpty();
        }
    }
}
