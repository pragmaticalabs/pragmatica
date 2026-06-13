// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ttm.model;

public record TTMForecast(long timestamp,
                          float[] predictions,
                          double confidence,
                          ScalingRecommendation recommendation) {
    public float predictedCpuUsage() {
        return predictions[FeatureIndex.CPU_USAGE];
    }

    public float predictedHeapUsage() {
        return predictions[FeatureIndex.HEAP_USAGE];
    }

    public float predictedEventLoopLagMs() {
        return predictions[FeatureIndex.EVENT_LOOP_LAG_MS];
    }

    public float predictedLatencyMs() {
        return predictions[FeatureIndex.LATENCY_MS];
    }

    public float predictedInvocations() {
        return predictions[FeatureIndex.INVOCATIONS];
    }

    public float predictedErrorRate() {
        return predictions[FeatureIndex.ERROR_RATE];
    }

    public boolean indicatesLoadIncrease(float currentCpu, float currentInvocations) {
        return predictedCpuUsage() > currentCpu * 1.2f || predictedInvocations() > currentInvocations * 1.2f;
    }

    public boolean indicatesLoadDecrease(float currentCpu, float currentInvocations) {
        return predictedCpuUsage() < currentCpu * 0.8f && predictedInvocations() < currentInvocations * 0.8f;
    }

    public boolean requiresAction() {
        return ! (recommendation instanceof ScalingRecommendation.NoAction);
    }
}
