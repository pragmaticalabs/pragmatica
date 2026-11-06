// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.lang.Functions.Fn0;


/// rc6 §4: one kind of workflow, as the engine runs it: its `name` (the run's `kind`), its [WorkflowBudget], its pure
/// [WorkflowPlanner] and `observe`, which reads the committed state and metrics its conditions are evaluated on. `observe` is the
/// only impure part and is called once per tick per run.
public record WorkflowKind<O>(String name, WorkflowBudget budget, WorkflowPlanner<O> planner, Fn0<O> observe) {
    /// The plan for `run` at `now`, against a fresh observation.
    public WorkflowPlanner.Plan plan(WorkflowRunValue run, long now) {
        return WorkflowPlanner.plan(planner, budget, run, observe.apply(), now);
    }
}
