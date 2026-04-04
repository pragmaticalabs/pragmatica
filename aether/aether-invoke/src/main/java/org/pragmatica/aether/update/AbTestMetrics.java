package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.Version;

import java.util.Map;


/// Collected metrics for an A/B test across all variants.
///
///
/// Provides per-variant performance data including request counts,
/// error rates, and latency percentiles for comparison.
///
/// @param testId the A/B test identifier
/// @param variantMetrics per-variant metrics keyed by variant name
/// @param collectedAt timestamp when metrics were collected
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
