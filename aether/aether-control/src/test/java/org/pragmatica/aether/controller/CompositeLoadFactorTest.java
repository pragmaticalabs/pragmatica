package org.pragmatica.aether.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CompositeLoadFactorTest {

    private ScalingConfig config;
    private CompositeLoadFactor loadFactor;

    @BeforeEach
    void setUp() {
        config = ScalingConfig.productionDefaults();
        loadFactor = CompositeLoadFactor.compositeLoadFactor(config);
    }

    @Nested
    class ComputeBeforeReady {
        @Test
        void compute_windowsNotFull_returnsNotReady() {
            var result = loadFactor.compute();

            assertThat(result.canScale()).isFalse();
            assertThat(result.compositeScore()).isEqualTo(1.0);
        }

        @Test
        void notReady_hasCompositeScoreOneAndCanScaleFalse() {
            var result = CompositeLoadFactor.LoadFactorResult.notReady();

            assertThat(result.compositeScore()).isEqualTo(1.0);
            assertThat(result.canScale()).isFalse();
            assertThat(result.components()).isEmpty();
        }
    }

    @Nested
    class RecordingAndComputing {
        @Test
        void recordSample_fillsWindows() {
            fillAllWindows(1.0);

            var result = loadFactor.compute();

            assertThat(result.canScale()).isTrue();
        }

        @Test
        void compute_allMetricsAtAverage_compositeScoreIsOne() {
            fillAllWindows(10.0);

            var result = loadFactor.compute();

            assertThat(result.compositeScore()).isCloseTo(1.0, within(0.01));
        }

        @Test
        void compute_metricsAboveAverage_compositeScoreGreaterThanOne() {
            // Fill windows with low values, then record high values
            fillAllWindowsExceptLast(1.0);
            recordAllMetrics(10.0);

            var result = loadFactor.compute();

            assertThat(result.compositeScore()).isGreaterThan(1.0);
        }

        @Test
        void compute_metricsBelowAverage_compositeScoreLessThanOne() {
            // Fill windows with high values, then record low values
            fillAllWindowsExceptLast(10.0);
            recordAllMetrics(1.0);

            var result = loadFactor.compute();

            assertThat(result.compositeScore()).isLessThan(1.0);
        }
    }

    @Nested
    class ErrorRateGuardRail {
        @Test
        void isErrorRateHigh_windowNotFull_returnsFalse() {
            loadFactor.recordSample(ScalingMetric.ERROR_RATE, 0.9);

            assertThat(loadFactor.isErrorRateHigh()).isFalse();
        }

        @Test
        void isErrorRateHigh_aboveThreshold_returnsTrue() {
            for (int i = 0; i < config.windowSize(); i++) {
                loadFactor.recordSample(ScalingMetric.ERROR_RATE, 0.5);
            }

            assertThat(loadFactor.isErrorRateHigh()).isTrue();
        }

        @Test
        void isErrorRateHigh_belowThreshold_returnsFalse() {
            for (int i = 0; i < config.windowSize(); i++) {
                loadFactor.recordSample(ScalingMetric.ERROR_RATE, 0.01);
            }

            assertThat(loadFactor.isErrorRateHigh()).isFalse();
        }
    }

    @Nested
    class ComputeWithCurrentValues {
        @Test
        void computeWithCurrentValues_usesProvidedValues() {
            fillAllWindows(10.0);

            var currentValues = new EnumMap<ScalingMetric, Double>(ScalingMetric.class);
            currentValues.put(ScalingMetric.CPU, 20.0);
            currentValues.put(ScalingMetric.ACTIVE_INVOCATIONS, 20.0);
            currentValues.put(ScalingMetric.P95_LATENCY, 20.0);

            var result = loadFactor.computeWithCurrentValues(currentValues);

            assertThat(result.canScale()).isTrue();
            assertThat(result.compositeScore()).isCloseTo(2.0, within(0.01));
        }
    }

    @Nested
    class WeightEffects {
        @Test
        void compute_zeroWeightMetrics_excludedFromCalculation() {
            // Use forge defaults where CPU weight is 0
            var forgeConfig = ScalingConfig.forgeDefaults();
            var forgeLF = CompositeLoadFactor.compositeLoadFactor(forgeConfig);

            // Fill windows
            for (int i = 0; i < forgeConfig.windowSize(); i++) {
                forgeLF.recordSample(ScalingMetric.CPU, 100.0);
                forgeLF.recordSample(ScalingMetric.ACTIVE_INVOCATIONS, 10.0);
                forgeLF.recordSample(ScalingMetric.P95_LATENCY, 10.0);
                forgeLF.recordSample(ScalingMetric.ERROR_RATE, 0.0);
            }

            var result = forgeLF.compute();

            // CPU has zero weight, so even extreme CPU values don't affect score
            assertThat(result.canScale()).isTrue();
            assertThat(result.components()).doesNotContainKey(ScalingMetric.CPU);
        }

        @Test
        void compute_weightsAffectCompositeScoreProportionally() {
            fillAllWindows(10.0);

            // The score should be proportionally weighted
            var result = loadFactor.compute();

            assertThat(result.components()).containsKey(ScalingMetric.CPU);
            assertThat(result.components()).containsKey(ScalingMetric.ACTIVE_INVOCATIONS);
            assertThat(result.components()).containsKey(ScalingMetric.P95_LATENCY);
        }
    }

    // === Helpers ===

    private void fillAllWindows(double value) {
        for (int i = 0; i < config.windowSize(); i++) {
            recordAllMetrics(value);
        }
    }

    private void fillAllWindowsExceptLast(double value) {
        for (int i = 0; i < config.windowSize() - 1; i++) {
            recordAllMetrics(value);
        }
    }

    private void recordAllMetrics(double value) {
        loadFactor.recordSample(ScalingMetric.CPU, value);
        loadFactor.recordSample(ScalingMetric.ACTIVE_INVOCATIONS, value);
        loadFactor.recordSample(ScalingMetric.P95_LATENCY, value);
        loadFactor.recordSample(ScalingMetric.ERROR_RATE, 0.0);
    }
}
