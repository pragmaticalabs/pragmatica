package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.consensus.RabiaMetrics;
import org.pragmatica.aether.metrics.eventloop.EventLoopMetrics;
import org.pragmatica.aether.metrics.gc.GCMetrics;
import org.pragmatica.aether.metrics.network.NetworkMetrics;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MinuteAggregatorTest {

    private MinuteAggregator aggregator;

    private ComprehensiveSnapshot snapshot(long timestamp, double cpu, double avgLatencyMs) {
        return new ComprehensiveSnapshot(timestamp, cpu, 100L, 1000L,
            GCMetrics.EMPTY, EventLoopMetrics.EMPTY, NetworkMetrics.EMPTY, RabiaMetrics.EMPTY,
            10, 9, 1, avgLatencyMs, Map.of());
    }

    @Nested
    class AddSampleTests {
        @BeforeEach
        void setUp() {
            aggregator = MinuteAggregator.minuteAggregator();
        }

        @Test
        void addSample_singleSample_incrementsCurrentSampleCount() {
            aggregator.addSample(snapshot(1000L, 0.5, 5.0));

            assertThat(aggregator.currentSampleCount()).isEqualTo(1);
        }

        @Test
        void addSample_multipleSamplesInSameMinute_accumulatesInBuffer() {
            aggregator.addSample(snapshot(1000L, 0.5, 5.0));
            aggregator.addSample(snapshot(2000L, 0.6, 6.0));
            aggregator.addSample(snapshot(3000L, 0.7, 7.0));

            assertThat(aggregator.currentSampleCount()).isEqualTo(3);
            assertThat(aggregator.aggregateCount()).isEqualTo(0);
        }

        @Test
        void addSample_crossMinuteBoundary_triggersRollover() {
            // First sample in minute 0
            aggregator.addSample(snapshot(1000L, 0.5, 5.0));
            // Second sample in minute 1 (60000ms = next minute)
            aggregator.addSample(snapshot(60_000L, 0.6, 6.0));

            assertThat(aggregator.aggregateCount()).isEqualTo(1);
            assertThat(aggregator.currentSampleCount()).isEqualTo(1);
        }

        @Test
        void addSample_multipleMinuteRollovers_createsMultipleAggregates() {
            aggregator.addSample(snapshot(1000L, 0.5, 5.0));
            aggregator.addSample(snapshot(60_000L, 0.6, 6.0));
            aggregator.addSample(snapshot(120_000L, 0.7, 7.0));

            assertThat(aggregator.aggregateCount()).isEqualTo(2);
            assertThat(aggregator.currentSampleCount()).isEqualTo(1);
        }
    }

    @Nested
    class FlushTests {
        @BeforeEach
        void setUp() {
            aggregator = MinuteAggregator.minuteAggregator();
        }

        @Test
        void flush_withSamples_finalizesCurrentMinute() {
            aggregator.addSample(snapshot(1000L, 0.5, 5.0));
            aggregator.addSample(snapshot(2000L, 0.6, 6.0));

            aggregator.flush();

            assertThat(aggregator.aggregateCount()).isEqualTo(1);
            assertThat(aggregator.currentSampleCount()).isEqualTo(0);
        }

        @Test
        void flush_withNoSamples_doesNothing() {
            aggregator.flush();

            assertThat(aggregator.aggregateCount()).isEqualTo(0);
        }
    }

    @Nested
    class QueryTests {
        @BeforeEach
        void setUp() {
            aggregator = MinuteAggregator.minuteAggregator();
        }

        @Test
        void all_initially_returnsEmptyList() {
            assertThat(aggregator.all()).isEmpty();
        }

        @Test
        void all_afterFlush_returnsAggregates() {
            aggregator.addSample(snapshot(1000L, 0.5, 5.0));
            aggregator.flush();

            assertThat(aggregator.all()).hasSize(1);
        }

        @Test
        void recent_returnsAtMostNEntries() {
            aggregator.addSample(snapshot(1000L, 0.5, 5.0));
            aggregator.addSample(snapshot(60_000L, 0.6, 6.0));
            aggregator.addSample(snapshot(120_000L, 0.7, 7.0));
            aggregator.flush();

            assertThat(aggregator.recent(2)).hasSize(2);
        }

        @Test
        void recent_fewerThanRequested_returnsAll() {
            aggregator.addSample(snapshot(1000L, 0.5, 5.0));
            aggregator.flush();

            assertThat(aggregator.recent(5)).hasSize(1);
        }

        @Test
        void since_filtersCorrectly() {
            aggregator.addSample(snapshot(1000L, 0.5, 5.0));
            aggregator.addSample(snapshot(60_000L, 0.6, 6.0));
            aggregator.addSample(snapshot(120_000L, 0.7, 7.0));
            aggregator.flush();

            // Minute 0 = aligned to 0, Minute 1 = aligned to 60000, Minute 2 = aligned to 120000
            var result = aggregator.since(60_000L);

            assertThat(result).hasSize(2);
        }
    }

    @Nested
    class TTMInputTests {
        @BeforeEach
        void setUp() {
            aggregator = MinuteAggregator.minuteAggregator();
        }

        @Test
        void toTTMInput_zeroPadding_whenInsufficientData() {
            aggregator.addSample(snapshot(1000L, 0.5, 5.0));
            aggregator.flush();

            float[][] result = aggregator.toTTMInput(5);

            assertThat(result).hasNumberOfRows(5);
            // First 4 rows should be zero-padded
            assertThat(result[0]).containsOnly(0.0f);
            assertThat(result[1]).containsOnly(0.0f);
            assertThat(result[2]).containsOnly(0.0f);
            assertThat(result[3]).containsOnly(0.0f);
            // Last row should have data
            assertThat(result[4]).isNotEqualTo(new float[11]);
        }
    }

    @Nested
    class CapacityEvictionTests {
        @Test
        void capacityEviction_exceedsCapacity_evictsOldest() {
            var smallAggregator = MinuteAggregator.minuteAggregator(3);

            // Add 4 minutes worth of data to exceed capacity of 3
            smallAggregator.addSample(snapshot(1000L, 0.1, 1.0));
            smallAggregator.addSample(snapshot(60_000L, 0.2, 2.0));
            smallAggregator.addSample(snapshot(120_000L, 0.3, 3.0));
            smallAggregator.addSample(snapshot(180_000L, 0.4, 4.0));
            smallAggregator.flush();

            // 4 minute transitions + flush = 4 aggregates, but capacity is 3
            assertThat(smallAggregator.aggregateCount()).isEqualTo(3);
            // Verify oldest was evicted - first aggregate should be from minute 60000
            var all = smallAggregator.all();
            assertThat(all.getFirst().minuteTimestamp()).isEqualTo(60_000L);
        }
    }
}
