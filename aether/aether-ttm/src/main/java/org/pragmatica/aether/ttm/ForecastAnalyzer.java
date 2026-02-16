package org.pragmatica.aether.ttm;

import org.pragmatica.aether.config.TtmConfig;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.metrics.MinuteAggregate;
import org.pragmatica.aether.ttm.model.FeatureIndex;
import org.pragmatica.aether.ttm.model.ScalingRecommendation;
import org.pragmatica.aether.ttm.model.TTMForecast;

import java.util.List;

/// Analyzes TTM predictions and generates scaling recommendations.
public interface ForecastAnalyzer {
    /// Analyze predictions against current state.
    ///
    /// @param predictions   Raw predictions from TTM model
    /// @param confidence    Confidence score from model
    /// @param recentHistory Recent minute aggregates for comparison
    /// @param currentConfig Current controller configuration
    ///
    /// @return TTMForecast with recommendation
    TTMForecast analyze(float[] predictions,
                        double confidence,
                        List<MinuteAggregate> recentHistory,
                        ControllerConfig currentConfig);

    /// Create default analyzer.
    static ForecastAnalyzer forecastAnalyzer(TtmConfig config) {
        return new ForecastAnalyzerImpl(config);
    }
}

final class ForecastAnalyzerImpl implements ForecastAnalyzer {
    private static final float CPU_INCREASE_THRESHOLD = 0.15f;
    private static final float HIGH_CPU_THRESHOLD = 0.7f;
    private static final float HIGH_CPU_INCREASE_THRESHOLD = 0.1f;
    private static final double TARGET_CPU_UTILIZATION = 0.6;
    private static final float CPU_DECREASE_THRESHOLD = - 0.15f;
    private static final float LOW_CPU_THRESHOLD = 0.3f;
    private static final double SCALE_DOWN_TARGET_CPU = 0.5;
    private static final double MODERATE_CHANGE_THRESHOLD = 0.05;
    private static final double MIN_SCALE_UP_THRESHOLD = 0.5;
    private static final double MAX_SCALE_UP_THRESHOLD = 0.9;
    private static final double MIN_SCALE_DOWN_THRESHOLD = 0.1;
    private static final double MAX_SCALE_DOWN_THRESHOLD = 0.4;
    private static final double THRESHOLD_ADJUSTMENT_FACTOR = 0.5;

    private final TtmConfig config;

    ForecastAnalyzerImpl(TtmConfig config) {
        this.config = config;
    }

    @Override
    public TTMForecast analyze(float[] predictions,
                               double confidence,
                               List<MinuteAggregate> recentHistory,
                               ControllerConfig currentConfig) {
        long timestamp = System.currentTimeMillis();
        // Check confidence threshold
        if (confidence < config.confidenceThreshold()) {
            return new TTMForecast(timestamp, predictions, confidence, ScalingRecommendation.NoAction.LOW_CONFIDENCE);
        }
        // Check if we have enough history
        if (recentHistory.isEmpty()) {
            return new TTMForecast(timestamp, predictions, confidence, ScalingRecommendation.NoAction.INSUFFICIENT_DATA);
        }
        // Check predictions array has required indices
        if (predictions.length <= FeatureIndex.INVOCATIONS) {
            return new TTMForecast(timestamp, predictions, confidence, ScalingRecommendation.NoAction.INSUFFICIENT_DATA);
        }
        // Get current metrics (average of last 5 minutes or available)
        var current = averageRecent(recentHistory, 5);
        float predictedCpu = predictions[FeatureIndex.CPU_USAGE];
        float predictedLatency = predictions[FeatureIndex.LATENCY_MS];
        float predictedInvocations = predictions[FeatureIndex.INVOCATIONS];
        // Analyze trend and determine recommendation
        var recommendation = determineRecommendation(current,
                                                     predictedCpu,
                                                     predictedLatency,
                                                     predictedInvocations,
                                                     currentConfig);
        return new TTMForecast(timestamp, predictions, confidence, recommendation);
    }

    private MinuteAggregate averageRecent(List<MinuteAggregate> history, int count) {
        if (history.isEmpty()) {
            return MinuteAggregate.EMPTY;
        }
        int start = Math.max(0, history.size() - count);
        var recent = history.subList(start, history.size());
        double avgCpu = recent.stream()
                              .mapToDouble(MinuteAggregate::avgCpuUsage)
                              .average()
                              .orElse(0);
        double avgHeap = recent.stream()
                               .mapToDouble(MinuteAggregate::avgHeapUsage)
                               .average()
                               .orElse(0);
        double avgLag = recent.stream()
                              .mapToDouble(MinuteAggregate::avgEventLoopLagMs)
                              .average()
                              .orElse(0);
        double avgLatency = recent.stream()
                                  .mapToDouble(MinuteAggregate::avgLatencyMs)
                                  .average()
                                  .orElse(0);
        long totalInvocations = recent.stream()
                                      .mapToLong(MinuteAggregate::totalInvocations)
                                      .sum() / recent.size();
        long totalGc = recent.stream()
                             .mapToLong(MinuteAggregate::totalGcPauseMs)
                             .sum() / recent.size();
        double p50 = recent.stream()
                           .mapToDouble(MinuteAggregate::latencyP50)
                           .average()
                           .orElse(0);
        double p95 = recent.stream()
                           .mapToDouble(MinuteAggregate::latencyP95)
                           .average()
                           .orElse(0);
        double p99 = recent.stream()
                           .mapToDouble(MinuteAggregate::latencyP99)
                           .average()
                           .orElse(0);
        double errorRate = recent.stream()
                                 .mapToDouble(MinuteAggregate::errorRate)
                                 .average()
                                 .orElse(0);
        int events = recent.stream()
                           .mapToInt(MinuteAggregate::eventCount)
                           .sum() / recent.size();
        return MinuteAggregate.minuteAggregate(System.currentTimeMillis(),
                                               avgCpu,
                                               avgHeap,
                                               avgLag,
                                               avgLatency,
                                               totalInvocations,
                                               totalGc,
                                               p50,
                                               p95,
                                               p99,
                                               errorRate,
                                               events,
                                               recent.size());
    }

    private ScalingRecommendation determineRecommendation(MinuteAggregate current,
                                                          float predictedCpu,
                                                          float predictedLatency,
                                                          float predictedInvocations,
                                                          ControllerConfig currentConfig) {
        float currentCpu = (float) current.avgCpuUsage();
        float cpuIncrease = predictedCpu - currentCpu;
        // Significant load increase predicted
        if (cpuIncrease > CPU_INCREASE_THRESHOLD || (predictedCpu > HIGH_CPU_THRESHOLD && cpuIncrease > HIGH_CPU_INCREASE_THRESHOLD)) {
            int suggested = (int) Math.ceil(predictedCpu / TARGET_CPU_UTILIZATION);
            return new ScalingRecommendation.PreemptiveScaleUp(predictedCpu, predictedLatency, Math.max(1, suggested));
        }
        // Significant load decrease predicted
        if (cpuIncrease < CPU_DECREASE_THRESHOLD && predictedCpu < LOW_CPU_THRESHOLD) {
            int suggested = Math.max(1, (int) Math.ceil(predictedCpu / SCALE_DOWN_TARGET_CPU));
            return new ScalingRecommendation.PreemptiveScaleDown(predictedCpu, suggested);
        }
        // Moderate change - adjust thresholds
        if (Math.abs(cpuIncrease) > MODERATE_CHANGE_THRESHOLD) {
            double newScaleUp = Math.max(MIN_SCALE_UP_THRESHOLD,
                                         Math.min(MAX_SCALE_UP_THRESHOLD,
                                                  currentConfig.cpuScaleUpThreshold() - cpuIncrease * THRESHOLD_ADJUSTMENT_FACTOR));
            double newScaleDown = Math.max(MIN_SCALE_DOWN_THRESHOLD,
                                           Math.min(MAX_SCALE_DOWN_THRESHOLD,
                                                    currentConfig.cpuScaleDownThreshold() - cpuIncrease * THRESHOLD_ADJUSTMENT_FACTOR));
            return new ScalingRecommendation.AdjustThresholds(newScaleUp, newScaleDown);
        }
        return ScalingRecommendation.NoAction.STABLE;
    }
}
