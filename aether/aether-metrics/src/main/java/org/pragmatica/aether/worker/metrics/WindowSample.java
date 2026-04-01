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
@Codec public record WindowSample( double avgCpuUsage,
                                   double avgHeapUsage,
                                   long totalActiveInvocations,
                                   double avgP95LatencyMs,
                                   double avgErrorRate,
                                   long timestampMs) {
    /// Factory following JBCT naming convention.
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

    /// Factory with current timestamp.
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
