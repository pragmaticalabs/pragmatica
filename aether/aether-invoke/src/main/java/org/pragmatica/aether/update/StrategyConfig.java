package org.pragmatica.aether.update;

import java.util.List;


/// Strategy-specific configuration for deployment operations.
///
/// Each deployment strategy has its own configuration needs:
///   - Canary: progressive traffic stages with analysis config
///   - Blue-Green: drain timeout for old environment
///   - Rolling: optional manual approval gate
public sealed interface StrategyConfig {
    record CanaryConfig(List<CanaryStage> stages, CanaryAnalysisConfig analysisConfig) implements StrategyConfig {
        public CanaryConfig {
            stages = List.copyOf(stages);
        }
    }

    record BlueGreenConfig(long drainTimeoutMs) implements StrategyConfig{}

    record RollingConfig(boolean requireManualApproval) implements StrategyConfig{}
}
