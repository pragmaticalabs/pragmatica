// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


public record CanaryStage(int trafficPercent, int observationMinutes) {
    private static final Cause INVALID_TRAFFIC = Causes.cause("Traffic percent must be between 1 and 100");
    private static final Cause NEGATIVE_OBSERVATION = Causes.cause("Observation minutes must be non-negative");

    public static Result<CanaryStage> canaryStage(int trafficPercent, int observationMinutes) {
        if (trafficPercent < 1 || trafficPercent > 100) {
            return INVALID_TRAFFIC.result();
        }

        if (observationMinutes < 0) {
            return NEGATIVE_OBSERVATION.result();
        }

        return Result.success(new CanaryStage(trafficPercent, observationMinutes));
    }

    public VersionRouting toRouting() {
        return new VersionRouting(trafficPercent, 100 - trafficPercent);
    }

    public static List<CanaryStage> defaultStages() {
        return List.of(new CanaryStage(1, 5),
                       new CanaryStage(5, 5),
                       new CanaryStage(25, 10),
                       new CanaryStage(50, 10),
                       new CanaryStage(100, 0));
    }
}
