// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.EnumMap;
import java.util.Map;


/// Configuration for the Lizard Brain relative change scaling algorithm.
///
/// This replaces absolute metric thresholds with relative change detection
/// using sliding windows.
///
/// **Note on evaluationIntervalMs:** Both {@link ControllerConfig} and ScalingConfig have
/// an evaluationIntervalMs field. They serve different purposes:
///
///   - `ControllerConfig.evaluationIntervalMs` - Controls the scheduler interval for the control loop
///       (how often the controller runs its evaluation cycle)
///   - `ScalingConfig.evaluationIntervalMs` - Used for window timing semantics in the Lizard Brain
///       algorithm (determines how long until the sliding window is considered "full" for scaling decisions)
///
/// @param windowSize              Number of samples in each metric's sliding window (default: 10)
/// @param evaluationIntervalMs    Interval for window timing semantics in milliseconds (default: 5000).
///                                This determines window fill time: windowSize * evaluationIntervalMs.
/// @param scaleUpThreshold        Relative change above which to scale up (default: 1.5 = 50% above average)
/// @param scaleDownThreshold      Relative change below which to scale down (default: 0.5 = 50% below average)
/// @param weights                 Per-metric weights for composite load factor calculation
/// @param errorRateBlockThreshold Error rate threshold above which scale-up is blocked (default: 0.1)
public record ScalingConfig(int windowSize,
                            long evaluationIntervalMs,
                            double scaleUpThreshold,
                            double scaleDownThreshold,
                            Map<ScalingMetric, Double> weights,
                            double errorRateBlockThreshold) {
    private static final int DEFAULT_WINDOW_SIZE = 10;

    private static final int FORGE_WINDOW_SIZE = 5;

    private static final long DEFAULT_EVALUATION_INTERVAL_MS = 5000;

    private static final double DEFAULT_SCALE_UP_THRESHOLD = 1.5;

    private static final double DEFAULT_SCALE_DOWN_THRESHOLD = 0.5;

    private static final double DEFAULT_ERROR_RATE_BLOCK_THRESHOLD = 0.1;

    @Deprecated static final double ERROR_RATE_BLOCK_THRESHOLD = DEFAULT_ERROR_RATE_BLOCK_THRESHOLD;

    @SuppressWarnings("JBCT-VO-02") public static ScalingConfig productionDefaults() {
        var weights = new EnumMap<ScalingMetric, Double>(ScalingMetric.class);
        weights.put(ScalingMetric.CPU, 0.4);
        weights.put(ScalingMetric.ACTIVE_INVOCATIONS, 0.4);
        weights.put(ScalingMetric.P95_LATENCY, 0.2);
        weights.put(ScalingMetric.ERROR_RATE, 0.0);
        return new ScalingConfig(DEFAULT_WINDOW_SIZE,
                                 DEFAULT_EVALUATION_INTERVAL_MS,
                                 DEFAULT_SCALE_UP_THRESHOLD,
                                 DEFAULT_SCALE_DOWN_THRESHOLD,
                                 Map.copyOf(weights),
                                 DEFAULT_ERROR_RATE_BLOCK_THRESHOLD);
    }

    @SuppressWarnings("JBCT-VO-02") public static ScalingConfig forgeDefaults() {
        var weights = new EnumMap<ScalingMetric, Double>(ScalingMetric.class);
        weights.put(ScalingMetric.CPU, 0.0);
        weights.put(ScalingMetric.ACTIVE_INVOCATIONS, 0.6);
        weights.put(ScalingMetric.P95_LATENCY, 0.4);
        weights.put(ScalingMetric.ERROR_RATE, 0.0);
        return new ScalingConfig(FORGE_WINDOW_SIZE,
                                 DEFAULT_EVALUATION_INTERVAL_MS,
                                 DEFAULT_SCALE_UP_THRESHOLD,
                                 DEFAULT_SCALE_DOWN_THRESHOLD,
                                 Map.copyOf(weights),
                                 DEFAULT_ERROR_RATE_BLOCK_THRESHOLD);
    }

    public static Result<ScalingConfig> scalingConfig(int windowSize,
                                                      long evaluationIntervalMs,
                                                      double scaleUpThreshold,
                                                      double scaleDownThreshold,
                                                      Map<ScalingMetric, Double> weights) {
        return scalingConfig(windowSize,
                             evaluationIntervalMs,
                             scaleUpThreshold,
                             scaleDownThreshold,
                             weights,
                             DEFAULT_ERROR_RATE_BLOCK_THRESHOLD);
    }

    public static Result<ScalingConfig> scalingConfig(int windowSize,
                                                      long evaluationIntervalMs,
                                                      double scaleUpThreshold,
                                                      double scaleDownThreshold,
                                                      Map<ScalingMetric, Double> weights,
                                                      double errorRateBlockThreshold) {
        return validatePositive(windowSize, "windowSize").flatMap(_ -> validatePositive(scaleUpThreshold,
                                                                                        "scaleUpThreshold"))
                               .flatMap(_ -> validatePositive(scaleDownThreshold, "scaleDownThreshold"))
                               .flatMap(_ -> validateThresholdOrder(scaleUpThreshold, scaleDownThreshold))
                               .flatMap(_ -> validateWeights(weights))
                               .map(_ -> new ScalingConfig(windowSize,
                                                           evaluationIntervalMs,
                                                           scaleUpThreshold,
                                                           scaleDownThreshold,
                                                           Map.copyOf(weights),
                                                           errorRateBlockThreshold));
    }

    private static Result<Unit> validatePositive(double value, String name) {
        return value > 0
              ? Result.unitResult()
              : Causes.cause(name + " must be positive, got: " + value).result();
    }

    private static Result<Unit> validatePositive(int value, String name) {
        return value > 0
              ? Result.unitResult()
              : Causes.cause(name + " must be positive, got: " + value).result();
    }

    private static Result<Unit> validateThresholdOrder(double scaleUp, double scaleDown) {
        return scaleUp > scaleDown
              ? Result.unitResult()
              : Causes.cause("scaleUpThreshold must be greater than scaleDownThreshold, got: " + scaleUp + " <= " + scaleDown)
                            .result();
    }

    private static Result<Unit> validateWeights(Map<ScalingMetric, Double> weights) {
        for (var entry : weights.entrySet()) {if (entry.getValue() <0) {return Causes.cause("Weight for " + entry.getKey() + " must be >= 0, got: " + entry.getValue())
                                                                                           .result();}}
        return Result.unitResult();
    }

    public double weight(ScalingMetric metric) {
        return weights.getOrDefault(metric, 0.0);
    }

    public Result<ScalingConfig> withWeight(ScalingMetric metric, double newWeight) {
        var newWeights = new EnumMap<ScalingMetric, Double>(ScalingMetric.class);
        newWeights.putAll(weights);
        newWeights.put(metric, newWeight);
        return scalingConfig(windowSize,
                             evaluationIntervalMs,
                             scaleUpThreshold,
                             scaleDownThreshold,
                             newWeights,
                             errorRateBlockThreshold);
    }

    public Result<ScalingConfig> withWindowSize(int newWindowSize) {
        return scalingConfig(newWindowSize,
                             evaluationIntervalMs,
                             scaleUpThreshold,
                             scaleDownThreshold,
                             weights,
                             errorRateBlockThreshold);
    }

    public Result<ScalingConfig> withEvaluationIntervalMs(long newIntervalMs) {
        return scalingConfig(windowSize,
                             newIntervalMs,
                             scaleUpThreshold,
                             scaleDownThreshold,
                             weights,
                             errorRateBlockThreshold);
    }

    public Result<ScalingConfig> withScaleUpThreshold(double newThreshold) {
        return scalingConfig(windowSize,
                             evaluationIntervalMs,
                             newThreshold,
                             scaleDownThreshold,
                             weights,
                             errorRateBlockThreshold);
    }

    public Result<ScalingConfig> withScaleDownThreshold(double newThreshold) {
        return scalingConfig(windowSize,
                             evaluationIntervalMs,
                             scaleUpThreshold,
                             newThreshold,
                             weights,
                             errorRateBlockThreshold);
    }

    public Result<ScalingConfig> withErrorRateBlockThreshold(double newThreshold) {
        return scalingConfig(windowSize,
                             evaluationIntervalMs,
                             scaleUpThreshold,
                             scaleDownThreshold,
                             weights,
                             newThreshold);
    }
}
