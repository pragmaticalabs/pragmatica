package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.Version;

/// Results of a canary health comparison between baseline and canary versions.
///
/// Used by the canary evaluation loop to decide whether to advance, hold, or rollback.
///
/// @param canaryId the canary deployment being evaluated
/// @param baselineVersion the old (baseline) version
/// @param canaryVersion the new (canary) version
/// @param baselineMetrics metrics snapshot for baseline version
/// @param canaryMetrics metrics snapshot for canary version
/// @param verdict the health comparison verdict
/// @param collectedAt timestamp when metrics were collected
public record CanaryHealthComparison(String canaryId,
                                     Version baselineVersion,
                                     Version canaryVersion,
                                     VersionMetrics baselineMetrics,
                                     VersionMetrics canaryMetrics,
                                     Verdict verdict,
                                     long collectedAt) {
    /// Health comparison verdict.
    public enum Verdict {
        /// Both absolute and relative thresholds pass — canary is healthy.
        HEALTHY,
        /// Canary metrics exceed absolute thresholds — auto-rollback recommended.
        ABSOLUTE_BREACH,
        /// Canary metrics are significantly worse than baseline — auto-rollback recommended.
        RELATIVE_BREACH,
        /// Insufficient data to make a determination — hold current stage.
        INSUFFICIENT_DATA
    }

    /// Metrics for a single version.
    ///
    /// @param version the version these metrics are for
    /// @param requestCount total request count in evaluation window
    /// @param errorCount total error count in evaluation window
    /// @param errorRate error rate (0.0-1.0)
    /// @param p99LatencyMs p99 latency in milliseconds
    /// @param avgLatencyMs average latency in milliseconds
    public record VersionMetrics(Version version,
                                 long requestCount,
                                 long errorCount,
                                 double errorRate,
                                 long p99LatencyMs,
                                 long avgLatencyMs) {
        /// Minimum requests needed for a meaningful comparison.
        public static final long MIN_REQUESTS = 10;

        /// Whether this snapshot has enough data for evaluation.
        public boolean hasSufficientData() {
            return requestCount >= MIN_REQUESTS;
        }

        /// Whether error rate exceeds the relative threshold compared to a baseline value.
        public boolean exceedsRelativeErrorRate(double baselineErrorRate, double relativeThreshold) {
            return errorRate > baselineErrorRate * relativeThreshold;
        }

        /// Whether p99 latency exceeds the relative threshold compared to a baseline value.
        public boolean exceedsRelativeLatency(long baselineP99LatencyMs, double relativeThreshold) {
            return p99LatencyMs > baselineP99LatencyMs * relativeThreshold;
        }
    }

    /// Factory method following JBCT naming convention.
    public static CanaryHealthComparison canaryHealthComparison(String canaryId,
                                                                Version baselineVersion,
                                                                Version canaryVersion,
                                                                VersionMetrics baselineMetrics,
                                                                VersionMetrics canaryMetrics,
                                                                Verdict verdict) {
        return new CanaryHealthComparison(canaryId,
                                          baselineVersion,
                                          canaryVersion,
                                          baselineMetrics,
                                          canaryMetrics,
                                          verdict,
                                          System.currentTimeMillis());
    }

    /// Evaluates canary health against thresholds and baseline.
    public static CanaryHealthComparison evaluate(String canaryId,
                                                  Version baselineVersion,
                                                  Version canaryVersion,
                                                  VersionMetrics baselineMetrics,
                                                  VersionMetrics canaryMetrics,
                                                  HealthThresholds thresholds,
                                                  CanaryAnalysisConfig config) {
        if (!canaryMetrics.hasSufficientData()) {
            return withVerdict(canaryId,
                               baselineVersion,
                               canaryVersion,
                               baselineMetrics,
                               canaryMetrics,
                               Verdict.INSUFFICIENT_DATA);
        }
        if (breachesAbsoluteThreshold(canaryMetrics, thresholds, config)) {
            return withVerdict(canaryId,
                               baselineVersion,
                               canaryVersion,
                               baselineMetrics,
                               canaryMetrics,
                               Verdict.ABSOLUTE_BREACH);
        }
        if (breachesRelativeThreshold(baselineMetrics, canaryMetrics, config)) {
            return withVerdict(canaryId,
                               baselineVersion,
                               canaryVersion,
                               baselineMetrics,
                               canaryMetrics,
                               Verdict.RELATIVE_BREACH);
        }
        return withVerdict(canaryId, baselineVersion, canaryVersion, baselineMetrics, canaryMetrics, Verdict.HEALTHY);
    }

    /// Whether the canary should be rolled back based on this comparison.
    public boolean shouldRollback() {
        return verdict == Verdict.ABSOLUTE_BREACH || verdict == Verdict.RELATIVE_BREACH;
    }

    /// Whether there is sufficient data to make a decision.
    public boolean hasDecision() {
        return verdict != Verdict.INSUFFICIENT_DATA;
    }

    private static CanaryHealthComparison withVerdict(String canaryId,
                                                      Version baselineVersion,
                                                      Version canaryVersion,
                                                      VersionMetrics baselineMetrics,
                                                      VersionMetrics canaryMetrics,
                                                      Verdict verdict) {
        return canaryHealthComparison(canaryId, baselineVersion, canaryVersion, baselineMetrics, canaryMetrics, verdict);
    }

    private static boolean breachesAbsoluteThreshold(VersionMetrics canaryMetrics,
                                                     HealthThresholds thresholds,
                                                     CanaryAnalysisConfig config) {
        return config.mode() != CanaryAnalysisConfig.ComparisonMode.RELATIVE_ONLY && !thresholds.isHealthy(canaryMetrics.errorRate(),
                                                                                                           canaryMetrics.p99LatencyMs());
    }

    private static boolean breachesRelativeThreshold(VersionMetrics baselineMetrics,
                                                     VersionMetrics canaryMetrics,
                                                     CanaryAnalysisConfig config) {
        if (config.mode() == CanaryAnalysisConfig.ComparisonMode.ABSOLUTE_ONLY || !baselineMetrics.hasSufficientData()) {
            return false;
        }
        var relativeThreshold = 1.0 + (config.relativeThresholdPercent() / 100.0);
        return canaryMetrics.exceedsRelativeErrorRate(baselineMetrics.errorRate(), relativeThreshold) || canaryMetrics.exceedsRelativeLatency(baselineMetrics.p99LatencyMs(),
                                                                                                                                              relativeThreshold);
    }
}
