package org.pragmatica.aether.ttm.model;

public sealed interface ScalingRecommendation {
    enum NoAction implements ScalingRecommendation {
        STABLE,
        LOW_CONFIDENCE,
        INSUFFICIENT_DATA
    }

    record PreemptiveScaleUp(float predictedCpuPeak, float predictedLatency, int suggestedInstances) implements ScalingRecommendation{}

    record PreemptiveScaleDown(float predictedCpuTrough, int suggestedInstances) implements ScalingRecommendation{}

    record AdjustThresholds(double newCpuScaleUpThreshold, double newCpuScaleDownThreshold) implements ScalingRecommendation{}
}
