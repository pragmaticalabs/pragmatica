package org.pragmatica.aether.ttm;

import org.pragmatica.aether.config.TTMConfig;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.metrics.MinuteAggregate;
import org.pragmatica.aether.ttm.model.FeatureIndex;
import org.pragmatica.aether.ttm.model.ScalingRecommendation;
import org.pragmatica.aether.ttm.model.TTMForecast;

import java.util.List;

/**
 * Analyzes TTM predictions and generates scaling recommendations.
 */
public interface ForecastAnalyzer {
    /**
     * Analyze predictions against current state.
     *
     * @param predictions   Raw predictions from TTM model
     * @param confidence    Confidence score from model
     * @param recentHistory Recent minute aggregates for comparison
     * @param currentConfig Current controller configuration
     *
     * @return TTMForecast with recommendation
     */
    TTMForecast analyze(float[] predictions,
                        double confidence,
                        List<MinuteAggregate> recentHistory,
                        ControllerConfig currentConfig);

    /**
     * Create default analyzer.
     */
    static ForecastAnalyzer forecastAnalyzer(TTMConfig config) {
        return new ForecastAnalyzerImpl(config);
    }
}

final class ForecastAnalyzerImpl implements ForecastAnalyzer {
    private final TTMConfig config;

    ForecastAnalyzerImpl(TTMConfig config) {
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
        if (cpuIncrease > 0.15 || (predictedCpu > 0.7 && cpuIncrease > 0.1)) {
            int suggested = (int) Math.ceil(predictedCpu / 0.6);
            // Target 60% CPU
            return new ScalingRecommendation.PreemptiveScaleUp(predictedCpu, predictedLatency, Math.max(1, suggested));
        }
        // Significant load decrease predicted
        if (cpuIncrease < - 0.15 && predictedCpu < 0.3) {
            int suggested = Math.max(1, (int) Math.ceil(predictedCpu / 0.5));
            return new ScalingRecommendation.PreemptiveScaleDown(predictedCpu, suggested);
        }
        // Moderate change - adjust thresholds
        if (Math.abs(cpuIncrease) > 0.05) {
            double newScaleUp = Math.max(0.5,
                                         Math.min(0.9, currentConfig.cpuScaleUpThreshold() - cpuIncrease * 0.5));
            double newScaleDown = Math.max(0.1,
                                           Math.min(0.4, currentConfig.cpuScaleDownThreshold() - cpuIncrease * 0.5));
            return new ScalingRecommendation.AdjustThresholds(newScaleUp, newScaleDown);
        }
        return ScalingRecommendation.NoAction.STABLE;
    }
}
