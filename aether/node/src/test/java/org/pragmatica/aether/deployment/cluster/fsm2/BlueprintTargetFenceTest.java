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
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.assertj.core.api.Assertions.assertThat;


/// CF-5 and CF-6 of the FSM replacement plan (#2759): the target a publish declares is fenced on the blueprint that owns it.
///
/// - **CF-5, blueprint publish then delete, per operation:** the fence is a `ReadWitness` on the owner's `AppBlueprintKey` that
///   every target put carries. (a) A put that would commit after the blueprint's delete, or after a republish, is refused by the
///   applier, in either commit order and across a leader change (D.9: per-origin FIFO (#2629) does not order a put sent in one
///   term against a delete handled in another). (b) A target that committed BEFORE the delete is removed by the delete's removal
///   batch only if that batch commits. A removal batch lost with its leader's term leaves an orphan target: the witness does not
///   cover it. That residual is pinned by `tripwire_removalBatchLostWithTheTerm_orphanTargetSurvivesTheRebuild` and closed by CF-8
///   (`newLeader_orphanTarget_removed`, disabled until the orphan-target reconcile step exists).
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
        assertThat(h.store.get(new AppBlueprintKey(blueprintId(APP))).isPresent()).as("control: the delete committed")
                  .isFalse();
        var late = h.applyHeld(put.correlationId());

        h.settle(put, late);
        h.drain();
        h.advance(h.settings.timing().awaitedTarget() + 60_000L);
        assertThat(late).as("the late put is refused by the applier")
                  .isInstanceOf(ClusterInput.Settlement.Refused.class);
        assertThat(h.store.get(ORDERS_TARGET)).as("no target of the deleted blueprint survives")
                  .isEqualTo(Option.none());
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
        h.settle(put,
                 h.applyHeld(put.correlationId()));
        h.drain();
        assertThat(committedTargetVersion(h)).as("control: the target committed first")
                  .isEqualTo(Option.some(ORDERS.version()));
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

    /// The target committed, the delete committed, and the leader's removal batch was lost with its term: a new leader rebuilds
    /// from a snapshot that holds a target whose owner blueprint is absent, and sees no remove event to act on.
    private static FsmHarness orphanedByALostRemovalBatch() {
        var h = active();

        publish(h, app(ATTEMPT, ORDERS));
        assertThat(committedTargetVersion(h)).as("control: the target committed")
                  .isEqualTo(Option.some(ORDERS.version()));
        h.policy = FsmHarness.SettlePolicy.HOLD;
        delete(h);
        h.held.clear();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.commitLeader(CORE_2, 2L);
        h.drain();
        h.deliver(new ClusterInput.Deactivate());
        h.commitLeader(FsmHarness.SELF, 3L);
        h.drain();
        h.activate();
        assertThat(h.isActive()).as("control: the new term rebuilt").isTrue();
        h.advance(h.settings.timing().tick() * 3);

        return h;
    }

    // CF-8 newLeader_orphanTarget_removed (G2 P2; the CF-5 residual): the witness fences a put against a delete, not a removal batch
    // lost with its leader's term. The new leader removes a committed target whose owner blueprint is absent from its mirror.
    // Red when: no reconcile step removes a target whose owner blueprint is absent (the removal batch of the delete was lost).
    @Test
    void newLeader_orphanTarget_removed() {
        assertThat(orphanedByALostRemovalBatch().store.get(ORDERS_TARGET)).isEqualTo(Option.none());
    }

    /// A committed target whose owner blueprint is absent, observed once by the new leader's rebuild (the removal batch was lost).
    private static FsmHarness orphanAtRebuild() {
        var h = active();

        publish(h, app(ATTEMPT, ORDERS));
        h.policy = FsmHarness.SettlePolicy.HOLD;
        delete(h);
        h.held.clear();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.deliver(new ClusterInput.Deactivate());
        h.commitLeader(FsmHarness.SELF, 3L);
        h.drain();
        h.activate();
        assertThat(h.store.get(ORDERS_TARGET).isPresent()).as("control: the orphan is committed at the rebuild")
                  .isTrue();

        return h;
    }

    // CF-8, hysteresis (G2 6.5): an orphan is removed on its second observation, not its first
    // Red when: the first tick that observes the orphan removes it (no hysteresis), or no tick removes it.
    @Test
    void orphanTarget_isRemovedOnlyAfterTwoConsecutiveTicks() {
        var h = orphanAtRebuild();

        h.advance(h.settings.timing().tick());
        assertThat(h.store.get(ORDERS_TARGET).isPresent()).as("observed once: kept").isTrue();
        h.advance(h.settings.timing().tick() * 2);
        assertThat(h.store.get(ORDERS_TARGET)).as("observed on consecutive ticks: removed").isEqualTo(Option.none());
    }

    // CF-8, the routing guard: a rolling update that names the base keeps its target until the update is gone
    // Red when: the orphan step removes the target of a base a routing record names, or never removes it once the routing is gone.
    @Test
    void orphanTarget_isKeptWhileARoutingNamesTheBase() {
        var h = orphanAtRebuild();
        var routing = new VersionRoutingKey(ORDERS.base());

        h.seed(routing,
               new VersionRoutingValue(ORDERS.version(), ORDERS_V2.version(), 1, 1, h.wallMs));
        h.drain();
        h.advance(h.settings.timing().tick() * 6);
        assertThat(h.submits(SubmitKind.ORPHAN_TARGET)).as("no removal while the routing names the base").isEmpty();
        assertThat(h.store.get(ORDERS_TARGET).isPresent()).isTrue();
        h.seedRemove(routing);
        h.drain();
        h.advance(h.settings.timing().tick() * 6);
        assertThat(h.store.get(ORDERS_TARGET)).as("control: the routing was the only holder").isEqualTo(Option.none());
    }

    // CF-8, the declared-base guard (the restore guard's direct form): a present blueprint of another id declaring the base re-owns the slice
    // Red when: the orphan step removes the target of a base a present blueprint declares (the owner id it names is absent).
    @Test
    void orphanTarget_isKeptWhileAnotherBlueprintDeclaresTheBase() {
        var h = orphanAtRebuild();
        var other = blueprintId("org.example:app:2.0.0");

        h.seed(new AppBlueprintKey(other),
               blueprint("org.example:app:2.0.0", true, "attempt-2", slice(ORDERS, 1, 1, Set.of())));
        h.drain();
        h.advance(h.settings.timing().tick() * 6);
        assertThat(h.submits(SubmitKind.ORPHAN_TARGET)).as("no removal while a blueprint declares the base").isEmpty();
        assertThat(h.store.get(ORDERS_TARGET).isPresent()).isTrue();
        h.seedRemove(new AppBlueprintKey(other));
        h.drain();
        h.advance(h.settings.timing().tick() * 6);
        assertThat(h.store.get(ORDERS_TARGET)).as("control: the declaring blueprint was the only holder")
                  .isEqualTo(Option.none());
    }

    // CF-8, the owner guard: a target whose owner blueprint is present is not an orphan, even when that blueprint no longer declares the base
    // Red when: the orphan step treats a target of a present owner as an orphan.
    @Test
    void orphanTarget_isKeptWhileItsOwnerBlueprintIsPresent() {
        var h = orphanAtRebuild();

        h.seed(new AppBlueprintKey(blueprintId(APP)), blueprint(APP, true, ATTEMPT));
        h.drain();
        h.advance(h.settings.timing().tick() * 6);
        assertThat(h.submits(SubmitKind.ORPHAN_TARGET)).as("the owner is present: the removal of a dropped slice is not this step's")
                  .isEmpty();
        assertThat(h.store.get(ORDERS_TARGET).isPresent()).isTrue();
    }

    // CF-8, the retry: a removal that fails is derived again two ticks later, not on every tick, and lands once the writes succeed
    // Red when: a failed removal is never derived again, or is derived again on every tick (the observation does not restart).
    @Test
    void orphanTarget_failedRemoval_isRetriedEveryTwoTicks() {
        var h = orphanAtRebuild();

        h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        h.advance(h.settings.timing().tick() * 6);
        var attempts = h.submits(SubmitKind.ORPHAN_TARGET).size();

        assertThat(attempts).as("one removal per two ticks while it keeps failing").isBetween(2, 3);
        assertThat(h.store.get(ORDERS_TARGET).isPresent()).isTrue();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.held.clear();
        h.advance(h.settings.timing().tick() * 3);
        assertThat(h.store.get(ORDERS_TARGET)).as("derived again until it lands").isEqualTo(Option.none());
    }

    // CF-8, a refused removal is not resubmitted at once: the next scan derives it again (the target may have moved)
    // Red when: a refused orphan removal is resubmitted in the step that refuses it.
    @Test
    void orphanTarget_refusedRemoval_isNotResubmittedAtOnce() {
        var h = orphanAtRebuild();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.advance(h.settings.timing().tick() * 3);
        var first = h.submits(SubmitKind.ORPHAN_TARGET).getFirst();

        h.settle(first, h.refused(first));
        h.drain();
        assertThat(h.submits(SubmitKind.ORPHAN_TARGET)).as("the refusal does not resubmit").hasSize(1);
    }

    // CF-8, the removal is a guarded mutation: a target that moved since it was observed is not removed
    // Red when: the removal does not expect the observed target value.
    @Test
    void orphanTarget_removalExpectsTheObservedValue() {
        var h = orphanAtRebuild();

        h.advance(h.settings.timing().tick() * 3);
        var removal = h.submits(SubmitKind.ORPHAN_TARGET).getFirst().transaction().mutations().getFirst();

        assertThat(removal.expected().isPresent()).as("expects the observed target").isTrue();
        assertThat(removal.replacement()).isEqualTo(Option.none());
    }

    // CF-8, the restore guard (G2 6.5 rollbackRestore_differentId_targetsNotRemovedByR2): a rollback to a blueprint of another id leaves
    // the kept slice's target naming the removed id for a while; the restored blueprint re-owns the slice.
    // Red when: the orphan step removes a target whose slice a present blueprint declares.
    @Test
    void rollbackRestore_differentId_targetsNotRemovedByR2() {
        var h = active();
        var first = blueprint("org.example:app:1.0.0", false, ATTEMPT, slice(ORDERS, 1, 1, Set.of()));
        var second = blueprint("org.example:app:2.0.0", false, "attempt-2", slice(ORDERS_V2, 1, 1, Set.of()));
        var node = node(h, CORE_1, 11);

        publish(h, first);
        node.follows(ORDERS);
        h.drain();
        publish(h, second, Option.some(first));
        node.writes(ORDERS_V2, SliceObservedState.LOADING);
        h.drain();
        node.fails(ORDERS_V2, true);
        h.drain();
        h.advance(h.settings.timing().tick() * 6);
        assertThat(h.submits(SubmitKind.ROLLBACK)).as("control: the attempt rolled back").hasSize(1);
        assertThat(h.store.get(new AppBlueprintKey(blueprintId("org.example:app:2.0.0")))).as("control: the rolled-back id is gone")
                  .isEqualTo(Option.none());
        assertThat(h.store.get(ORDERS_TARGET).isPresent()).as("the restored blueprint's slice keeps its target")
                  .isTrue();
    }

    // CF-5, the fence's own arm, on the declaration and on the awaited-target re-send
    // Red when: a target put, first sent or re-sent at the awaited-target bound, carries no witness on its owner blueprint at the
    // owner's value the mirror held when it was sent.
    @Test
    void everyTargetPut_witnessesItsOwnerBlueprint() {
        var h = active();
        var value = app(ATTEMPT, ORDERS);

        h.policy = FsmHarness.SettlePolicy.HOLD;
        publish(h, value);
        var first = h.submits(SubmitKind.TARGETS).getFirst();

        h.held.clear();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.settleHeld(first, h.refused(first));
        h.advance(h.settings.timing().awaitedTarget() + 31_000L);
        assertThat(h.submits(SubmitKind.TARGETS)).as("control: the declaration's batch and the re-send at the bound")
                  .hasSize(2);
        assertThat(h.submits(SubmitKind.TARGETS)).allSatisfy(submit -> assertThat(submit.transaction().guards()).anyMatch(guard -> guard.key()
                                                                                                                                        .equals(new AppBlueprintKey(blueprintId(APP))) && guard.expected()
                                                                                                                                                                                               .equals(Option.some(value))));
    }

    // CF-5, an empty load order declares nothing: submitTargets' empty-mutations guard
    // Red when: the guard is gone, so publishing a blueprint with no slices throws on the first mutation's key (getFirst of an empty
    // list) instead of publishing nothing.
    @Test
    void publishEmptyLoadOrder_submitsNoTargetsAndThrowsNothing() {
        var h = active();

        publish(h, blueprint(APP, false, ATTEMPT));

        assertThat(h.store.get(new AppBlueprintKey(blueprintId(APP))).isPresent()).as("control: the empty blueprint committed").isTrue();
        assertThat(h.submits(SubmitKind.TARGETS)).as("no TARGETS submit for an empty load order").isEmpty();
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
        h.settle(v2,
                 h.applyHeld(v2.correlationId()));
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
        h.settle(v1,
                 h.applyHeld(v1.correlationId()));
        h.drain();
        h.settle(v2,
                 h.applyHeld(v2.correlationId()));
        h.drain();
        h.advance(h.settings.timing().awaitedTarget() + 60_000L);
        assertThat(committedTargetVersion(h)).as("the committed target matches the committed blueprint")
                  .isEqualTo(Option.some(ORDERS_V2.version()));
    }
}
