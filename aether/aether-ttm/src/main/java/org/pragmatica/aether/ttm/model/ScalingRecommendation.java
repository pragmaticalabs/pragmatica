// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ttm.model;

public sealed interface ScalingRecommendation {
    enum NoAction implements ScalingRecommendation {
        STABLE,
        LOW_CONFIDENCE,
        INSUFFICIENT_DATA
    }

    record PreemptiveScaleUp(float predictedCpuPeak, float predictedLatency, int suggestedInstances) implements ScalingRecommendation {}

    record PreemptiveScaleDown(float predictedCpuTrough, int suggestedInstances) implements ScalingRecommendation {}

    record AdjustThresholds(double newCpuScaleUpThreshold, double newCpuScaleDownThreshold) implements ScalingRecommendation {}
}
