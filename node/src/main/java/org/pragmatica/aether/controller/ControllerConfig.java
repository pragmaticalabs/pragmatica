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
 * @param cpuScaleUpThreshold CPU usage above which to scale up (0.0-1.0)
 * @param cpuScaleDownThreshold CPU usage below which to scale down (0.0-1.0)
 * @param callRateScaleUpThreshold call rate above which to scale up (must be positive)
 * @param evaluationIntervalMs interval between controller evaluations in milliseconds (must be positive)
 */
public record ControllerConfig(double cpuScaleUpThreshold,
                               double cpuScaleDownThreshold,
                               double callRateScaleUpThreshold,
                               long evaluationIntervalMs) {
    private static final Fn1<Cause, String> INVALID_THRESHOLD = Causes.forOneValue("Invalid threshold: %s (must be between 0.0 and 1.0)");
    private static final Fn1<Cause, String> INVALID_POSITIVE = Causes.forOneValue("Invalid value: %s (must be positive)");
    private static final Cause INVALID_THRESHOLD_ORDER = Causes.cause("cpuScaleUpThreshold must be greater than cpuScaleDownThreshold");

    /**
     * Default configuration.
     */
    public static final ControllerConfig DEFAULT = new ControllerConfig(0.8, 0.2, 1000, 1000);

    /**
     * Creates default configuration.
     */
    public static ControllerConfig defaults() {
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
        return validateThreshold(cpuScaleUpThreshold, "cpuScaleUpThreshold").flatMap(_ -> validateThreshold(cpuScaleDownThreshold,
                                                                                                            "cpuScaleDownThreshold"))
                                .flatMap(_ -> validatePositive(callRateScaleUpThreshold, "callRateScaleUpThreshold"))
                                .flatMap(_ -> validatePositive(evaluationIntervalMs, "evaluationIntervalMs"))
                                .flatMap(_ -> validateThresholdOrder(cpuScaleUpThreshold, cpuScaleDownThreshold))
                                .map(_ -> new ControllerConfig(cpuScaleUpThreshold,
                                                               cpuScaleDownThreshold,
                                                               callRateScaleUpThreshold,
                                                               evaluationIntervalMs));
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

    private static Result<Double> validateThresholdOrder(double up, double down) {
        return up > down
               ? Result.success(up)
               : INVALID_THRESHOLD_ORDER.result();
    }

    /**
     * Returns a copy with updated CPU scale-up threshold.
     */
    public ControllerConfig withCpuScaleUpThreshold(double threshold) {
        return new ControllerConfig(threshold, cpuScaleDownThreshold, callRateScaleUpThreshold, evaluationIntervalMs);
    }

    /**
     * Returns a copy with updated CPU scale-down threshold.
     */
    public ControllerConfig withCpuScaleDownThreshold(double threshold) {
        return new ControllerConfig(cpuScaleUpThreshold, threshold, callRateScaleUpThreshold, evaluationIntervalMs);
    }

    /**
     * Returns a copy with updated call rate threshold.
     */
    public ControllerConfig withCallRateScaleUpThreshold(double threshold) {
        return new ControllerConfig(cpuScaleUpThreshold, cpuScaleDownThreshold, threshold, evaluationIntervalMs);
    }

    /**
     * Returns a copy with updated evaluation interval.
     */
    public ControllerConfig withEvaluationIntervalMs(long intervalMs) {
        return new ControllerConfig(cpuScaleUpThreshold, cpuScaleDownThreshold, callRateScaleUpThreshold, intervalMs);
    }

    /**
     * Converts to JSON.
     */
    public String toJson() {
        return "{\"cpuScaleUpThreshold\":" + cpuScaleUpThreshold + ",\"cpuScaleDownThreshold\":" + cpuScaleDownThreshold
               + ",\"callRateScaleUpThreshold\":" + callRateScaleUpThreshold + ",\"evaluationIntervalMs\":" + evaluationIntervalMs
               + "}";
    }
}
