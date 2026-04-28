// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.serialization.Codec;


@Codec public record PerSliceMetrics(Artifact artifact,
                                     long activeInvocations,
                                     double p95LatencyMs,
                                     double errorRate,
                                     long totalCalls) {
    public static PerSliceMetrics perSliceMetrics(Artifact artifact,
                                                  long activeInvocations,
                                                  double p95LatencyMs,
                                                  double errorRate,
                                                  long totalCalls) {
        return new PerSliceMetrics(artifact, activeInvocations, p95LatencyMs, errorRate, totalCalls);
    }
}
