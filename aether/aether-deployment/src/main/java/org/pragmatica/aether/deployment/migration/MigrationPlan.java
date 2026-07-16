// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.migration;

import java.util.List;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record MigrationPlan(MigrationRequest request,
                            List<MigrationStep> steps,
                            List<MigrationStep> rollbackSteps,
                            int sourceNodeCount) {
    public MigrationPlan {
        steps = List.copyOf(steps);
        rollbackSteps = List.copyOf(rollbackSteps);
    }

    public static Result<MigrationPlan> migrationPlan(MigrationRequest request,
                                                      List<MigrationStep> steps,
                                                      List<MigrationStep> rollbackSteps,
                                                      int sourceNodeCount) {
        return success(new MigrationPlan(request, steps, rollbackSteps, sourceNodeCount));
    }
}
