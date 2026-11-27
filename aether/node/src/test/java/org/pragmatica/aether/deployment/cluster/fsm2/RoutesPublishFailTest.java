// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.deployment.cluster.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.RouteFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// The declared-route table's PUBLISH (W2) and FAIL (W3) writers (#3389 A2 part 2), each driven through the harness in production order: the
/// node's plain Put is committed, the FSM reacts, the leader transaction is applied; a fatal FAILED row is committed, the FSM settles it.
class RoutesPublishFailTest {
    private static final String ATTEMPT_1 = "attempt-1";
    private static final String ATTEMPT_2 = "attempt-2";

    private static FsmHarness declaredApp() {
        var h = leader(DeploymentAtomicity.BEST_EFFORT, 0.5, CORE_1);

        h.activate();
        publish(h, blueprintOf(ATTEMPT_1, LIST, GET_ONE));

        return h;
    }

    private static void failFatally(FsmHarness h) {
        var node = node(h, CORE_1, 11);

        node.writes(ORDERS, SliceObservedState.LOADING).fails(ORDERS, true);
        h.drain();
    }

    /// One transient failure of CORE_1's instance; `unloaded` also lets the node follow the UNLOAD that the failure writes.
    private static void transientFailure(FsmHarness h, NodeSim node, boolean unloaded) {
        h.advance(30_000L);
        node.writes(ORDERS, SliceObservedState.LOADING).fails(ORDERS, false);
        h.drain();
        if (unloaded) {
            node.follows(ORDERS);
            h.drain();
        }
    }

    private static Option<ArtifactJudgmentValue> judgment(FsmHarness h) {
        return h.store.get(new ArtifactJudgmentKey(ORDERS))
                      .filter(ArtifactJudgmentValue.class::isInstance)
                      .map(ArtifactJudgmentValue.class::cast);
    }

    private static boolean carriesRows(ClusterEffect.Submit submit) {
        return submit.transaction()
                     .mutations()
                     .stream()
                     .anyMatch(mutation -> mutation.key().equals(key(LIST)));
    }

    // -- W2: PUBLISH ------------------------------------------------------------------------------------------------
    /// Red when: the leader does not flip a Declared row on the node's committed routes Put (no `Routes` publication in the diff, or no event
    /// hook on the committed put): the rows stay Declared and every request for a served route answers 503.
    @Test
    void publish_aNodesRoutesFlipTheDeclaredRowsToPublished() {
        var h = declaredApp();

        assertThat(committed(h, LIST)).as("control: declared before any node publishes").isEqualTo(Option.some(declared(ATTEMPT_1)));
        publishRoutes(h, CORE_1);
        assertThat(committed(h, LIST)).isEqualTo(Option.some(published(ATTEMPT_1)));
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.some(published(ATTEMPT_1)));
    }

    /// Red when: the flip's `expected` is not the committed Declared row, or the sweep flips from lingering evidence: a flip derived for an
    /// earlier attemptId lands on a row that has since been reset for a later attempt. The Put's evidence is gone (the node unloaded) and the row
    /// belongs to attempt-2 when the held flip is applied: it must be refused, and no later pass may flip the row.
    @Test
    void publish_aFlipDerivedForAStaleAttemptDoesNotFlipTheRow() {
        var h = declaredApp();

        h.holdKinds = Set.of(SubmitKind.ROUTES);
        h.clearLog();
        publishRoutes(h, CORE_1);
        var flips = h.submits(SubmitKind.ROUTES).stream().filter(RoutesPublishFailTest::carriesRows).toList();

        assertThat(flips).as("the Put drafts the flip").hasSize(1);
        h.seedRemove(new NodeRoutesKey(CORE_1, ORDERS));
        h.seed(key(LIST), declared(ATTEMPT_2));
        h.drain();
        var settlement = h.applyHeld(flips.getFirst().correlationId());

        assertThat(settlement).as("expected Declared at attempt-1, committed Declared at attempt-2").isInstanceOf(Settlement.Refused.class);
        assertThat(committed(h, LIST)).isEqualTo(Option.some(declared(ATTEMPT_2)));
        h.holdKinds = Set.of();
        h.advance(h.settings.submissionExpiry() + 2 * h.settings.timing().tick());
        assertThat(committed(h, LIST)).as("no evidence, no flip").isEqualTo(Option.some(declared(ATTEMPT_2)));
    }

    /// Red when: a publication overwrites a Failed row: a late PUBLISHED must never undo FAILED (the flip may only expect Declared).
    @Test
    void publish_neverOverwritesAFailedRow() {
        var h = declaredApp();

        h.seed(key(LIST), failed(ATTEMPT_1, "boom"));
        h.drain();
        publishRoutes(h, CORE_1);
        assertThat(committed(h, LIST)).isEqualTo(Option.some(failed(ATTEMPT_1, "boom")));
        assertThat(committed(h, GET_ONE)).as("the row that is still Declared does flip").isEqualTo(Option.some(published(ATTEMPT_1)));
    }

    // -- W3: FAIL ---------------------------------------------------------------------------------------------------
    /// Red when: the Fatal FAILURE transaction does not carry the Declared rows' Failed mutations (no `Routes.fail` in `Failures.settle`): the
    /// judgment commits and the rows stay Declared. One transaction holds the judgment and every row.
    @Test
    void fail_theFatalFailureTransactionFailsTheDeclaredRows() {
        var h = declaredApp();

        failFatally(h);
        assertThat(judgment(h).map(ArtifactJudgmentValue::permanentFailure)).as("control: the judgment is permanent").isEqualTo(Option.some(true));
        assertThat(committed(h, LIST)).isEqualTo(Option.some(failed(ATTEMPT_1, "boom")));
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.some(failed(ATTEMPT_1, "boom")));
        var carrying = h.submits(SubmitKind.FAILURE).stream().filter(RoutesPublishFailTest::carriesRows).toList();

        assertThat(carrying).as("one FAILURE transaction").hasSize(1);
        assertThat(carrying.getFirst().transaction().mutations().stream().map(mutation -> (AetherKey) mutation.key()).toList())
                  .as("the judgment and both rows ride together")
                  .contains(new ArtifactJudgmentKey(ORDERS), key(LIST), key(GET_ONE));
    }

    /// Red when: a Published row is left out of the Failed set (only Declared is failed): the served-then-failed deploy keeps answering 503.
    @Test
    void fail_aPublishedRowFailsToo() {
        var h = declaredApp();

        publishRoutes(h, CORE_1);
        assertThat(committed(h, LIST)).as("control: published").isEqualTo(Option.some(published(ATTEMPT_1)));
        failFatally(h);
        assertThat(committed(h, LIST)).isEqualTo(Option.some(failed(ATTEMPT_1, "boom")));
    }

    /// Red when: the rows are failed in a transaction of their own: a refused FAILURE transaction then leaves the rows Failed (or the judgment
    /// committed without them). The held transaction is refused because a row moved under it; neither the judgment nor any row may change.
    @Test
    void fail_aRefusedFailureTransactionLeavesTheRowsAndTheJudgmentUnchanged() {
        var h = declaredApp();

        h.holdKinds = Set.of(SubmitKind.FAILURE);
        failFatally(h);
        var failure = h.submits(SubmitKind.FAILURE).stream().filter(RoutesPublishFailTest::carriesRows).toList();

        assertThat(failure).as("the failure drafts the rows").hasSize(1);
        h.seed(key(LIST), published(ATTEMPT_1));
        h.drain();
        var settlement = h.applyHeld(failure.getFirst().correlationId());

        assertThat(settlement).isInstanceOf(Settlement.Refused.class);
        assertThat(committed(h, LIST)).isEqualTo(Option.some(published(ATTEMPT_1)));
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.some(declared(ATTEMPT_1)));
        assertThat(judgment(h)).as("the judgment did not commit without the rows").isEqualTo(Option.none());
    }

    /// Red when: only `onFailed`'s fatal branch fails the rows: the sixth transient report exhausts the retries with the apply outstanding,
    /// `Failures.exhausted` settles the artifact, and the rows stay Declared although the judgment is permanent.
    @Test
    void fail_anExhaustedApplyFailsTheDeclaredRowsToo() {
        var h = declaredApp();
        var node = node(h, CORE_1, 11);

        for (var i = 0; i < 5; i++) {
            transientFailure(h, node, true);
        }

        assertThat(judgment(h).map(ArtifactJudgmentValue::permanentFailure)).as("control: five reports do not exhaust").isEqualTo(Option.some(false));
        assertThat(committed(h, LIST)).as("control: still declared").isEqualTo(Option.some(declared(ATTEMPT_1)));
        transientFailure(h, node, false);
        assertThat(judgment(h).map(ArtifactJudgmentValue::permanentFailure)).as("control: report 6 exhausts").isEqualTo(Option.some(true));
        assertThat(committed(h, LIST)).isEqualTo(Option.some(failed(ATTEMPT_1, "boom")));
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.some(failed(ATTEMPT_1, "boom")));
    }

    /// Red when: the exhaustion's rows are failed in a transaction of their own (or outside the FAILURE transaction): the refusal then leaves the
    /// rows Failed, or the permanent mark committed without them. The sixth report's transaction is refused because a row moved under it.
    @Test
    void fail_aRefusedExhaustionTransactionLeavesTheRowsAndTheJudgmentUnchanged() {
        var h = declaredApp();
        var node = node(h, CORE_1, 11);

        for (var i = 0; i < 5; i++) {
            transientFailure(h, node, true);
        }

        h.holdKinds = Set.of(SubmitKind.FAILURE);
        transientFailure(h, node, false);
        var exhaustion = h.submits(SubmitKind.FAILURE).stream().filter(RoutesPublishFailTest::carriesRows).toList();

        assertThat(exhaustion).as("the exhaustion drafts the rows").hasSize(1);
        h.seed(key(LIST), published(ATTEMPT_1));
        h.drain();
        var settlement = h.applyHeld(exhaustion.getFirst().correlationId());

        assertThat(settlement).isInstanceOf(Settlement.Refused.class);
        assertThat(committed(h, LIST)).isEqualTo(Option.some(published(ATTEMPT_1)));
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.some(declared(ATTEMPT_1)));
        assertThat(judgment(h).map(ArtifactJudgmentValue::permanentFailure)).as("no Fatal judgment is recorded").isEqualTo(Option.some(false));
    }
}
