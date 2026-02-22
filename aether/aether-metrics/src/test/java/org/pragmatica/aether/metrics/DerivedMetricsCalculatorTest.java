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
import static org.assertj.core.api.Assertions.within;

class DerivedMetricsCalculatorTest {

    private DerivedMetricsCalculator calculator;

    private ComprehensiveSnapshot snapshot(long timestamp, double cpu, long heapUsed, long heapMax,
                                           long totalInvocations, long failedInvocations,
                                           double avgLatencyMs) {
        return new ComprehensiveSnapshot(timestamp, cpu, heapUsed, heapMax,
            GCMetrics.EMPTY, EventLoopMetrics.EMPTY, NetworkMetrics.EMPTY, RabiaMetrics.EMPTY,
            totalInvocations, totalInvocations - failedInvocations, failedInvocations,
            avgLatencyMs, Map.of());
    }

    @Nested
    class InitialStateTests {
        @BeforeEach
        void setUp() {
            calculator = DerivedMetricsCalculator.derivedMetricsCalculator();
        }

        @Test
        void current_initially_returnsEmpty() {
            assertThat(calculator.current()).isEqualTo(DerivedMetrics.EMPTY);
        }
    }

    @Nested
    class AddSampleTests {
        @BeforeEach
        void setUp() {
            calculator = DerivedMetricsCalculator.derivedMetricsCalculator();
        }

        @Test
        void addSample_singleSample_updatesCurrent() {
            calculator.addSample(snapshot(1000L, 0.5, 500, 1000, 100, 5, 10.0));

            var current = calculator.current();

            assertThat(current).isNotEqualTo(DerivedMetrics.EMPTY);
            assertThat(current.heapSaturation()).isCloseTo(0.5, within(0.001));
        }

        @Test
        void addSample_multipleSamples_updatesRates() {
            calculator.addSample(snapshot(1000L, 0.3, 500, 1000, 50, 2, 5.0));
            calculator.addSample(snapshot(2000L, 0.5, 600, 1000, 80, 4, 8.0));

            var current = calculator.current();

            // requestRate = totalInvocations / windowSeconds
            // windowSeconds = max(1.0, (2000-1000)/1000.0) = 1.0
            // totalInvocations = 50 + 80 = 130
            assertThat(current.requestRate()).isCloseTo(130.0, within(0.1));
        }
    }

    @Nested
    class PercentileTests {
        @BeforeEach
        void setUp() {
            calculator = DerivedMetricsCalculator.derivedMetricsCalculator();
        }

        @Test
        void percentiles_calculatedFromSamples() {
            // Add samples with varying latencies
            for (int i = 0; i < 10; i++) {
                calculator.addSample(snapshot(1000L + i * 1000L, 0.5, 500, 1000,
                    100, 5, (i + 1) * 10.0));
            }

            var current = calculator.current();

            // With 10 samples latencies: 10, 20, 30, ..., 100
            // p50 should be around 50, p95 near 100, p99 near 100
            assertThat(current.latencyP50()).isGreaterThan(0.0);
            assertThat(current.latencyP95()).isGreaterThanOrEqualTo(current.latencyP50());
            assertThat(current.latencyP99()).isGreaterThanOrEqualTo(current.latencyP95());
        }
    }

    @Nested
    class TrendTests {
        @BeforeEach
        void setUp() {
            calculator = DerivedMetricsCalculator.derivedMetricsCalculator(20);
        }

        @Test
        void trends_withFewerThan10Samples_areZero() {
            for (int i = 0; i < 9; i++) {
                calculator.addSample(snapshot(1000L + i * 1000L, 0.5, 500, 1000, 100, 5, 10.0));
            }

            var current = calculator.current();

            assertThat(current.cpuTrend()).isEqualTo(0.0);
            assertThat(current.latencyTrend()).isEqualTo(0.0);
            assertThat(current.errorTrend()).isEqualTo(0.0);
        }

        @Test
        void trends_with10OrMoreSamples_areCalculated() {
            // First 5: low CPU, low latency
            for (int i = 0; i < 5; i++) {
                calculator.addSample(snapshot(1000L + i * 1000L, 0.2, 500, 1000, 100, 0, 5.0));
            }
            // Second 5: high CPU, high latency
            for (int i = 5; i < 10; i++) {
                calculator.addSample(snapshot(1000L + i * 1000L, 0.8, 500, 1000, 100, 10, 20.0));
            }

            var current = calculator.current();

            // CPU trending up: second half avg (0.8) - first half avg (0.2) = 0.6
            assertThat(current.cpuTrend()).isGreaterThan(0.0);
            // Latency trending up: second half avg (20) - first half avg (5) = 15
            assertThat(current.latencyTrend()).isGreaterThan(0.0);
        }
    }

    @Nested
    class WindowEvictionTests {
        @Test
        void windowEviction_exceedsWindowSize_evictsOldSamples() {
            var smallCalculator = DerivedMetricsCalculator.derivedMetricsCalculator(5);

            // Add 7 samples to exceed window of 5
            for (int i = 0; i < 7; i++) {
                smallCalculator.addSample(snapshot(1000L + i * 1000L, 0.5, 500, 1000,
                    100, 5, 10.0));
            }

            // Derived metrics should only be based on the last 5 samples
            var current = smallCalculator.current();

            assertThat(current).isNotEqualTo(DerivedMetrics.EMPTY);
            // With 5 samples (< 10), trends should be zero
            assertThat(current.cpuTrend()).isEqualTo(0.0);
        }
    }
}
