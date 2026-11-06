// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/// CF-5 and CF-6 of the FSM replacement plan (#2759): the target a publish declares is fenced on the blueprint that owns it.
///
/// - **CF-5, blueprint publish then delete:** after the delete commits, no `SliceTargetKey` owned by the deleted blueprint
///   survives, in either commit order of the target put and the delete. Mechanism: every target put carries a `ReadWitness` on
///   its owner's `AppBlueprintKey` at the value the declaration read, so a put that would commit after the blueprint is gone is
///   refused by the applier; a put that commits first is a committed target the delete's removal batch removes. This holds
///   across a leader change too (D.9): per-origin FIFO (#2629) does not order a put sent in one term against a delete handled in
///   another.
/// - **CF-6, two publishes of one id:** the last committed target matches the committed blueprint. Mechanism: the target put
///   is a mutation expecting the committed target the declaration read, and carries the blueprint witness, so the earlier
///   declaration's put cannot commit over the later one.
class BlueprintTargetFenceTest {
    private static final String APP = "org.example:app:1.0.0";
    private static final SliceTargetKey ORDERS_TARGET = new SliceTargetKey(ORDERS.base());

    private static AppBlueprintValue app(String attempt, Artifact artifact) {
        return blueprint(APP, false, attempt, slice(artifact, 1, 1, Set.of()));
    }

    private static Option<Version> committedTargetVersion(FsmHarness h) {
        return h.store.get(ORDERS_TARGET)
                      .filter(SliceTargetValue.class::isInstance)
                      .map(SliceTargetValue.class::cast)
                      .map(SliceTargetValue::currentVersion);
    }

    /// The application operator's delete: the blueprint key is removed, and the leader handles the notification.
    private static void delete(FsmHarness h) {
        h.seedRemove(new AppBlueprintKey(blueprintId(APP)));
        h.drain();
    }

    // CF-5, the put commits last (#2628's order)
    // Red when: the target put carries no witness on its owner blueprint, so a put still in flight when the delete is handled commits
    // after the blueprint is gone, and its target survives with no owner to remove it.
    @Test
    void publishThenDelete_targetPutCommitsLast_noTargetSurvives() {
        var h = active();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        publish(h, app(ATTEMPT, ORDERS));
        var put = h.submits(SubmitKind.TARGETS).getFirst();

        h.policy = FsmHarness.SettlePolicy.APPLY;
        delete(h);
        assertThat(h.store.get(new AppBlueprintKey(blueprintId(APP))).isPresent()).as("control: the delete committed").isFalse();
        var late = h.applyHeld(put.correlationId());

        h.settle(put, late);
        h.drain();
        h.advance(h.settings.timing().awaitedTarget() + 60_000L);

        assertThat(late).as("the late put is refused by the applier").isInstanceOf(ClusterInput.Settlement.Refused.class);
        assertThat(h.store.get(ORDERS_TARGET)).as("no target of the deleted blueprint survives").isEqualTo(Option.none());
    }

    // CF-5, the put commits first
    // Red when: a committed target of a deleted blueprint is not part of the delete's removal batch.
    @Test
    void publishThenDelete_targetPutCommitsFirst_targetRemovedByTheDelete() {
        var h = active();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        publish(h, app(ATTEMPT, ORDERS));
        var put = h.submits(SubmitKind.TARGETS).getFirst();

        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.settle(put, h.applyHeld(put.correlationId()));
        h.drain();
        assertThat(committedTargetVersion(h)).as("control: the target committed first").isEqualTo(Option.some(ORDERS.version()));
        delete(h);
        h.advance(60_000L);

        assertThat(h.store.get(ORDERS_TARGET)).as("the delete removed the committed target").isEqualTo(Option.none());
    }

    // CF-5, cross-origin (D.9): the put was sent in one term, the delete is handled while another node leads, the put arrives after.
    // Red when: the put's only fence is the leader record. The sender regains leadership in a new term, takes the refused put as
    // its own stale witness and resubmits it unchanged (R-5.7.2); nothing but a witness on the blueprint refuses it then.
    @Test
    void putSentInOneTerm_deleteHandledUnderAnotherLeader_putRefusedAfterTheLeaderReturns() {
        var h = active();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        publish(h, app(ATTEMPT, ORDERS));
        var put = h.submits(SubmitKind.TARGETS).getFirst();

        h.held.clear();
        h.commitLeader(CORE_2, 2L);
        h.drain();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        delete(h);
        h.commitLeader(FsmHarness.SELF, 3L);
        h.drain();
        var late = h.applyTransaction(put);

        assertThat(late).as("control: the first send is refused by the leader record of the ended term")
                        .isInstanceOf(ClusterInput.Settlement.Refused.class);
        h.settle(put, late);
        h.drain();
        h.advance(h.settings.timing().awaitedTarget() + 60_000L);

        assertThat(h.store.get(ORDERS_TARGET)).as("no target of the deleted blueprint survives the leader change")
                                              .isEqualTo(Option.none());
    }

    // CF-5, the fence's own arm
    // Red when: a target put carries no witness on its owner blueprint at the value the declaration read.
    @Test
    void everyTargetPut_witnessesItsOwnerBlueprint() {
        var h = active();
        var value = app(ATTEMPT, ORDERS);

        publish(h, value);

        assertThat(h.submits(SubmitKind.TARGETS)).as("control: the declaration's batch").isNotEmpty();
        assertThat(h.submits(SubmitKind.TARGETS)).allSatisfy(submit -> assertThat(submit.transaction().guards())
                                                                       .anyMatch(guard -> guard.key()
                                                                                               .equals(new AppBlueprintKey(blueprintId(APP)))
                                                                                          && guard.expected()
                                                                                                  .equals(Option.some(value))));
    }

    // CF-6
    // Red when: the earlier declaration's put commits over the later one, so the committed target names v1 while the committed
    // blueprint declares v2.
    @Test
    void twoPublishesOfOneId_v1CommitsLast_targetConvergesToV2() {
        var h = active();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        publish(h, app(ATTEMPT, ORDERS));
        publish(h, app("attempt-2", ORDERS_V2));
        var v1 = h.submits(SubmitKind.TARGETS).get(0);
        var v2 = h.submits(SubmitKind.TARGETS).get(1);

        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.settle(v2, h.applyHeld(v2.correlationId()));
        h.drain();
        var late = h.applyHeld(v1.correlationId());

        h.settle(v1, late);
        h.drain();
        h.advance(h.settings.timing().awaitedTarget() + 60_000L);

        assertThat(late).as("v1's put is refused").isInstanceOf(ClusterInput.Settlement.Refused.class);
        assertThat(committedTargetVersion(h)).as("the committed target matches the committed blueprint")
                                             .isEqualTo(Option.some(ORDERS_V2.version()));
    }

    // CF-6, the other order
    // Red when: v2's put, refused because v1's committed first, is never re-sent, so the target stays at v1.
    @Test
    void twoPublishesOfOneId_v1CommitsFirst_targetConvergesToV2() {
        var h = active();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        publish(h, app(ATTEMPT, ORDERS));
        publish(h, app("attempt-2", ORDERS_V2));
        var v1 = h.submits(SubmitKind.TARGETS).get(0);
        var v2 = h.submits(SubmitKind.TARGETS).get(1);

        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.settle(v1, h.applyHeld(v1.correlationId()));
        h.drain();
        h.settle(v2, h.applyHeld(v2.correlationId()));
        h.drain();
        h.advance(h.settings.timing().awaitedTarget() + 60_000L);

        assertThat(committedTargetVersion(h)).as("the committed target matches the committed blueprint")
                                             .isEqualTo(Option.some(ORDERS_V2.version()));
    }
}
