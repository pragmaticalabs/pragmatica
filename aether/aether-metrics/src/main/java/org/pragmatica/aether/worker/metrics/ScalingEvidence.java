package org.pragmatica.aether.worker.metrics;

import org.pragmatica.serialization.Codec;


/// Evidence supporting a community scaling request.
/// Collected from the governor's sliding window of follower metrics.
///
/// @param memberCount            number of community members
/// @param avgCpuUsage            average CPU usage across community
/// @param avgP95LatencyMs        average P95 latency in milliseconds
/// @param totalActiveInvocations total in-flight invocations across community
/// @param avgErrorRate           average error rate across community
/// @param windowDurationMs       sliding window duration in milliseconds
/// @param timestampMs            when evidence was collected
@Codec public record ScalingEvidence(int memberCount,
                                     double avgCpuUsage,
                                     double avgP95LatencyMs,
                                     long totalActiveInvocations,
                                     double avgErrorRate,
                                     long windowDurationMs,
                                     long timestampMs) {
    public static ScalingEvidence scalingEvidence(int memberCount,
                                                  double avgCpuUsage,
                                                  double avgP95LatencyMs,
                                                  long totalActiveInvocations,
                                                  double avgErrorRate,
                                                  long windowDurationMs,
                                                  long timestampMs) {
        return new ScalingEvidence(memberCount,
                                   avgCpuUsage,
                                   avgP95LatencyMs,
                                   totalActiveInvocations,
                                   avgErrorRate,
                                   windowDurationMs,
                                   timestampMs);
    }

    public static ScalingEvidence scalingEvidence(int memberCount,
                                                  double avgCpuUsage,
                                                  double avgP95LatencyMs,
                                                  long totalActiveInvocations,
                                                  double avgErrorRate,
                                                  long windowDurationMs) {
        return new ScalingEvidence(memberCount,
                                   avgCpuUsage,
                                   avgP95LatencyMs,
                                   totalActiveInvocations,
                                   avgErrorRate,
                                   windowDurationMs,
                                   System.currentTimeMillis());
    }
}
