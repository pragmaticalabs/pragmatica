package org.pragmatica.aether.slice.blueprint;

import java.util.List;


/// Deployment strategy configuration parsed from blueprint TOML.
///
/// This is the blueprint-level configuration that defines HOW an application
/// should be deployed (rolling, canary, blue-green) and the associated thresholds.
/// The runtime deployment managers in aether-invoke consume this configuration.
///
/// @param strategy the deployment strategy type
/// @param canaryStages canary stages (only used when strategy is CANARY)
/// @param maxErrorRate maximum error rate threshold (0.0-1.0)
/// @param maxLatencyMs maximum latency threshold in milliseconds
/// @param drainTimeoutMs blue-green drain timeout in milliseconds
/// @param schemaRequired whether schema migrations must complete before slice activation (default true)
@SuppressWarnings({"JBCT-VO-02", "JBCT-UTIL-02"}) public record DeploymentConfig(Strategy strategy,
                                                                                 List<CanaryStageConfig> canaryStages,
                                                                                 double maxErrorRate,
                                                                                 long maxLatencyMs,
                                                                                 long drainTimeoutMs,
                                                                                 boolean schemaRequired) {
    public enum Strategy {
        ROLLING,
        CANARY,
        BLUE_GREEN
    }

    public record CanaryStageConfig(int trafficPercent, int observationMinutes) {
        @SuppressWarnings("JBCT-VO-02") public static CanaryStageConfig canaryStageConfig(int trafficPercent,
                                                                                          int observationMinutes) {
            return new CanaryStageConfig(trafficPercent, observationMinutes);
        }
    }

    public static List<CanaryStageConfig> defaultCanaryStages() {
        return List.of(CanaryStageConfig.canaryStageConfig(1, 5),
                       CanaryStageConfig.canaryStageConfig(5, 5),
                       CanaryStageConfig.canaryStageConfig(25, 10),
                       CanaryStageConfig.canaryStageConfig(50, 10),
                       CanaryStageConfig.canaryStageConfig(100, 0));
    }

    public static final DeploymentConfig DEFAULT = deploymentConfig(Strategy.ROLLING,
                                                                    defaultCanaryStages(),
                                                                    0.01,
                                                                    500,
                                                                    300_000,
                                                                    true);

    public static DeploymentConfig deploymentConfig(Strategy strategy,
                                                    List<CanaryStageConfig> canaryStages,
                                                    double maxErrorRate,
                                                    long maxLatencyMs,
                                                    long drainTimeoutMs,
                                                    boolean schemaRequired) {
        return new DeploymentConfig(strategy,
                                    List.copyOf(canaryStages),
                                    maxErrorRate,
                                    maxLatencyMs,
                                    drainTimeoutMs,
                                    schemaRequired);
    }

    public static DeploymentConfig deploymentConfig(Strategy strategy,
                                                    List<CanaryStageConfig> canaryStages,
                                                    double maxErrorRate,
                                                    long maxLatencyMs,
                                                    long drainTimeoutMs) {
        return deploymentConfig(strategy, canaryStages, maxErrorRate, maxLatencyMs, drainTimeoutMs, true);
    }
}
