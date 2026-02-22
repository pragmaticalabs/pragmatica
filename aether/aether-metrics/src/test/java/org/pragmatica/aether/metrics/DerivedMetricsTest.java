package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DerivedMetricsTest {

    @Nested
    class HealthScoreTests {
        @Test
        void healthScore_perfectValues_returnsOne() {
            var metrics = new DerivedMetrics(100.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0);

            assertThat(metrics.healthScore()).isCloseTo(1.0, within(0.001));
        }

        @Test
        void healthScore_oneSecondP99_latencyComponentIsZero() {
            var metrics = new DerivedMetrics(100.0, 0.0, 0.0,
                0.0, 0.0, 1000.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0);

            // latencyScore = max(0, 1 - 1000/1000) = 0 -> 0 * 0.3 = 0
            // eventLoop = 1.0 * 0.3 = 0.3
            // heap = 1.0 * 0.2 = 0.2
            // error = 1.0 * 0.2 = 0.2
            // total = 0.7
            assertThat(metrics.healthScore()).isCloseTo(0.7, within(0.001));
        }

        @Test
        void healthScore_fullSaturation_returnsZero() {
            var metrics = new DerivedMetrics(0.0, 0.1, 0.0,
                0.0, 0.0, 1000.0,
                1.0, 1.0, 0.0,
                0.0, 0.0, 0.0);

            // latency = 0 * 0.3, eventLoop = 0 * 0.3, heap = 0 * 0.2, error = max(0, 1-0.1*10) = 0 * 0.2
            assertThat(metrics.healthScore()).isCloseTo(0.0, within(0.001));
        }

        @Test
        void healthScore_halfSaturation_returnsExpected() {
            var metrics = new DerivedMetrics(0.0, 0.0, 0.0,
                0.0, 0.0, 500.0,
                0.5, 0.5, 0.0,
                0.0, 0.0, 0.0);

            // latency = max(0, 1 - 500/1000) = 0.5 * 0.3 = 0.15
            // eventLoop = (1 - 0.5) = 0.5 * 0.3 = 0.15
            // heap = (1 - 0.5) = 0.5 * 0.2 = 0.10
            // error = 1.0 * 0.2 = 0.2
            // total = 0.6
            assertThat(metrics.healthScore()).isCloseTo(0.6, within(0.001));
        }
    }

    @Nested
    class StressedTests {
        @Test
        void stressed_highEventLoopSaturation_returnsTrue() {
            var metrics = new DerivedMetrics(0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.71, 0.0, 0.0,
                0.0, 0.0, 0.0);

            assertThat(metrics.stressed()).isTrue();
        }

        @Test
        void stressed_highHeapSaturation_returnsTrue() {
            var metrics = new DerivedMetrics(0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.81, 0.0,
                0.0, 0.0, 0.0);

            assertThat(metrics.stressed()).isTrue();
        }

        @Test
        void stressed_highErrorRate_returnsTrue() {
            var metrics = new DerivedMetrics(0.0, 0.051, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0);

            assertThat(metrics.stressed()).isTrue();
        }

        @Test
        void stressed_allBelowThresholds_returnsFalse() {
            var metrics = new DerivedMetrics(0.0, 0.01, 0.0,
                0.0, 0.0, 0.0,
                0.5, 0.5, 0.0,
                0.0, 0.0, 0.0);

            assertThat(metrics.stressed()).isFalse();
        }
    }

    @Nested
    class HasCapacityTests {
        @Test
        void hasCapacity_allBelowThresholds_returnsTrue() {
            var metrics = new DerivedMetrics(0.0, 0.005, 0.0,
                0.0, 0.0, 0.0,
                0.3, 0.4, 0.0,
                0.0, 0.0, 0.0);

            assertThat(metrics.hasCapacity()).isTrue();
        }

        @Test
        void hasCapacity_highEventLoop_returnsFalse() {
            var metrics = new DerivedMetrics(0.0, 0.005, 0.0,
                0.0, 0.0, 0.0,
                0.6, 0.4, 0.0,
                0.0, 0.0, 0.0);

            assertThat(metrics.hasCapacity()).isFalse();
        }
    }

    @Nested
    class TrendTests {
        @Test
        void deteriorating_positiveTrends_returnsTrue() {
            var metrics = new DerivedMetrics(0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.2, 60.0, 0.02);

            assertThat(metrics.deteriorating()).isTrue();
        }

        @Test
        void deteriorating_stableTrends_returnsFalse() {
            var metrics = new DerivedMetrics(0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0);

            assertThat(metrics.deteriorating()).isFalse();
        }

        @Test
        void improving_negativeTrends_returnsTrue() {
            var metrics = new DerivedMetrics(0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                -0.1, -20.0, -0.01);

            assertThat(metrics.improving()).isTrue();
        }

        @Test
        void improving_positiveTrends_returnsFalse() {
            var metrics = new DerivedMetrics(0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.1, 20.0, 0.01);

            assertThat(metrics.improving()).isFalse();
        }
    }

    @Nested
    class EmptyConstantTests {
        @Test
        void empty_allFieldsZero() {
            assertThat(DerivedMetrics.EMPTY.requestRate()).isEqualTo(0.0);
            assertThat(DerivedMetrics.EMPTY.errorRate()).isEqualTo(0.0);
            assertThat(DerivedMetrics.EMPTY.gcRate()).isEqualTo(0.0);
            assertThat(DerivedMetrics.EMPTY.latencyP50()).isEqualTo(0.0);
            assertThat(DerivedMetrics.EMPTY.latencyP95()).isEqualTo(0.0);
            assertThat(DerivedMetrics.EMPTY.latencyP99()).isEqualTo(0.0);
            assertThat(DerivedMetrics.EMPTY.eventLoopSaturation()).isEqualTo(0.0);
            assertThat(DerivedMetrics.EMPTY.heapSaturation()).isEqualTo(0.0);
            assertThat(DerivedMetrics.EMPTY.backpressureRate()).isEqualTo(0.0);
            assertThat(DerivedMetrics.EMPTY.cpuTrend()).isEqualTo(0.0);
            assertThat(DerivedMetrics.EMPTY.latencyTrend()).isEqualTo(0.0);
            assertThat(DerivedMetrics.EMPTY.errorTrend()).isEqualTo(0.0);
        }
    }
}
