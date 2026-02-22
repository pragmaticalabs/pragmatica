package org.pragmatica.aether.metrics.invocation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.MethodName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MethodMetricsTest {

    private MethodMetrics metrics;
    private MethodName methodName;

    @BeforeEach
    void setUp() {
        methodName = MethodName.methodName("testMethod").unwrap();
        metrics = new MethodMetrics(methodName);
    }

    @Nested
    class RecordTests {
        @Test
        void record_success_incrementsCountAndSuccessCount() {
            metrics.record(500_000L, true);

            var snapshot = metrics.snapshot();

            assertThat(snapshot.count()).isEqualTo(1);
            assertThat(snapshot.successCount()).isEqualTo(1);
            assertThat(snapshot.failureCount()).isEqualTo(0);
        }

        @Test
        void record_failure_incrementsCountAndFailureCount() {
            metrics.record(500_000L, false);

            var snapshot = metrics.snapshot();

            assertThat(snapshot.count()).isEqualTo(1);
            assertThat(snapshot.successCount()).isEqualTo(0);
            assertThat(snapshot.failureCount()).isEqualTo(1);
        }

        @Test
        void record_multipleInvocations_accumulatesDuration() {
            metrics.record(1_000_000L, true);
            metrics.record(2_000_000L, true);

            assertThat(metrics.totalDurationNs()).isEqualTo(3_000_000L);
            assertThat(metrics.count()).isEqualTo(2);
        }
    }

    @Nested
    class HistogramTests {
        @Test
        void record_lessThan1ms_goesToBucket0() {
            metrics.record(500_000L, true); // 0.5ms

            var snapshot = metrics.snapshot();

            assertThat(snapshot.histogram()[0]).isEqualTo(1);
        }

        @Test
        void record_1to10ms_goesToBucket1() {
            metrics.record(5_000_000L, true); // 5ms

            var snapshot = metrics.snapshot();

            assertThat(snapshot.histogram()[1]).isEqualTo(1);
        }

        @Test
        void record_10to100ms_goesToBucket2() {
            metrics.record(50_000_000L, true); // 50ms

            var snapshot = metrics.snapshot();

            assertThat(snapshot.histogram()[2]).isEqualTo(1);
        }

        @Test
        void record_100to1000ms_goesToBucket3() {
            metrics.record(500_000_000L, true); // 500ms

            var snapshot = metrics.snapshot();

            assertThat(snapshot.histogram()[3]).isEqualTo(1);
        }

        @Test
        void record_greaterThan1s_goesToBucket4() {
            metrics.record(2_000_000_000L, true); // 2s

            var snapshot = metrics.snapshot();

            assertThat(snapshot.histogram()[4]).isEqualTo(1);
        }
    }

    @Nested
    class SnapshotAndResetTests {
        @Test
        void snapshotAndReset_resetsCounters() {
            metrics.record(1_000_000L, true);
            metrics.record(2_000_000L, false);

            var snapshot = metrics.snapshotAndReset();

            assertThat(snapshot.count()).isEqualTo(2);
            assertThat(snapshot.successCount()).isEqualTo(1);
            assertThat(snapshot.failureCount()).isEqualTo(1);

            // After reset, counters should be zero
            assertThat(metrics.count()).isEqualTo(0);
            assertThat(metrics.totalDurationNs()).isEqualTo(0);
        }

        @Test
        void snapshot_doesNotResetCounters() {
            metrics.record(1_000_000L, true);

            metrics.snapshot();

            assertThat(metrics.count()).isEqualTo(1);
            assertThat(metrics.totalDurationNs()).isEqualTo(1_000_000L);
        }
    }

    @Nested
    class ActiveInvocationTests {
        @Test
        void activeInvocations_initiallyZero() {
            assertThat(metrics.activeInvocations()).isEqualTo(0);
        }

        @Test
        void activeInvocations_afterStart_incrementsToOne() {
            metrics.recordStart();

            assertThat(metrics.activeInvocations()).isEqualTo(1);
        }

        @Test
        void activeInvocations_afterStartAndComplete_returnsZero() {
            metrics.recordStart();
            metrics.recordComplete();

            assertThat(metrics.activeInvocations()).isEqualTo(0);
        }

        @Test
        void activeInvocations_multipleConcurrent_tracksCorrectly() {
            metrics.recordStart();
            metrics.recordStart();
            metrics.recordStart();
            metrics.recordComplete();

            assertThat(metrics.activeInvocations()).isEqualTo(2);
        }
    }

    @Nested
    class SnapshotCalculationTests {
        @Test
        void averageLatencyNs_withRecords_calculatesCorrectly() {
            metrics.record(1_000_000L, true);
            metrics.record(3_000_000L, true);

            var snapshot = metrics.snapshot();

            // (1_000_000 + 3_000_000) / 2 = 2_000_000
            assertThat(snapshot.averageLatencyNs()).isEqualTo(2_000_000L);
        }

        @Test
        void averageLatencyNs_noRecords_returnsZero() {
            var snapshot = metrics.snapshot();

            assertThat(snapshot.averageLatencyNs()).isEqualTo(0L);
        }

        @Test
        void successRate_allSuccessful_returnsOne() {
            metrics.record(1_000_000L, true);
            metrics.record(2_000_000L, true);

            var snapshot = metrics.snapshot();

            assertThat(snapshot.successRate()).isCloseTo(1.0, within(0.001));
        }

        @Test
        void successRate_noRecords_returnsOne() {
            var snapshot = metrics.snapshot();

            assertThat(snapshot.successRate()).isEqualTo(1.0);
        }

        @Test
        void successRate_halfFailed_returnsHalf() {
            metrics.record(1_000_000L, true);
            metrics.record(2_000_000L, false);

            var snapshot = metrics.snapshot();

            assertThat(snapshot.successRate()).isCloseTo(0.5, within(0.001));
        }

        @Test
        void percentile_withSamples_returnsValue() {
            metrics.record(1_000_000L, true);
            metrics.record(5_000_000L, true);
            metrics.record(10_000_000L, true);

            var snapshot = metrics.snapshot();

            assertThat(snapshot.p50()).isGreaterThan(0);
            assertThat(snapshot.p99()).isGreaterThanOrEqualTo(snapshot.p50());
        }

        @Test
        void percentile_noSamples_returnsZero() {
            var snapshot = metrics.snapshot();

            assertThat(snapshot.percentile(0.95)).isEqualTo(0);
        }

        @Test
        void estimatePercentileNs_fromHistogram_returnsReasonableValue() {
            // 10 records < 1ms
            for (int i = 0; i < 10; i++) {
                metrics.record(500_000L, true);
            }

            var snapshot = metrics.snapshot();

            // p50 from histogram should give bucket 0 upper bound (1ms = 1_000_000ns)
            assertThat(snapshot.estimatePercentileNs(50)).isEqualTo(1_000_000L);
        }

        @Test
        void estimatePercentileNs_noRecords_returnsZero() {
            var snapshot = metrics.snapshot();

            assertThat(snapshot.estimatePercentileNs(95)).isEqualTo(0);
        }
    }
}
