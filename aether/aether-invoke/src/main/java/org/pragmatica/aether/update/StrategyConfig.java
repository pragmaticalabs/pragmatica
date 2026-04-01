package org.pragmatica.aether.update;

import java.util.List;

/// Strategy-specific configuration for deployment operations.
///
/// Each deployment strategy has its own configuration needs:
///   - Canary: progressive traffic stages with analysis config
///   - Blue-Green: drain timeout for old environment
///   - Rolling: optional manual approval gate
public sealed interface StrategyConfig {
    /// Canary deployment configuration with progressive traffic stages.
    ///
    /// @param stages ordered list of canary stages defining traffic progression
    /// @param analysisConfig health analysis configuration for auto-progression
    record CanaryConfig(List<CanaryStage> stages, CanaryAnalysisConfig analysisConfig) implements StrategyConfig {
        public CanaryConfig {
            stages = List.copyOf(stages);
        }
    }

    /// Blue-green deployment configuration with drain timeout.
    ///
    /// @param drainTimeoutMs maximum time in milliseconds to wait for old environment drain
    record BlueGreenConfig(long drainTimeoutMs) implements StrategyConfig{}

    /// Rolling update configuration with optional manual approval.
    ///
    /// @param requireManualApproval if true, requires explicit approval before routing traffic
    record RollingConfig(boolean requireManualApproval) implements StrategyConfig{}
}
