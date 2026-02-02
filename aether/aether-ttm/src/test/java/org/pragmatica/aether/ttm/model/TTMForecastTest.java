package org.pragmatica.aether.ttm.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TTMForecastTest {

    @Test
    void predictedCpuUsage_returnsCorrectFeature() {
        var predictions = new float[FeatureIndex.FEATURE_COUNT];
        predictions[FeatureIndex.CPU_USAGE] = 0.75f;

        var forecast = new TTMForecast(System.currentTimeMillis(), predictions, 0.9, ScalingRecommendation.NoAction.STABLE);

        assertThat(forecast.predictedCpuUsage()).isEqualTo(0.75f);
    }

    @Test
    void predictedHeapUsage_returnsCorrectFeature() {
        var predictions = new float[FeatureIndex.FEATURE_COUNT];
        predictions[FeatureIndex.HEAP_USAGE] = 0.65f;

        var forecast = new TTMForecast(System.currentTimeMillis(), predictions, 0.9, ScalingRecommendation.NoAction.STABLE);

        assertThat(forecast.predictedHeapUsage()).isEqualTo(0.65f);
    }

    @Test
    void predictedLatencyMs_returnsCorrectFeature() {
        var predictions = new float[FeatureIndex.FEATURE_COUNT];
        predictions[FeatureIndex.LATENCY_MS] = 150.5f;

        var forecast = new TTMForecast(System.currentTimeMillis(), predictions, 0.9, ScalingRecommendation.NoAction.STABLE);

        assertThat(forecast.predictedLatencyMs()).isEqualTo(150.5f);
    }

    @Test
    void predictedInvocations_returnsCorrectFeature() {
        var predictions = new float[FeatureIndex.FEATURE_COUNT];
        predictions[FeatureIndex.INVOCATIONS] = 1000f;

        var forecast = new TTMForecast(System.currentTimeMillis(), predictions, 0.9, ScalingRecommendation.NoAction.STABLE);

        assertThat(forecast.predictedInvocations()).isEqualTo(1000f);
    }

    @Test
    void predictedErrorRate_returnsCorrectFeature() {
        var predictions = new float[FeatureIndex.FEATURE_COUNT];
        predictions[FeatureIndex.ERROR_RATE] = 0.02f;

        var forecast = new TTMForecast(System.currentTimeMillis(), predictions, 0.9, ScalingRecommendation.NoAction.STABLE);

        assertThat(forecast.predictedErrorRate()).isEqualTo(0.02f);
    }

    @Test
    void indicatesLoadIncrease_returnsTrue_whenCpuIncreases() {
        var predictions = new float[FeatureIndex.FEATURE_COUNT];
        predictions[FeatureIndex.CPU_USAGE] = 0.8f;
        predictions[FeatureIndex.INVOCATIONS] = 100f;

        var forecast = new TTMForecast(System.currentTimeMillis(), predictions, 0.9, ScalingRecommendation.NoAction.STABLE);

        assertThat(forecast.indicatesLoadIncrease(0.5f, 100f)).isTrue();
    }

    @Test
    void indicatesLoadIncrease_returnsTrue_whenInvocationsIncrease() {
        var predictions = new float[FeatureIndex.FEATURE_COUNT];
        predictions[FeatureIndex.CPU_USAGE] = 0.5f;
        predictions[FeatureIndex.INVOCATIONS] = 200f;

        var forecast = new TTMForecast(System.currentTimeMillis(), predictions, 0.9, ScalingRecommendation.NoAction.STABLE);

        assertThat(forecast.indicatesLoadIncrease(0.5f, 100f)).isTrue();
    }

    @Test
    void indicatesLoadIncrease_returnsFalse_whenLoadStable() {
        var predictions = new float[FeatureIndex.FEATURE_COUNT];
        predictions[FeatureIndex.CPU_USAGE] = 0.5f;
        predictions[FeatureIndex.INVOCATIONS] = 100f;

        var forecast = new TTMForecast(System.currentTimeMillis(), predictions, 0.9, ScalingRecommendation.NoAction.STABLE);

        assertThat(forecast.indicatesLoadIncrease(0.5f, 100f)).isFalse();
    }

    @Test
    void indicatesLoadDecrease_returnsTrue_whenBothDecrease() {
        var predictions = new float[FeatureIndex.FEATURE_COUNT];
        predictions[FeatureIndex.CPU_USAGE] = 0.3f;
        predictions[FeatureIndex.INVOCATIONS] = 50f;

        var forecast = new TTMForecast(System.currentTimeMillis(), predictions, 0.9, ScalingRecommendation.NoAction.STABLE);

        assertThat(forecast.indicatesLoadDecrease(0.5f, 100f)).isTrue();
    }

    @Test
    void requiresAction_returnsTrue_forScaleUp() {
        var predictions = new float[FeatureIndex.FEATURE_COUNT];
        var forecast = new TTMForecast(System.currentTimeMillis(), predictions, 0.9,
                                       new ScalingRecommendation.PreemptiveScaleUp(0.8f, 100f, 2));

        assertThat(forecast.requiresAction()).isTrue();
    }

    @Test
    void requiresAction_returnsFalse_forNoAction() {
        var predictions = new float[FeatureIndex.FEATURE_COUNT];
        var forecast = new TTMForecast(System.currentTimeMillis(), predictions, 0.9, ScalingRecommendation.NoAction.STABLE);

        assertThat(forecast.requiresAction()).isFalse();
    }
}
