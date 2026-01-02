package org.pragmatica.aether.controller;
/**
 * Configuration for the cluster controller.
 *
 * <p>Controls thresholds for automatic scaling decisions.
 *
 * @param cpuScaleUpThreshold CPU usage above which to scale up (0.0-1.0)
 * @param cpuScaleDownThreshold CPU usage below which to scale down (0.0-1.0)
 * @param callRateScaleUpThreshold call rate above which to scale up
 * @param evaluationIntervalMs interval between controller evaluations in milliseconds
 */
public record ControllerConfig(
 double cpuScaleUpThreshold,
 double cpuScaleDownThreshold,
 double callRateScaleUpThreshold,
 long evaluationIntervalMs) {
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
     * Factory method following JBCT naming convention.
     */
    public static ControllerConfig controllerConfig(double cpuScaleUpThreshold,
                                                    double cpuScaleDownThreshold,
                                                    double callRateScaleUpThreshold,
                                                    long evaluationIntervalMs) {
        return new ControllerConfig(cpuScaleUpThreshold,
                                    cpuScaleDownThreshold,
                                    callRateScaleUpThreshold,
                                    evaluationIntervalMs);
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
