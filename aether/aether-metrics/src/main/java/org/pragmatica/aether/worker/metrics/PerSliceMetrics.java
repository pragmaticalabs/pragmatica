package org.pragmatica.aether.worker.metrics;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.serialization.Codec;

/// Per-slice metrics within a community snapshot.
///
/// @param artifact          slice artifact
/// @param activeInvocations in-flight invocations for this slice
/// @param p95LatencyMs      P95 latency in milliseconds
/// @param errorRate         error rate (0.0-1.0)
/// @param totalCalls        total calls observed
@Codec public record PerSliceMetrics( Artifact artifact,
                                      long activeInvocations,
                                      double p95LatencyMs,
                                      double errorRate,
                                      long totalCalls) {
    /// Factory following JBCT naming convention.
    public static PerSliceMetrics perSliceMetrics(Artifact artifact,
                                                  long activeInvocations,
                                                  double p95LatencyMs,
                                                  double errorRate,
                                                  long totalCalls) {
        return new PerSliceMetrics(artifact, activeInvocations, p95LatencyMs, errorRate, totalCalls);
    }
}
