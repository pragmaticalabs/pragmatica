package org.pragmatica.aether.ttm;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.TtmConfig;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.metrics.MinuteAggregate;
import org.pragmatica.aether.ttm.model.FeatureIndex;
import org.pragmatica.aether.ttm.model.ScalingRecommendation;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastAnalyzerTest {
    private final ForecastAnalyzer analyzer = ForecastAnalyzer.forecastAnalyzer(TtmConfig.ttmConfig());

    @Test
    void recommendationUsesOnlyTheLastFiveCpuMinutes() {
        var recent = minute(0.4, 10, 0.1);
        var history = List.of(minute(0, 1, 1), recent, recent, recent, recent, recent);
        var forecast = analyzer.analyze(prediction(0.5f), 1, history, ControllerConfig.DEFAULT);
        assertThat(forecast.recommendation()).isInstanceOf(ScalingRecommendation.AdjustThresholds.class);
        // Including the older low-CPU minute would incorrectly request scale-up.
        assertThat(analyzer.analyze(prediction(0.7f), 1, history, ControllerConfig.DEFAULT).recommendation())
            .isInstanceOf(ScalingRecommendation.PreemptiveScaleUp.class);
    }

    @Test
    void cpuBaselineIsTimeAveragedAndDoesNotWeightByRequestOrErrorCounts() {
        var history = List.of(minute(0, 1, 1), minute(1, 10000, 0));
        assertThat(analyzer.analyze(prediction(0.5f), 1, history, ControllerConfig.DEFAULT).recommendation())
            .isEqualTo(ScalingRecommendation.NoAction.STABLE);
    }

    private static float[] prediction(float cpu) {
        var features = new float[FeatureIndex.FEATURE_COUNT];
        features[FeatureIndex.CPU_USAGE] = cpu;
        return features;
    }

    private static MinuteAggregate minute(double cpu, long requests, double errorRate) {
        return MinuteAggregate.minuteAggregate(0, cpu, 0, 0, 10, requests, 0, 1, 10, 100,
            errorRate, 0, 60);
    }
}
