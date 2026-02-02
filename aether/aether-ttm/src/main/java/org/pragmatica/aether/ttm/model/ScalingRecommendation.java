package org.pragmatica.aether.ttm.model;
/**
 * Sealed interface representing scaling recommendations from TTM analysis.
 */
public sealed interface ScalingRecommendation {
    /**
     * No action required.
     */
    enum NoAction implements ScalingRecommendation {
        STABLE,
        LOW_CONFIDENCE,
        INSUFFICIENT_DATA
    }

    /**
     * Preemptive scale up before predicted load increase.
     */
    record PreemptiveScaleUp(float predictedCpuPeak, float predictedLatency, int suggestedInstances) implements ScalingRecommendation {}

    /**
     * Preemptive scale down before predicted load decrease.
     */
    record PreemptiveScaleDown(float predictedCpuTrough, int suggestedInstances) implements ScalingRecommendation {}

    /**
     * Adjust controller thresholds based on predictions.
     */
    record AdjustThresholds(double newCpuScaleUpThreshold, double newCpuScaleDownThreshold) implements ScalingRecommendation {}
}
