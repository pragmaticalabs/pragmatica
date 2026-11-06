// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowState;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/// rc6 §4.2: the transitions of [WorkflowRunValue] as record-level facts.
class WorkflowRunValueTest {
    private static WorkflowRunValue fresh() {
        return WorkflowRunValue.admitted("wf-1", "k", Map.of("a", "b"), List.of("node:n1"), 100L, 5_000L);
    }

    @Test
    void resumed_resetsAttempts_keepsEffectsDeadlineReplacedAndIssuedAt() {
        var escalated = fresh().issued("p", 200L).issued("p", 300L).in(WorkflowState.ESCALATED, "incident");
        var resumed = escalated.resumed(9_000L);

        assertThat(escalated.attempts()).isEqualTo(2);
        assertThat(resumed.attempts()).as("fresh attempt budget").isZero();
        assertThat(resumed.effects()).as("the effect budget stays spent").isEqualTo(2);
        assertThat(resumed.deadline()).as("the new elapsed-time budget").isEqualTo(9_000L);
        assertThat(resumed.issuedAt()).as("an issued effect stays issued").isEqualTo(300L);
        assertThat(resumed.state()).isEqualTo(WorkflowState.RUNNING);
        assertThat(resumed.detail()).isEmpty();
        assertThat(resumed.stepIndex()).isEqualTo(escalated.stepIndex());
        assertThat(resumed.startedAt()).isEqualTo(escalated.startedAt());
    }

    @Test
    void epoch_isBumpedByEveryTransition_soARevertedStateIsStillADifferentValue() {
        var run = fresh();

        assertThat(run.epoch()).isZero();
        assertThat(run.issued("p", 1L).epoch()).isEqualTo(1L);
        assertThat(run.advanced().epoch()).isEqualTo(1L);
        assertThat(run.in(WorkflowState.PAUSED, "x").epoch()).isEqualTo(1L);
        assertThat(run.in(WorkflowState.PAUSED, "x").resumed(7L).epoch()).isEqualTo(2L);
        assertThat(run.in(WorkflowState.PAUSED, "x").resumed(5_000L)).as("same fields as the original except epoch")
                  .isNotEqualTo(run);
    }

    @Test
    void construction_copiesParamsAndConflictKeysDefensively() {
        var params = new HashMap<>(Map.of("a", "b"));
        var keys = new ArrayList<>(List.of("node:n1"));
        var run = new WorkflowRunValue("wf-1", "k", params, 0, 0, 0, 0L, 1L, 0L, WorkflowState.RUNNING, "", keys, 0L);

        params.put("c", "d");
        keys.add("node:n2");
        assertThat(run.params()).containsOnlyKeys("a");
        assertThat(run.conflictKeys()).containsExactly("node:n1");
        assertThatThrownBy(() -> run.params().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> run.conflictKeys().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void active_isRunningOrWaiting_onlyAndTerminalStatesReleaseClaims() {
        for (var state : WorkflowState.values()) {
            var run = fresh().in(state, "x");

            assertThat(run.active()).as(state.name()).isEqualTo(state == WorkflowState.RUNNING || state == WorkflowState.WAITING);
            assertThat(run.releasesClaims()).as(state.name()).isEqualTo(state == WorkflowState.DONE || state == WorkflowState.FAILED);
        }
    }
}
