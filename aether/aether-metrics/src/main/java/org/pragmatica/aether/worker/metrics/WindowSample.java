// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.pragmatica.serialization.Codec;


/// A single sample from the governor's sliding window.
///
/// @param avgCpuUsage            average CPU usage in this window
/// @param avgHeapUsage           average heap usage in this window
/// @param totalActiveInvocations total in-flight invocations
/// @param avgP95LatencyMs        average P95 latency in milliseconds
/// @param avgErrorRate           average error rate
/// @param timestampMs            when this sample was taken
@Codec public record WindowSample(double avgCpuUsage,
                                  double avgHeapUsage,
                                  long totalActiveInvocations,
                                  double avgP95LatencyMs,
                                  double avgErrorRate,
                                  long timestampMs) {
    public static WindowSample windowSample(double avgCpuUsage,
                                            double avgHeapUsage,
                                            long totalActiveInvocations,
                                            double avgP95LatencyMs,
                                            double avgErrorRate,
                                            long timestampMs) {
        return new WindowSample(avgCpuUsage,
                                avgHeapUsage,
                                totalActiveInvocations,
                                avgP95LatencyMs,
                                avgErrorRate,
                                timestampMs);
    }

    public static WindowSample windowSample(double avgCpuUsage,
                                            double avgHeapUsage,
                                            long totalActiveInvocations,
                                            double avgP95LatencyMs,
                                            double avgErrorRate) {
        return new WindowSample(avgCpuUsage,
                                avgHeapUsage,
                                totalActiveInvocations,
                                avgP95LatencyMs,
                                avgErrorRate,
                                System.currentTimeMillis());
    }
}
