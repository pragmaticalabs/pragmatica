// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ttm;

import java.util.List;

import org.pragmatica.aether.config.TtmConfig;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.metrics.MinuteAggregate;
import org.pragmatica.aether.ttm.model.FeatureIndex;
import org.pragmatica.aether.ttm.model.ScalingRecommendation;
import org.pragmatica.aether.ttm.model.TTMForecast;


public interface ForecastAnalyzer {
    TTMForecast analyze(float[] predictions,
                        double confidence,
                        List<MinuteAggregate> recentHistory,
                        ControllerConfig currentConfig);

    static ForecastAnalyzer forecastAnalyzer(TtmConfig config) {
        return new ForecastAnalyzerImpl(config);
    }
}

final class ForecastAnalyzerImpl implements ForecastAnalyzer {
    private static final float CPU_INCREASE_THRESHOLD = 0.15f;
    private static final float HIGH_CPU_THRESHOLD = 0.7f;
    private static final float HIGH_CPU_INCREASE_THRESHOLD = 0.1f;
    private static final double TARGET_CPU_UTILIZATION = 0.6;
    private static final float CPU_DECREASE_THRESHOLD = -0.15f;
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

        if (confidence < config.confidenceThreshold()) {
            return new TTMForecast(timestamp, predictions, confidence, ScalingRecommendation.NoAction.LOW_CONFIDENCE);
        }

        if (recentHistory.isEmpty()) {
            return new TTMForecast(timestamp, predictions, confidence, ScalingRecommendation.NoAction.INSUFFICIENT_DATA);
        }

        if (predictions.length <= FeatureIndex.INVOCATIONS) {
            return new TTMForecast(timestamp, predictions, confidence, ScalingRecommendation.NoAction.INSUFFICIENT_DATA);
        }

        var currentCpu = averageRecentCpu(recentHistory, 5);
        float predictedCpu = predictions[FeatureIndex.CPU_USAGE];
        float predictedLatency = predictions[FeatureIndex.LATENCY_MS];
        var recommendation = determineRecommendation(currentCpu, predictedCpu, predictedLatency, currentConfig);

        return new TTMForecast(timestamp, predictions, confidence, recommendation);
    }

    /// Only CPU drives this policy. Do not manufacture aggregate error rates or quantiles
    /// from minute ratios and quantiles that the recommendation does not consume.
    private float averageRecentCpu(List<MinuteAggregate> history, int count) {
        int start = Math.max(0, history.size() - count);

        return (float) history.subList(start,
                                       history.size())
                              .stream()
                              .mapToDouble(MinuteAggregate::avgCpuUsage)
                              .average()
                              .orElse(0);
    }

    private ScalingRecommendation determineRecommendation(float currentCpu,
                                                          float predictedCpu,
                                                          float predictedLatency,
                                                          ControllerConfig currentConfig) {
        float cpuIncrease = predictedCpu - currentCpu;

        if (cpuIncrease > CPU_INCREASE_THRESHOLD || (predictedCpu > HIGH_CPU_THRESHOLD && cpuIncrease > HIGH_CPU_INCREASE_THRESHOLD)) {
            int suggested = (int) Math.ceil(predictedCpu / TARGET_CPU_UTILIZATION);

            return new ScalingRecommendation.PreemptiveScaleUp(predictedCpu, predictedLatency, Math.max(1, suggested));
        }

        if (cpuIncrease < CPU_DECREASE_THRESHOLD && predictedCpu < LOW_CPU_THRESHOLD) {
            int suggested = Math.max(1, (int) Math.ceil(predictedCpu / SCALE_DOWN_TARGET_CPU));

            return new ScalingRecommendation.PreemptiveScaleDown(predictedCpu, suggested);
        }

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
