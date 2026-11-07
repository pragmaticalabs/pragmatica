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
        assertThat(resumed.reason()).isEmpty();
        assertThat(resumed.wasEscalated()).as("the incident is still open").isTrue();
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

    @Test
    void abandoned_isFailedWithTheCauseInDetail_andOnlyThatIsAbandoned() {
        var abandoned = fresh().abandoned("operator gave up");

        assertThat(abandoned.state()).isEqualTo(WorkflowState.FAILED);
        assertThat(abandoned.detail()).isEqualTo("abandoned:operator gave up");
        assertThat(abandoned.isAbandoned()).isTrue();
        assertThat(abandoned.releasesClaims()).isTrue();
        assertThat(fresh().in(WorkflowState.FAILED, "abandoned").isAbandoned()).isFalse();
        assertThat(fresh().in(WorkflowState.DONE, "abandoned:x").isAbandoned()).isFalse();
    }

    /// fixD: the durable escalated mark is set by the transition into ESCALATED and kept by every later transition; a run that never
    /// escalated carries no mark and its detail is untouched.
    @Test
    void escalatedMark_isSetOnEscalation_andKeptThroughResumeIssueAdvanceAndEveryTerminal() {
        var escalated = fresh().in(WorkflowState.ESCALATED, "incident");
        var resumed = escalated.resumed(9_000L);

        assertThat(fresh().wasEscalated()).isFalse();
        assertThat(fresh().in(WorkflowState.FAILED, "why").detail()).as("an unmarked run's detail is verbatim").isEqualTo("why");
        assertThat(escalated.wasEscalated()).isTrue();
        assertThat(escalated.reason()).isEqualTo("incident");
        assertThat(resumed.issued("post", 5L).wasEscalated()).isTrue();
        assertThat(resumed.issued("post", 5L).reason()).isEqualTo("post");
        assertThat(resumed.advanced().wasEscalated()).isTrue();
        assertThat(resumed.in(WorkflowState.DONE, "ok").wasEscalated()).isTrue();
        assertThat(resumed.in(WorkflowState.FAILED, "no").wasEscalated()).isTrue();
        assertThat(escalated.abandoned("gave up").wasEscalated()).isTrue();
        assertThat(escalated.in(WorkflowState.ESCALATED, "again").reason()).as("one mark, never doubled").isEqualTo("again");
    }

    /// fixD: the mark composes with the abandoned prefix: the abandon is read through the mark, and both survive in one detail.
    @Test
    void escalatedMark_composesWithTheAbandonedPrefix_inBothDirections() {
        var abandonedAfterEscalation = fresh().in(WorkflowState.ESCALATED, "incident").abandoned("gave up");

        assertThat(abandonedAfterEscalation.isAbandoned()).isTrue();
        assertThat(abandonedAfterEscalation.wasEscalated()).isTrue();
        assertThat(abandonedAfterEscalation.reason()).isEqualTo("abandoned:gave up");
        assertThat(abandonedAfterEscalation.detail()).isEqualTo(WorkflowRunValue.ESCALATED_MARK + "abandoned:gave up");
        assertThat(fresh().abandoned("x").wasEscalated()).isFalse();
        assertThat(fresh().in(WorkflowState.DONE, "abandoned:x").isAbandoned()).isFalse();
    }
}
