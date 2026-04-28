// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.Version;

import java.util.Map;


public record AbTestMetrics(String testId, Map<String, VariantMetrics> variantMetrics, long collectedAt) {
    public record VariantMetrics(String variant,
                                 Version version,
                                 long requestCount,
                                 long errorCount,
                                 double errorRate,
                                 long p99LatencyMs,
                                 long avgLatencyMs) {
        @SuppressWarnings("JBCT-VO-02") public static VariantMetrics variantMetrics(String variant,
                                                                                    Version version,
                                                                                    long requestCount,
                                                                                    long errorCount,
                                                                                    double errorRate,
                                                                                    long p99LatencyMs,
                                                                                    long avgLatencyMs) {
            return new VariantMetrics(variant, version, requestCount, errorCount, errorRate, p99LatencyMs, avgLatencyMs);
        }
    }

    public static AbTestMetrics abTestMetrics(String testId, Map<String, VariantMetrics> variantMetrics) {
        return new AbTestMetrics(testId, Map.copyOf(variantMetrics), System.currentTimeMillis());
    }
}
