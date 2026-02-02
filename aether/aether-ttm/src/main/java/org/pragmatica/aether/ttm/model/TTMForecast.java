package org.pragmatica.aether.ttm.model;
/**
 * Result of TTM forecast containing predicted metrics and confidence.
 *
 * @param timestamp      Timestamp when forecast was generated
 * @param predictions    Predicted values for each feature (same order as MinuteAggregate.featureNames())
 * @param confidence     Overall confidence score (0.0-1.0)
 * @param recommendation Derived scaling recommendation
 */
public record TTMForecast(long timestamp,
                          float[] predictions,
                          double confidence,
                          ScalingRecommendation recommendation) {
    /**
     * Get predicted CPU usage.
     */
    public float predictedCpuUsage() {
        return predictions[FeatureIndex.CPU_USAGE];
    }

    /**
     * Get predicted heap usage.
     */
    public float predictedHeapUsage() {
        return predictions[FeatureIndex.HEAP_USAGE];
    }

    /**
     * Get predicted event loop lag in milliseconds.
     */
    public float predictedEventLoopLagMs() {
        return predictions[FeatureIndex.EVENT_LOOP_LAG_MS];
    }

    /**
     * Get predicted latency in milliseconds.
     */
    public float predictedLatencyMs() {
        return predictions[FeatureIndex.LATENCY_MS];
    }

    /**
     * Get predicted invocations.
     */
    public float predictedInvocations() {
        return predictions[FeatureIndex.INVOCATIONS];
    }

    /**
     * Get predicted error rate.
     */
    public float predictedErrorRate() {
        return predictions[FeatureIndex.ERROR_RATE];
    }

    /**
     * Check if forecast indicates load increase.
     */
    public boolean indicatesLoadIncrease(float currentCpu, float currentInvocations) {
        return predictedCpuUsage() > currentCpu * 1.2f || predictedInvocations() > currentInvocations * 1.2f;
    }

    /**
     * Check if forecast indicates load decrease.
     */
    public boolean indicatesLoadDecrease(float currentCpu, float currentInvocations) {
        return predictedCpuUsage() < currentCpu * 0.8f && predictedInvocations() < currentInvocations * 0.8f;
    }

    /**
     * Check if the recommendation requires action.
     */
    public boolean requiresAction() {
        return ! ( recommendation instanceof ScalingRecommendation.NoAction);
    }
}
