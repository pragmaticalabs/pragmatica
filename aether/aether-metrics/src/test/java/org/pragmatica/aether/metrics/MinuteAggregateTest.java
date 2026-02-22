package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MinuteAggregateTest {

    @Nested
    class AlignToMinuteTests {
        @Test
        void alignToMinute_exactMinuteBoundary_returnsUnchanged() {
            assertThat(MinuteAggregate.alignToMinute(120_000L)).isEqualTo(120_000L);
        }

        @Test
        void alignToMinute_midMinute_roundsDown() {
            assertThat(MinuteAggregate.alignToMinute(90_000L)).isEqualTo(60_000L);
        }

        @Test
        void alignToMinute_justBeforeMinute_roundsDown() {
            assertThat(MinuteAggregate.alignToMinute(59_999L)).isEqualTo(0L);
        }

        @Test
        void alignToMinute_zero_returnsZero() {
            assertThat(MinuteAggregate.alignToMinute(0L)).isEqualTo(0L);
        }
    }

    @Nested
    class HasDataTests {
        @Test
        void hasData_positiveSampleCount_returnsTrue() {
            var aggregate = MinuteAggregate.minuteAggregate(60_000L, 0.5, 0.5, 1.0, 5.0,
                100, 10, 4.0, 8.0, 12.0, 0.01, 0, 10);

            assertThat(aggregate.hasData()).isTrue();
        }

        @Test
        void hasData_zeroSampleCount_returnsFalse() {
            var aggregate = MinuteAggregate.minuteAggregate(60_000L, 0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0, 0.0, 0.0, 0.0, 0, 0);

            assertThat(aggregate.hasData()).isFalse();
        }
    }

    @Nested
    class HealthyTests {
        @Test
        void healthy_allCriteriaMet_returnsTrue() {
            var aggregate = MinuteAggregate.minuteAggregate(60_000L, 0.5, 0.5, 5.0, 5.0,
                100, 10, 4.0, 8.0, 12.0, 0.05, 0, 10);

            assertThat(aggregate.healthy()).isTrue();
        }

        @Test
        void healthy_highErrorRate_returnsFalse() {
            var aggregate = MinuteAggregate.minuteAggregate(60_000L, 0.5, 0.5, 5.0, 5.0,
                100, 10, 4.0, 8.0, 12.0, 0.1, 0, 10);

            assertThat(aggregate.healthy()).isFalse();
        }

        @Test
        void healthy_highHeapUsage_returnsFalse() {
            var aggregate = MinuteAggregate.minuteAggregate(60_000L, 0.5, 0.9, 5.0, 5.0,
                100, 10, 4.0, 8.0, 12.0, 0.01, 0, 10);

            assertThat(aggregate.healthy()).isFalse();
        }

        @Test
        void healthy_highEventLoopLag_returnsFalse() {
            var aggregate = MinuteAggregate.minuteAggregate(60_000L, 0.5, 0.5, 10.0, 5.0,
                100, 10, 4.0, 8.0, 12.0, 0.01, 0, 10);

            assertThat(aggregate.healthy()).isFalse();
        }
    }

    @Nested
    class ToFeatureArrayTests {
        @Test
        void toFeatureArray_returnsCorrectOrderAndValues() {
            var aggregate = MinuteAggregate.minuteAggregate(60_000L,
                0.5, 0.6, 1.5, 5.0, 200, 15, 4.0, 8.0, 12.0, 0.02, 3, 30);

            float[] features = aggregate.toFeatureArray();

            assertThat(features).hasSize(11);
            assertThat(features[0]).isCloseTo(0.5f, within(0.001f));   // cpu
            assertThat(features[1]).isCloseTo(0.6f, within(0.001f));   // heap
            assertThat(features[2]).isCloseTo(1.5f, within(0.001f));   // eventLoopLag
            assertThat(features[3]).isCloseTo(5.0f, within(0.001f));   // latency
            assertThat(features[4]).isCloseTo(200.0f, within(0.001f)); // invocations
            assertThat(features[5]).isCloseTo(15.0f, within(0.001f));  // gcPause
            assertThat(features[6]).isCloseTo(4.0f, within(0.001f));   // p50
            assertThat(features[7]).isCloseTo(8.0f, within(0.001f));   // p95
            assertThat(features[8]).isCloseTo(12.0f, within(0.001f));  // p99
            assertThat(features[9]).isCloseTo(0.02f, within(0.001f));  // errorRate
            assertThat(features[10]).isCloseTo(3.0f, within(0.001f));  // eventCount
        }

        @Test
        void featureNames_returns11Features() {
            assertThat(MinuteAggregate.featureNames()).hasSize(11);
        }

        @Test
        void featureNames_firstIsCpuUsage() {
            assertThat(MinuteAggregate.featureNames()[0]).isEqualTo("cpu_usage");
        }

        @Test
        void featureNames_lastIsEventCount() {
            assertThat(MinuteAggregate.featureNames()[10]).isEqualTo("event_count");
        }
    }

    @Nested
    class EmptyConstantTests {
        @Test
        void empty_hasZeroTimestamp() {
            assertThat(MinuteAggregate.EMPTY.minuteTimestamp()).isEqualTo(0);
        }

        @Test
        void empty_hasNoData() {
            assertThat(MinuteAggregate.EMPTY.hasData()).isFalse();
        }

        @Test
        void empty_isHealthy() {
            assertThat(MinuteAggregate.EMPTY.healthy()).isTrue();
        }
    }
}
