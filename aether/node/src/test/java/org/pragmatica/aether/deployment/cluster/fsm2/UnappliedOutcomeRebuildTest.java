// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.lang.Option;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.blueprintId;
import static org.pragmatica.aether.deployment.cluster.fsm2.FsmHarness.harness;

/// FSM replacement plan 3.2 delta (b): a restore carries no apply record (R-4.1.3, NT-84), so an rc4 backup taken mid-deployment restores an
/// IN_PROGRESS outcome that no terminal path of FSM2 would ever close. `Rebuild` closes it FAILED for its own attempt, cause
/// `restored-without-apply`, and announces it once (`outcome-closed-unapplied`, INFO). An outcome an apply record backs, and a terminal
/// outcome, are left to the paths that own them.
class UnappliedOutcomeRebuildTest {
    private static final BlueprintId APP = blueprintId("org.example:app:1.0.0");
    private static final BlueprintId OTHER = blueprintId("org.example:other:1.0.0");
    private static final String ATTEMPT = "attempt-1";

    /// Red when `Rebuild` does not close the outcome (it stays IN_PROGRESS for ever) or closes it for another attempt or version.
    @Test
    void anInProgressOutcomeWithNoApplyRecord_isClosedFailed_atRebuild_andAnnounced() {
        var h = harness();

        h.seed(new DeploymentOutcomeKey(APP), DeploymentOutcomeValue.inProgress(h.wallMs, 3L, ATTEMPT));
        h.activate();

        assertThat(outcome(h, APP)).as("closed FAILED, the same attempt, the successor version")
                                   .isEqualTo(Option.some(List.of(DeploymentOutcomeStatus.FAILED,
                                                                  Outcomes.UNAPPLIED_CAUSE,
                                                                  ATTEMPT,
                                                                  4L)));
        assertThat(h.warns(Outcomes.OUTCOME_CLOSED_UNAPPLIED)).as("announced once, the blueprint as subject")
                                                              .singleElement()
                                                              .satisfies(warn -> assertThat(warn.subject()).isEqualTo(APP.asString()));
    }

    /// Red when the rule ignores the apply record: an open apply's outcome is the apply's to close.
    @Test
    void anInProgressOutcomeWithAnApplyRecord_isLeftOpen() {
        var h = harness();

        h.seed(new DeploymentOutcomeKey(APP), DeploymentOutcomeValue.inProgress(h.wallMs, 1L, ATTEMPT));
        h.seed(new DeploymentApplyKey(APP), new DeploymentApplyValue(ATTEMPT, Option.none(), h.wallMs));
        h.activate();

        assertThat(outcome(h, APP).map(List::getFirst)).isEqualTo(Option.some(DeploymentOutcomeStatus.IN_PROGRESS));
        assertThat(h.warns(Outcomes.OUTCOME_CLOSED_UNAPPLIED)).isEmpty();
    }

    /// Red when the rule ignores the status: a terminal outcome without an apply record is the normal end of a deployment.
    @Test
    void aTerminalOutcomeWithNoApplyRecord_isLeftAlone() {
        var h = harness();

        h.seed(new DeploymentOutcomeKey(APP), DeploymentOutcomeValue.succeeded(h.wallMs, 2L, ATTEMPT));
        h.activate();

        assertThat(outcome(h, APP)).isEqualTo(Option.some(List.of(DeploymentOutcomeStatus.SUCCEEDED, "", ATTEMPT, 2L)));
        assertThat(h.warns(Outcomes.OUTCOME_CLOSED_UNAPPLIED)).isEmpty();
    }

    /// Every unapplied outcome is closed, in blueprint-id order (a deterministic `Rebuild`).
    @Test
    void severalUnappliedOutcomes_areAllClosed_inIdOrder() {
        var h = harness();

        h.seed(new DeploymentOutcomeKey(OTHER), DeploymentOutcomeValue.inProgress(h.wallMs, 1L, ATTEMPT));
        h.seed(new DeploymentOutcomeKey(APP), DeploymentOutcomeValue.inProgress(h.wallMs, 1L, ATTEMPT));
        h.activate();

        assertThat(outcome(h, APP).map(List::getFirst)).isEqualTo(Option.some(DeploymentOutcomeStatus.FAILED));
        assertThat(outcome(h, OTHER).map(List::getFirst)).isEqualTo(Option.some(DeploymentOutcomeStatus.FAILED));
        assertThat(h.warns(Outcomes.OUTCOME_CLOSED_UNAPPLIED)
                    .stream()
                    .map(ClusterEffect.Warn::subject)
                    .toList()).containsExactly(APP.asString(), OTHER.asString());
    }

    private static Option<List<Object>> outcome(FsmHarness h, BlueprintId id) {
        return h.store.get(new DeploymentOutcomeKey(id))
                      .map(DeploymentOutcomeValue.class::cast)
                      .map(value -> List.of(value.status(), value.cause(), value.attemptId(), value.outcomeVersion()));
    }
}
