package org.pragmatica.aether.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MetricWindowTest {

    private MetricWindow window;

    @BeforeEach
    void setUp() {
        window = MetricWindow.metricWindow(5).unwrap();
    }

    @Nested
    class FactoryValidation {
        @Test
        void metricWindow_validWindowSize_returnsSuccess() {
            var result = MetricWindow.metricWindow(10);

            result.onFailure(c -> org.junit.jupiter.api.Assertions.fail(c.message()))
                  .onSuccess(w -> assertThat(w.windowSize()).isEqualTo(10));
        }

        @Test
        void metricWindow_zeroWindowSize_returnsFailure() {
            var result = MetricWindow.metricWindow(0);

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void metricWindow_negativeWindowSize_returnsFailure() {
            var result = MetricWindow.metricWindow(-3);

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class RecordingValues {
        @Test
        void record_returnsNewInstance_preservingImmutability() {
            var updated = window.record(5.0);

            assertThat(updated).isNotSameAs(window);
            assertThat(window.count()).isEqualTo(0);
            assertThat(updated.count()).isEqualTo(1);
        }

        @Test
        void record_addsValue_countIncreases() {
            var updated = window.record(1.0).record(2.0).record(3.0);

            assertThat(updated.count()).isEqualTo(3);
        }

        @Test
        void record_fullWindow_evictsOldestValue() {
            var current = window;
            for (int i = 1; i <= 6; i++) {
                current = current.record(i);
            }

            assertThat(current.count()).isEqualTo(5);
            // Window should contain 2,3,4,5,6 (first value evicted)
            assertThat(current.average()).isCloseTo(4.0, within(0.001));
        }
    }

    @Nested
    class AverageCalculation {
        @Test
        void average_emptyWindow_returnsZero() {
            assertThat(window.average()).isEqualTo(0.0);
        }

        @Test
        void average_withValues_calculatesCorrectly() {
            var updated = window.record(10.0).record(20.0).record(30.0);

            assertThat(updated.average()).isCloseTo(20.0, within(0.001));
        }
    }

    @Nested
    class LastValueRetrieval {
        @Test
        void lastValue_emptyWindow_returnsZero() {
            assertThat(window.lastValue()).isEqualTo(0.0);
        }

        @Test
        void lastValue_afterRecording_returnsMostRecentValue() {
            var updated = window.record(1.0).record(2.0).record(99.0);

            assertThat(updated.lastValue()).isEqualTo(99.0);
        }
    }

    @Nested
    class RelativeChangeCalculation {
        @Test
        void relativeChange_zeroAverage_returnsOne() {
            assertThat(window.relativeChange(5.0)).isEqualTo(1.0);
        }

        @Test
        void relativeChange_withAverage_returnsCurrentOverAverage() {
            var updated = window.record(10.0).record(10.0).record(10.0);

            assertThat(updated.relativeChange(20.0)).isCloseTo(2.0, within(0.001));
        }
    }

    @Nested
    class CapacityChecks {
        @Test
        void isFull_notAtCapacity_returnsFalse() {
            var updated = window.record(1.0).record(2.0);

            assertThat(updated.isFull()).isFalse();
        }

        @Test
        void isFull_atCapacity_returnsTrue() {
            var current = window;
            for (int i = 0; i < 5; i++) {
                current = current.record(i);
            }

            assertThat(current.isFull()).isTrue();
        }
    }

    @Nested
    class DefensiveCopy {
        @Test
        void values_returnsDefensiveCopy() {
            var updated = window.record(42.0);
            var values = updated.values();
            values[0] = 999.0;

            assertThat(updated.values()[0]).isNotEqualTo(999.0);
        }
    }

    @Nested
    class SumDriftCorrection {
        @Test
        void record_every100Records_recalculatesSum() {
            var current = MetricWindow.metricWindow(10).unwrap();
            for (int i = 0; i < 100; i++) {
                current = current.record(1.0);
            }

            // After 100 records, sum should be exactly 10.0 (10 windows of 1.0)
            assertThat(current.average()).isCloseTo(1.0, within(0.0001));
        }
    }

    @Nested
    class WindowSize {
        @Test
        void windowSize_returnsCapacity() {
            assertThat(window.windowSize()).isEqualTo(5);
        }
    }
}
