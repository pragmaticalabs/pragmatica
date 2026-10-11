// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
