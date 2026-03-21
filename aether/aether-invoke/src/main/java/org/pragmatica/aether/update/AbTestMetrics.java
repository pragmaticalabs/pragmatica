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
public record AbTestMetrics(String testId,
                            Map<String, VariantMetrics> variantMetrics,
                            long collectedAt) {
    /// Performance metrics for a single variant in an A/B test.
    ///
    /// @param variant the variant name
    /// @param version the version deployed for this variant
    /// @param requestCount total requests routed to this variant
    /// @param errorCount total errors from this variant
    /// @param errorRate error rate (0.0-1.0)
    /// @param p99LatencyMs p99 latency in milliseconds
    /// @param avgLatencyMs average latency in milliseconds
    public record VariantMetrics(String variant,
                                 Version version,
                                 long requestCount,
                                 long errorCount,
                                 double errorRate,
                                 long p99LatencyMs,
                                 long avgLatencyMs) {
        /// Factory method following JBCT naming convention.
        @SuppressWarnings("JBCT-VO-02")
        public static VariantMetrics variantMetrics(String variant,
                                                    Version version,
                                                    long requestCount,
                                                    long errorCount,
                                                    double errorRate,
                                                    long p99LatencyMs,
                                                    long avgLatencyMs) {
            return new VariantMetrics(variant, version, requestCount, errorCount, errorRate, p99LatencyMs, avgLatencyMs);
        }
    }

    /// Creates metrics with current timestamp.
    ///
    /// @param testId the A/B test identifier
    /// @param variantMetrics per-variant metrics
    /// @return new A/B test metrics snapshot
    public static AbTestMetrics abTestMetrics(String testId, Map<String, VariantMetrics> variantMetrics) {
        return new AbTestMetrics(testId, Map.copyOf(variantMetrics), System.currentTimeMillis());
    }
}
