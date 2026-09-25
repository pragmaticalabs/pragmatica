// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

/// Counts are interval deltas; errorRate is total failed / total completed invocations.
/// Latency quantiles describe interval means, not individual requests.
public record MinuteAggregate(long minuteTimestamp,
                              double avgCpuUsage,
                              double avgHeapUsage,
                              double avgEventLoopLagMs,
                              double avgLatencyMs,
                              long totalInvocations,
                              long totalGcPauseMs,
                              double intervalMeanLatencyP50,
                              double intervalMeanLatencyP95,
                              double intervalMeanLatencyP99,
                              double errorRate,
                              int eventCount,
                              int sampleCount) {
    public static final MinuteAggregate EMPTY = new MinuteAggregate(0,
                                                                    0.0,
                                                                    0.0,
                                                                    0.0,
                                                                    0.0,
                                                                    0,
                                                                    0,
                                                                    0.0,
                                                                    0.0,
                                                                    0.0,
                                                                    0.0,
                                                                    0,
                                                                    0);

    public static MinuteAggregate minuteAggregate(long minuteTimestamp,
                                                  double avgCpuUsage,
                                                  double avgHeapUsage,
                                                  double avgEventLoopLagMs,
                                                  double avgLatencyMs,
                                                  long totalInvocations,
                                                  long totalGcPauseMs,
                                                  double intervalMeanLatencyP50,
                                                  double intervalMeanLatencyP95,
                                                  double intervalMeanLatencyP99,
                                                  double errorRate,
                                                  int eventCount,
                                                  int sampleCount) {
        return new MinuteAggregate(minuteTimestamp,
                                   avgCpuUsage,
                                   avgHeapUsage,
                                   avgEventLoopLagMs,
                                   avgLatencyMs,
                                   totalInvocations,
                                   totalGcPauseMs,
                                   intervalMeanLatencyP50,
                                   intervalMeanLatencyP95,
                                   intervalMeanLatencyP99,
                                   errorRate,
                                   eventCount,
                                   sampleCount);
    }

    public static long alignToMinute(long timestamp) {
        return (timestamp / 60_000L) * 60_000L;
    }

    public boolean hasData() {
        return sampleCount > 0;
    }

    public boolean healthy() {
        return errorRate < 0.1
               && avgHeapUsage < 0.9
               && avgEventLoopLagMs < 10.0;
    }

    public float[] toFeatureArray() {
        return new float[]{(float) avgCpuUsage, (float) avgHeapUsage, (float) avgEventLoopLagMs, (float) avgLatencyMs, (float) totalInvocations, (float) totalGcPauseMs, (float) intervalMeanLatencyP50, (float) intervalMeanLatencyP95, (float) intervalMeanLatencyP99, (float) errorRate, (float) eventCount};
    }

    public static String[] featureNames() {
        return new String[]{"cpu_usage", "heap_usage", "event_loop_lag_ms", "latency_ms", "invocations", "gc_pause_ms", "interval_mean_latency_p50", "interval_mean_latency_p95", "interval_mean_latency_p99", "error_rate", "event_count"};
    }
}
