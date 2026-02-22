package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.metrics.invocation.ThresholdStrategy;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

class InvocationMetricsCollectorTest {

    private InvocationMetricsCollector collector;
    private Artifact artifact;
    private MethodName method;

    @BeforeEach
    void setUp() {
        collector = InvocationMetricsCollector.invocationMetricsCollector(ThresholdStrategy.fixed(100));
        artifact = Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
        method = MethodName.methodName("processRequest").unwrap();
    }

    @Nested
    class RecordingMetrics {
        @Test
        void record_singleSuccess_capturedInSnapshot() {
            collector.recordSuccess(artifact, method, 50_000_000L, 100, 200);

            var snapshots = collector.snapshot();

            assertThat(snapshots).hasSize(1);
            var snapshot = snapshots.getFirst();
            assertThat(snapshot.artifact()).isEqualTo(artifact);
            assertThat(snapshot.metrics().count()).isEqualTo(1);
            assertThat(snapshot.metrics().successCount()).isEqualTo(1);
            assertThat(snapshot.metrics().failureCount()).isZero();
        }

        @Test
        void record_singleFailure_capturedInSnapshot() {
            collector.recordFailure(artifact, method, 75_000_000L, 100, "TimeoutError");

            var snapshots = collector.snapshot();

            assertThat(snapshots).hasSize(1);
            assertThat(snapshots.getFirst().metrics().failureCount()).isEqualTo(1);
            assertThat(snapshots.getFirst().metrics().successCount()).isZero();
        }

        @Test
        void record_multipleInvocations_accumulates() {
            collector.recordSuccess(artifact, method, 10_000_000L, 50, 100);
            collector.recordSuccess(artifact, method, 20_000_000L, 50, 100);
            collector.recordFailure(artifact, method, 30_000_000L, 50, "Error");

            var snapshots = collector.snapshot();

            assertThat(snapshots).hasSize(1);
            var metrics = snapshots.getFirst().metrics();
            assertThat(metrics.count()).isEqualTo(3);
            assertThat(metrics.successCount()).isEqualTo(2);
            assertThat(metrics.failureCount()).isEqualTo(1);
            assertThat(metrics.totalDurationNs()).isEqualTo(60_000_000L);
        }

        @Test
        void record_multipleArtifacts_separateSnapshots() {
            var artifact2 = Artifact.artifact("org.example:other-slice:2.0.0").unwrap();

            collector.recordSuccess(artifact, method, 10_000_000L, 50, 100);
            collector.recordSuccess(artifact2, method, 20_000_000L, 50, 100);

            var snapshots = collector.snapshot();

            assertThat(snapshots).hasSize(2);
        }
    }

    @Nested
    class SnapshotAndReset {
        @Test
        void snapshotAndReset_returnsCurrentMetrics() {
            collector.recordSuccess(artifact, method, 10_000_000L, 50, 100);

            var snapshots = collector.snapshotAndReset();

            assertThat(snapshots).hasSize(1);
            assertThat(snapshots.getFirst().metrics().count()).isEqualTo(1);
        }

        @Test
        void snapshotAndReset_subsequentSnapshotHasZeroCounts() {
            collector.recordSuccess(artifact, method, 10_000_000L, 50, 100);

            collector.snapshotAndReset();
            var afterReset = collector.snapshot();

            assertThat(afterReset).hasSize(1);
            assertThat(afterReset.getFirst().metrics().count()).isZero();
        }

        @Test
        void snapshot_doesNotResetCounters() {
            collector.recordSuccess(artifact, method, 10_000_000L, 50, 100);

            collector.snapshot();
            var second = collector.snapshot();

            assertThat(second.getFirst().metrics().count()).isEqualTo(1);
        }
    }

    @Nested
    class ActiveInvocationTracking {
        @Test
        void totalActiveInvocations_noInvocations_returnsZero() {
            assertThat(collector.totalActiveInvocations()).isZero();
        }

        @Test
        void totalActiveInvocations_startWithoutComplete_countsAsActive() {
            collector.recordStart(artifact, method);

            assertThat(collector.totalActiveInvocations()).isEqualTo(1);
        }

        @Test
        void totalActiveInvocations_startAndComplete_returnsZero() {
            collector.recordStart(artifact, method);
            collector.recordComplete(artifact, method);

            assertThat(collector.totalActiveInvocations()).isZero();
        }

        @Test
        void totalActiveInvocations_multipleStartsOneComplete_returnsCorrectCount() {
            collector.recordStart(artifact, method);
            collector.recordStart(artifact, method);
            collector.recordStart(artifact, method);
            collector.recordComplete(artifact, method);

            assertThat(collector.totalActiveInvocations()).isEqualTo(2);
        }
    }

    @Nested
    class SerializationTracking {
        @Test
        void averageSerializationNs_noRecords_returnsZero() {
            assertThat(collector.averageSerializationNs()).isZero();
        }

        @Test
        void averageSerializationNs_singleRecord_returnsThatValue() {
            collector.recordSerialization(5_000_000L);

            assertThat(collector.averageSerializationNs()).isEqualTo(5_000_000L);
        }

        @Test
        void averageSerializationNs_multipleRecords_returnsAverage() {
            collector.recordSerialization(10_000_000L);
            collector.recordSerialization(20_000_000L);

            assertThat(collector.averageSerializationNs()).isEqualTo(15_000_000L);
        }
    }

    @Nested
    class ThresholdBehavior {
        @Test
        void thresholdNsFor_fixedStrategy_returnsSameForAllMethods() {
            var method2 = MethodName.methodName("otherMethod").unwrap();

            assertThat(collector.thresholdNsFor(method)).isEqualTo(collector.thresholdNsFor(method2));
        }

        @Test
        void thresholdStrategy_returnsConfiguredStrategy() {
            assertThat(collector.thresholdStrategy()).isInstanceOf(ThresholdStrategy.Fixed.class);
        }

        @Test
        void setThresholdStrategy_isNotSupported() {
            var result = collector.setThresholdStrategy(ThresholdStrategy.fixed(200));

            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class SlowInvocationCapture {
        @Test
        void record_belowThreshold_noSlowInvocationsCaptured() {
            // Fixed threshold is 100ms = 100_000_000ns; record 50ms
            collector.record(artifact, method, 50_000_000L, true, 100, 200, Option.empty());

            var snapshots = collector.snapshot();

            assertThat(snapshots.getFirst().slowInvocations()).isEmpty();
        }

        @Test
        void record_aboveThreshold_capturedAsSlow() {
            // Fixed threshold is 100ms = 100_000_000ns; record 150ms
            collector.record(artifact, method, 150_000_000L, true, 100, 200, Option.empty());

            var snapshots = collector.snapshot();

            assertThat(snapshots.getFirst().slowInvocations()).hasSize(1);
            var slow = snapshots.getFirst().slowInvocations().getFirst();
            assertThat(slow.durationNs()).isEqualTo(150_000_000L);
            assertThat(slow.success()).isTrue();
        }

        @Test
        void snapshotAndReset_drainsSlowInvocations() {
            collector.record(artifact, method, 150_000_000L, true, 100, 200, Option.empty());

            var first = collector.snapshotAndReset();
            assertThat(first.getFirst().slowInvocations()).hasSize(1);

            // Record another slow call
            collector.record(artifact, method, 200_000_000L, true, 100, 200, Option.empty());

            var second = collector.snapshotAndReset();
            assertThat(second.getFirst().slowInvocations()).hasSize(1);
            assertThat(second.getFirst().slowInvocations().getFirst().durationNs()).isEqualTo(200_000_000L);
        }
    }

    @Nested
    class SnapshotMetrics {
        @Test
        void snapshot_averageLatencyNs_calculatedCorrectly() {
            collector.recordSuccess(artifact, method, 10_000_000L, 50, 100);
            collector.recordSuccess(artifact, method, 30_000_000L, 50, 100);

            var snapshot = collector.snapshot().getFirst().metrics();

            assertThat(snapshot.averageLatencyNs()).isEqualTo(20_000_000L);
        }

        @Test
        void snapshot_successRate_calculatedCorrectly() {
            collector.recordSuccess(artifact, method, 10_000_000L, 50, 100);
            collector.recordSuccess(artifact, method, 10_000_000L, 50, 100);
            collector.recordFailure(artifact, method, 10_000_000L, 50, "Error");

            var snapshot = collector.snapshot().getFirst().metrics();

            assertThat(snapshot.successRate()).isCloseTo(0.6667, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        void snapshot_noInvocations_successRateIsOne() {
            // Create metrics entry via recordStart but no actual invocations
            collector.recordStart(artifact, method);
            collector.recordComplete(artifact, method);

            var snapshots = collector.snapshot();

            assertThat(snapshots).hasSize(1);
            assertThat(snapshots.getFirst().metrics().count()).isZero();
            assertThat(snapshots.getFirst().metrics().successRate()).isEqualTo(1.0);
        }

        @Test
        void snapshot_histogram_distributesCorrectly() {
            // < 1ms bucket
            collector.recordSuccess(artifact, method, 500_000L, 50, 100);
            // 1ms-10ms bucket
            collector.recordSuccess(artifact, method, 5_000_000L, 50, 100);
            // 10ms-100ms bucket
            collector.recordSuccess(artifact, method, 50_000_000L, 50, 100);

            var histogram = collector.snapshot().getFirst().metrics().histogram();

            assertThat(histogram[0]).isEqualTo(1); // < 1ms
            assertThat(histogram[1]).isEqualTo(1); // 1ms-10ms
            assertThat(histogram[2]).isEqualTo(1); // 10ms-100ms
            assertThat(histogram[3]).isZero();      // 100ms-1s
            assertThat(histogram[4]).isZero();      // >= 1s
        }
    }

    @Nested
    class DefaultFactory {
        @Test
        void invocationMetricsCollector_defaultFactory_usesAdaptiveStrategy() {
            var defaultCollector = InvocationMetricsCollector.invocationMetricsCollector();

            assertThat(defaultCollector.thresholdStrategy()).isInstanceOf(ThresholdStrategy.Adaptive.class);
        }
    }
}
