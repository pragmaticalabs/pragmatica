package org.pragmatica.aether.update;
/// Configuration for canary health analysis.
///
/// Determines how canary metrics are compared against baseline (old version)
/// or absolute thresholds.
///
/// @param mode comparison mode for health evaluation
/// @param relativeThresholdPercent maximum allowed degradation relative to baseline (e.g., 10 means 10% worse is tolerable)
public record CanaryAnalysisConfig(ComparisonMode mode, int relativeThresholdPercent) {
    /// How canary health metrics are compared.
    public enum ComparisonMode {
        /// Only compare against absolute thresholds from HealthThresholds.
        ABSOLUTE_ONLY,
        /// Only compare canary metrics relative to baseline (old version) metrics.
        RELATIVE_ONLY,
        /// Both absolute thresholds AND relative comparison must pass.
        RELATIVE_AND_ABSOLUTE
    }

    /// Default: absolute-only comparison (simplest mode).
    @SuppressWarnings("JBCT-VO-02") // Static constant uses pre-validated values
    public static final CanaryAnalysisConfig DEFAULT = new CanaryAnalysisConfig(ComparisonMode.ABSOLUTE_ONLY, 0);

    /// Factory method following JBCT naming convention.
    public static CanaryAnalysisConfig canaryAnalysisConfig(ComparisonMode mode, int relativeThresholdPercent) {
        return new CanaryAnalysisConfig(mode, relativeThresholdPercent);
    }

    /// Creates config with relative comparison at the given threshold.
    public static CanaryAnalysisConfig relativeComparison(int relativeThresholdPercent) {
        return new CanaryAnalysisConfig(ComparisonMode.RELATIVE_AND_ABSOLUTE, relativeThresholdPercent);
    }
}
