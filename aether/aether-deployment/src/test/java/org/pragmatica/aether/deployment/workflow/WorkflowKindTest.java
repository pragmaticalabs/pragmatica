// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.deployment.workflow.WorkflowPlanner.Plan;
import org.pragmatica.aether.deployment.workflow.WorkflowStep.Readiness;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowState;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// rc6 §4: [WorkflowKind#plan] hands the CALLER's `now` and ONE fresh observation to the pure planner.
class WorkflowKindTest {
    private static final long DEADLINE = 1_000L;

    private final AtomicInteger observations = new AtomicInteger();

    private WorkflowKind<Boolean> kind() {
        var step = new WorkflowStep<Boolean>("only",
                                             _ -> Readiness.READY,
                                             _ -> Promise.success(Unit.unit()),
                                             observed -> observed,
                                             "done",
                                             TimeSpan.timeSpan(1).seconds());

        return new WorkflowKind<>("k",
                                  new WorkflowBudget(2, TimeSpan.timeSpan(10).seconds(), 3),
                                  run -> Option.some(step),
                                  () -> {
                                      observations.incrementAndGet();

                                      return false;
                                  });
    }

    private static WorkflowRunValue run() {
        return WorkflowRunValue.admitted("wf-1", "k", Map.of(), List.of("a"), 0L, DEADLINE);
    }

    @Test
    void plan_passesNowThrough_theDeadlineIsJudgedAtThatInstant() {
        assertThat(kind().plan(run(), DEADLINE - 1)).isInstanceOf(Plan.Issue.class);
        var atDeadline = kind().plan(run(), DEADLINE);

        assertThat(atDeadline).isInstanceOf(Plan.Commit.class);
        assertThat(((Plan.Commit) atDeadline).next().state()).isEqualTo(WorkflowState.ESCALATED);
    }

    @Test
    void plan_observesExactlyOncePerCall() {
        kind().plan(run(), 1L);
        assertThat(observations.get()).isEqualTo(1);
    }
}
