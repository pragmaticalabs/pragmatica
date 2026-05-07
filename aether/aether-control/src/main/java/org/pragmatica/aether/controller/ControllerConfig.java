// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


public record ControllerConfig(double cpuScaleUpThreshold,
                               double cpuScaleDownThreshold,
                               double callRateScaleUpThreshold,
                               long evaluationIntervalMs,
                               long warmUpPeriodMs,
                               long sliceCooldownMs,
                               ScalingConfig scalingConfig) {
    private static final Fn1<Cause, String> INVALID_THRESHOLD = Causes.forOneValue("Invalid threshold: %s (must be between 0.0 and 1.0)");

    private static final Fn1<Cause, String> INVALID_POSITIVE = Causes.forOneValue("Invalid value: %s (must be positive)");

    private static final Fn1<Cause, String> INVALID_NON_NEGATIVE = Causes.forOneValue("Invalid value: %s (must be non-negative)");

    private static final Cause INVALID_THRESHOLD_ORDER = Causes.cause("cpuScaleUpThreshold must be greater than cpuScaleDownThreshold");

    public static final ControllerConfig DEFAULT = new ControllerConfig(0.8,
                                                                        0.2,
                                                                        2000,
                                                                        1000,
                                                                        30000,
                                                                        10000,
                                                                        ScalingConfig.productionDefaults());

    public static Result<ControllerConfig> controllerConfig(double cpuScaleUpThreshold,
                                                            double cpuScaleDownThreshold,
                                                            double callRateScaleUpThreshold,
                                                            long evaluationIntervalMs) {
        return controllerConfig(cpuScaleUpThreshold,
                                cpuScaleDownThreshold,
                                callRateScaleUpThreshold,
                                evaluationIntervalMs,
                                DEFAULT.warmUpPeriodMs(),
                                DEFAULT.sliceCooldownMs());
    }

    public static Result<ControllerConfig> controllerConfig(double cpuScaleUpThreshold,
                                                            double cpuScaleDownThreshold,
                                                            double callRateScaleUpThreshold,
                                                            long evaluationIntervalMs,
                                                            long warmUpPeriodMs,
                                                            long sliceCooldownMs) {
        return controllerConfig(cpuScaleUpThreshold,
                                cpuScaleDownThreshold,
                                callRateScaleUpThreshold,
                                evaluationIntervalMs,
                                warmUpPeriodMs,
                                sliceCooldownMs,
                                ScalingConfig.productionDefaults());
    }

    public static Result<ControllerConfig> controllerConfig(double cpuScaleUpThreshold,
                                                            double cpuScaleDownThreshold,
                                                            double callRateScaleUpThreshold,
                                                            long evaluationIntervalMs,
                                                            long warmUpPeriodMs,
                                                            long sliceCooldownMs,
                                                            ScalingConfig scalingConfig) {
        return validateThreshold(cpuScaleUpThreshold, "cpuScaleUpThreshold").flatMap(_ -> validateThreshold(cpuScaleDownThreshold,
                                                                                                            "cpuScaleDownThreshold"))
                                .flatMap(_ -> validatePositive(callRateScaleUpThreshold, "callRateScaleUpThreshold"))
                                .flatMap(_ -> validatePositive(evaluationIntervalMs, "evaluationIntervalMs"))
                                .flatMap(_ -> validateNonNegative(warmUpPeriodMs, "warmUpPeriodMs"))
                                .flatMap(_ -> validateNonNegative(sliceCooldownMs, "sliceCooldownMs"))
                                .flatMap(_ -> validateThresholdOrder(cpuScaleUpThreshold, cpuScaleDownThreshold))
                                .map(_ -> new ControllerConfig(cpuScaleUpThreshold,
                                                               cpuScaleDownThreshold,
                                                               callRateScaleUpThreshold,
                                                               evaluationIntervalMs,
                                                               warmUpPeriodMs,
                                                               sliceCooldownMs,
                                                               scalingConfig));
    }

    private static Result<Double> validateThreshold(double value, String name) {
        return value >= 0.0 && value <= 1.0
              ? Result.success(value)
              : INVALID_THRESHOLD.apply(name + "=" + value).result();
    }

    private static Result<Double> validatePositive(double value, String name) {
        return value > 0
              ? Result.success(value)
              : INVALID_POSITIVE.apply(name + "=" + value).result();
    }

    private static Result<Long> validatePositive(long value, String name) {
        return value > 0
              ? Result.success(value)
              : INVALID_POSITIVE.apply(name + "=" + value).result();
    }

    private static Result<Long> validateNonNegative(long value, String name) {
        return value >= 0
              ? Result.success(value)
              : INVALID_NON_NEGATIVE.apply(name + "=" + value).result();
    }

    private static Result<Double> validateThresholdOrder(double up, double down) {
        return up > down
              ? Result.success(up)
              : INVALID_THRESHOLD_ORDER.result();
    }

    public ControllerConfig withCpuScaleUpThreshold(double threshold) {
        return new ControllerConfig(threshold,
                                    cpuScaleDownThreshold,
                                    callRateScaleUpThreshold,
                                    evaluationIntervalMs,
                                    warmUpPeriodMs,
                                    sliceCooldownMs,
                                    scalingConfig);
    }

    public ControllerConfig withCpuScaleDownThreshold(double threshold) {
        return new ControllerConfig(cpuScaleUpThreshold,
                                    threshold,
                                    callRateScaleUpThreshold,
                                    evaluationIntervalMs,
                                    warmUpPeriodMs,
                                    sliceCooldownMs,
                                    scalingConfig);
    }

    public ControllerConfig withScalingConfig(ScalingConfig newScalingConfig) {
        return new ControllerConfig(cpuScaleUpThreshold,
                                    cpuScaleDownThreshold,
                                    callRateScaleUpThreshold,
                                    evaluationIntervalMs,
                                    warmUpPeriodMs,
                                    sliceCooldownMs,
                                    newScalingConfig);
    }

    public static ControllerConfig forgeDefaults() {
        return DEFAULT.withScalingConfig(ScalingConfig.forgeDefaults());
    }

    public String toJson() {
        return "{\"cpuScaleUpThreshold\":" + cpuScaleUpThreshold + ",\"cpuScaleDownThreshold\":" + cpuScaleDownThreshold + ",\"callRateScaleUpThreshold\":" + callRateScaleUpThreshold + ",\"evaluationIntervalMs\":" + evaluationIntervalMs + ",\"warmUpPeriodMs\":" + warmUpPeriodMs + ",\"sliceCooldownMs\":" + sliceCooldownMs + ",\"scalingConfig\":" + scalingConfigToJson() + "}";
    }

    private String scalingConfigToJson() {
        var weightsJson = new StringBuilder("{");
        var first = true;
        for (var entry : scalingConfig.weights().entrySet()) {
            if (!first) {weightsJson.append(",");}
            weightsJson.append("\"").append(entry.getKey().name())
                              .append("\":")
                              .append(entry.getValue());
            first = false;
        }
        weightsJson.append("}");
        return "{\"windowSize\":" + scalingConfig.windowSize() + ",\"evaluationIntervalMs\":" + scalingConfig.evaluationIntervalMs() + ",\"scaleUpThreshold\":" + scalingConfig.scaleUpThreshold() + ",\"scaleDownThreshold\":" + scalingConfig.scaleDownThreshold() + ",\"errorRateBlockThreshold\":" + scalingConfig.errorRateBlockThreshold() + ",\"weights\":" + weightsJson + "}";
    }
}
