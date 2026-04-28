// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import java.util.List;


public sealed interface StrategyConfig {
    record CanaryConfig(List<CanaryStage> stages, CanaryAnalysisConfig analysisConfig) implements StrategyConfig {
        public CanaryConfig {
            stages = List.copyOf(stages);
        }
    }

    record BlueGreenConfig(long drainTimeoutMs) implements StrategyConfig{}

    record RollingConfig(boolean requireManualApproval) implements StrategyConfig{}
}
