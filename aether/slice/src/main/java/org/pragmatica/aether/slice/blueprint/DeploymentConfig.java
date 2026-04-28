// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.List;


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
