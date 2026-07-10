// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.pragmatica.serialization.Codec;


@Codec
public record PerMethodMetrics(String method,
                               long activeInvocations,
                               double p95LatencyMs,
                               double errorRate,
                               long totalCalls) {
    public static PerMethodMetrics perMethodMetrics(String method,
                                                    long activeInvocations,
                                                    double p95LatencyMs,
                                                    double errorRate,
                                                    long totalCalls) {
        return new PerMethodMetrics(method, activeInvocations, p95LatencyMs, errorRate, totalCalls);
    }
}
