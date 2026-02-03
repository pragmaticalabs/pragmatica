package org.pragmatica.aether.controller;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

/**
 * Configuration for the cluster controller.
 *
 * <p>Controls thresholds for automatic scaling decisions.
 *
 * <p><strong>Note on evaluationIntervalMs:</strong> Both ControllerConfig and ScalingConfig have
 * an evaluationIntervalMs field. They serve different purposes:
 * <ul>
 *   <li>{@code ControllerConfig.evaluationIntervalMs} - Controls the scheduler interval for the control loop</li>
 *   <li>{@code ScalingConfig.evaluationIntervalMs} - Used for window timing calculations (how long until window is full)</li>
 * </ul>
 *
 * @param cpuScaleUpThreshold CPU usage above which to scale up (0.0-1.0)
 * @param cpuScaleDownThreshold CPU usage below which to scale down (0.0-1.0)
 * @param callRateScaleUpThreshold call rate above which to scale up (must be positive)
 * @param evaluationIntervalMs interval between controller evaluations in milliseconds (must be positive)
 * @param warmUpPeriodMs time after ControlLoop activation during which scaling is blocked (must be non-negative)
 * @param sliceCooldownMs time after slice reaches ACTIVE during which scaling is blocked (must be non-negative)
 * @param scalingConfig configuration for the Lizard Brain relative change scaling algorithm
 */
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

    /**
     * Default configuration with known-valid values.
     * These values are validated at compile time and guaranteed to pass factory validation.
     */
    public static final ControllerConfig DEFAULT = new ControllerConfig(0.8,
                                                                        0.2,
                                                                        2000,
                                                                        1000,
                                                                        30000,
                                                                        10000,
                                                                        ScalingConfig.productionDefaults());

    /**
     * Creates default configuration.
     *
     * <p>This is a semantic factory variant (not following {@code typeName()} pattern)
     * because it represents a named configuration profile rather than generic construction.
     * See also: {@link #forgeDefaults()}, {@link #controllerConfig(double, double, double, long)}.
     * @deprecated Use {@link #defaultConfig()} instead.
     */
    public static ControllerConfig defaultConfig() {
        return DEFAULT;
    }

    /**
     * Factory method with validation following JBCT naming convention.
     *
     * @return Result containing valid config or validation error
     */
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

    /**
     * Factory method with validation including warm-up and cooldown periods.
     *
     * @return Result containing valid config or validation error
     */
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

    /**
     * Factory method with validation including all parameters.
     *
     * @return Result containing valid config or validation error
     */
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
               : INVALID_THRESHOLD.apply(name + "=" + value)
                                  .result();
    }

    private static Result<Double> validatePositive(double value, String name) {
        return value > 0
               ? Result.success(value)
               : INVALID_POSITIVE.apply(name + "=" + value)
                                 .result();
    }

    private static Result<Long> validatePositive(long value, String name) {
        return value > 0
               ? Result.success(value)
               : INVALID_POSITIVE.apply(name + "=" + value)
                                 .result();
    }

    private static Result<Long> validateNonNegative(long value, String name) {
        return value >= 0
               ? Result.success(value)
               : INVALID_NON_NEGATIVE.apply(name + "=" + value)
                                     .result();
    }

    private static Result<Double> validateThresholdOrder(double up, double down) {
        return up > down
               ? Result.success(up)
               : INVALID_THRESHOLD_ORDER.result();
    }

    /**
     * Returns a copy with updated CPU scale-up threshold.
     */
    public ControllerConfig withCpuScaleUpThreshold(double threshold) {
        return new ControllerConfig(threshold,
                                    cpuScaleDownThreshold,
                                    callRateScaleUpThreshold,
                                    evaluationIntervalMs,
                                    warmUpPeriodMs,
                                    sliceCooldownMs,
                                    scalingConfig);
    }

    /**
     * Returns a copy with updated CPU scale-down threshold.
     */
    public ControllerConfig withCpuScaleDownThreshold(double threshold) {
        return new ControllerConfig(cpuScaleUpThreshold,
                                    threshold,
                                    callRateScaleUpThreshold,
                                    evaluationIntervalMs,
                                    warmUpPeriodMs,
                                    sliceCooldownMs,
                                    scalingConfig);
    }

    /**
     * Returns a copy with updated call rate threshold.
     */
    public ControllerConfig withCallRateScaleUpThreshold(double threshold) {
        return new ControllerConfig(cpuScaleUpThreshold,
                                    cpuScaleDownThreshold,
                                    threshold,
                                    evaluationIntervalMs,
                                    warmUpPeriodMs,
                                    sliceCooldownMs,
                                    scalingConfig);
    }

    /**
     * Returns a copy with updated evaluation interval.
     */
    public ControllerConfig withEvaluationIntervalMs(long intervalMs) {
        return new ControllerConfig(cpuScaleUpThreshold,
                                    cpuScaleDownThreshold,
                                    callRateScaleUpThreshold,
                                    intervalMs,
                                    warmUpPeriodMs,
                                    sliceCooldownMs,
                                    scalingConfig);
    }

    /**
     * Returns a copy with updated warm-up period.
     */
    public ControllerConfig withWarmUpPeriodMs(long warmUpMs) {
        return new ControllerConfig(cpuScaleUpThreshold,
                                    cpuScaleDownThreshold,
                                    callRateScaleUpThreshold,
                                    evaluationIntervalMs,
                                    warmUpMs,
                                    sliceCooldownMs,
                                    scalingConfig);
    }

    /**
     * Returns a copy with updated slice cooldown period.
     */
    public ControllerConfig withSliceCooldownMs(long cooldownMs) {
        return new ControllerConfig(cpuScaleUpThreshold,
                                    cpuScaleDownThreshold,
                                    callRateScaleUpThreshold,
                                    evaluationIntervalMs,
                                    warmUpPeriodMs,
                                    cooldownMs,
                                    scalingConfig);
    }

    /**
     * Returns a copy with updated scaling config.
     */
    public ControllerConfig withScalingConfig(ScalingConfig newScalingConfig) {
        return new ControllerConfig(cpuScaleUpThreshold,
                                    cpuScaleDownThreshold,
                                    callRateScaleUpThreshold,
                                    evaluationIntervalMs,
                                    warmUpPeriodMs,
                                    sliceCooldownMs,
                                    newScalingConfig);
    }

    /**
     * Creates configuration for Forge simulation environment.
     * Uses ForgeDefaults scaling config which disables CPU-based scaling.
     *
     * <p>This is a semantic factory variant (not following {@code typeName()} pattern)
     * because it represents a named configuration profile rather than generic construction.
     * See also: {@link #defaults()}, {@link #controllerConfig(double, double, double, long)}.
     */
    public static ControllerConfig forgeDefaults() {
        return DEFAULT.withScalingConfig(ScalingConfig.forgeDefaults());
    }

    /**
     * Converts to JSON.
     */
    public String toJson() {
        return "{\"cpuScaleUpThreshold\":" + cpuScaleUpThreshold + ",\"cpuScaleDownThreshold\":" + cpuScaleDownThreshold
               + ",\"callRateScaleUpThreshold\":" + callRateScaleUpThreshold + ",\"evaluationIntervalMs\":" + evaluationIntervalMs
               + ",\"warmUpPeriodMs\":" + warmUpPeriodMs + ",\"sliceCooldownMs\":" + sliceCooldownMs
               + ",\"scalingConfig\":" + scalingConfigToJson() + "}";
    }

    private String scalingConfigToJson() {
        var weightsJson = new StringBuilder("{");
        var first = true;
        for (var entry : scalingConfig.weights()
                                      .entrySet()) {
            if (!first) {
                weightsJson.append(",");
            }
            weightsJson.append("\"")
                       .append(entry.getKey()
                                    .name())
                       .append("\":")
                       .append(entry.getValue());
            first = false;
        }
        weightsJson.append("}");
        return "{\"windowSize\":" + scalingConfig.windowSize() + ",\"evaluationIntervalMs\":" + scalingConfig.evaluationIntervalMs()
               + ",\"scaleUpThreshold\":" + scalingConfig.scaleUpThreshold() + ",\"scaleDownThreshold\":" + scalingConfig.scaleDownThreshold()
               + ",\"errorRateBlockThreshold\":" + scalingConfig.errorRateBlockThreshold() + ",\"weights\":" + weightsJson
               + "}";
    }
}
