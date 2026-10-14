// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.UnresponsiveEntry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Independent verification of PR #2174 (v-2174): each test is red at ea364e8 and names the rule it holds the FSM to.
class V2174AdversarialTest {
    private static final long TOKEN_2 = 0x2222L;
    private static final String APP = "org.example:app:1.0.0";

    private static MbWorld world() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.admit(CORE_3, 0x3333L);

        return w.target(ORDERS, 1, "CORE_ONLY")
                .directive(CORE_2,
                           ActivationDirectiveValue.core())
                .instance(CORE_2,
                          ORDERS,
                          SliceGoal.ACTIVE,
                          SliceObservedState.ACTIVE,
                          gen(1),
                          TOKEN_2)
                .routes(CORE_2, ORDERS);
    }

    private static Option<DepartureState> recordState(MbWorld w, NodeId node) {
        return w.recordOf(node)
                .map(NodeDepartureValue::state);
    }

    // -- prior item 3: obsolete() uses >= -----------------------------------------------------------------------------
    /// R-4.3.0, "record unchanged -> resubmit the identical write". A node healed once carries ADMITTED(e1) and departs at
    /// that same e1, so its C17 expects ADMITTED(e1). Red when: the failed C17's re-read finds the record it expected,
    /// calls the departure obsolete (`e1 >= e1`), drops it, and the node is never recorded DEPARTED.
    @Test
    void equalEpochDeparture_whoseSubmissionFailed_isResubmitted_notDroppedAsObsolete() {
        var w = world();

        w.record(CORE_2, DepartureState.ADMITTED, 1L, TOKEN_2, null).activate();
        w.h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        assertThat(w.h.submits(SubmitKind.DEPARTURE)).as("the C17 was submitted (and failed)").hasSize(1);
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.advance(65_000L);
        assertThat(recordState(w, CORE_2)).as("the departure is recorded, not dropped as obsolete")
                  .isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(w.h.model().isDeparted(CORE_2)).isTrue();
    }

    /// The same defect on the refusal arm: the node rewrote its own route key under the C17's route removal, so the C17 is
    /// refused as a whole while its record is unchanged. Red when: the refusal is resolved as obsolete and nothing is
    /// resubmitted.
    @Test
    void equalEpochDeparture_refusedBecauseTheNodesRouteKeyMoved_isRederived() {
        var w = world();

        w.record(CORE_2, DepartureState.ADMITTED, 1L, TOKEN_2, null).activate();
        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        var c17 = w.h.submits(SubmitKind.DEPARTURE).getFirst();

        w.h.seed(new NodeRoutesKey(CORE_2, ORDERS),
                 new NodeRoutesValue(List.of(), Epoch.epoch(7L, 0L, 0L)));
        w.h.drain();
        var verdict = w.h.applyHeld(c17.correlationId());

        assertThat(verdict).as("the applier refuses the C17: its route removal expected the old value")
                  .isInstanceOf(Settlement.Refused.class);
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.settleHeld(c17, verdict);
        w.h.advance(65_000L);
        assertThat(recordState(w, CORE_2)).as("re-derived against the moved route and committed")
                  .isEqualTo(Option.some(DepartureState.DEPARTED));
    }

    /// The author's rebuttal scenario for item 3: a J3 admission at e2 and a C17 naming e2 race, both expecting the same
    /// DEPARTED(e1). Membership removed the node AFTER admitting it at e2 (C17 takes e2 from that admission), so the
    /// departure is genuine; the ADMITTED(e2) that beat it is the admission of the very incarnation that departed, not a
    /// later one. Red when: the refused C17 is resolved as obsolete (C11c on the ADMITTED put, or `e2 >= e2` on the
    /// refusal) and the node that membership removed is never recorded DEPARTED.
    @Test
    void j3AdmissionAtE2_thenC17AtE2_refusedByTheAdmission_isStillRecorded() {
        var w = world();

        w.record(CORE_2, DepartureState.DEPARTED, 1L, TOKEN_2, DepartureCause.NODE_REMOVED).activate();
        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admit(CORE_2, TOKEN_2)));
        var j3 = w.h.submits(SubmitKind.ADMISSION).getLast();

        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        var c17 = w.h.submits(SubmitKind.DEPARTURE).getLast();

        assertThat(w.h.applyHeld(j3.correlationId())).isInstanceOf(Settlement.Accepted.class);
        var verdict = w.h.applyHeld(c17.correlationId());

        assertThat(verdict).as("the C17 expected DEPARTED(1) and meets ADMITTED(2)")
                  .isInstanceOf(Settlement.Refused.class);
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.held.clear();
        w.h.settle(j3, new Settlement.Accepted());
        w.h.settleHeld(c17, verdict);
        w.h.advance(65_000L);
        assertThat(recordState(w, CORE_2)).as("membership removed CORE_2 after admitting it at e2: the departure stands")
                  .isEqualTo(Option.some(DepartureState.DEPARTED));
    }

    // -- prior item 4: retryPending twice per tick ---------------------------------------------------------------------
    /// J9: "re-evaluated on the next membership input for n, on C11c, and on every 30 s tick". Red when: the unmatched join
    /// is re-evaluated (and `join-unmatched` re-warned) more than once per tick: by the tick itself and again by the
    /// reconcile the tick queues.
    @Test
    void unmatchedJoin_isReevaluatedOncePerTick() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.admit(CORE_3, 0x3333L);
        w.directive(CORE_2,
                    ActivationDirectiveValue.core())
         .record(CORE_2, DepartureState.DEPARTED, 5L, TOKEN_2, DepartureCause.NODE_REMOVED)
         .activate();
        w.unforeseen(CORE_2);
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2)));
        assertThat(w.h.warns("join-unmatched")).as("J9 matched").isNotEmpty();
        w.h.advance(5_000L);
        w.h.clearLog();
        w.h.advance(300_000L);
        assertThat(w.h.warns("join-unmatched").size()).as("10 ticks: at most one re-evaluation each")
                  .isLessThanOrEqualTo(10);
    }

    // -- prior item 2: Rebuild while Active ----------------------------------------------------------------------------
    /// A `Rebuild` while Active keeps the incarnation. A TICK that already fired (queued behind the Rebuild) still carries
    /// the current incarnation, so it passes the stale-input check and re-arms a second TICK beside the one the rebuild
    /// armed. Red when: two TICK timers are scheduled afterwards (two tick chains for the rest of the leadership).
    @Test
    void rebuildWhileActive_withATickAlreadyFired_leavesOneTickChain() {
        var h = LcSupport.active();

        h.advance(5_000L);
        var incarnation = h.model().incarnation();

        h.deliverTogether(new ClusterInput.Rebuild(h.snapshot()),
                          new ClusterInput.Timer(TimerId.timerId(TimerId.Kind.TICK), incarnation));
        assertThat(h.timers.stream().filter(due -> due.timer()
                                                      .kind() == TimerId.Kind.TICK).count()).as("one tick chain")
                  .isEqualTo(1L);
    }

    // -- prior item 5: DeploymentFailed before acceptance ----------------------------------------------------------------
    /// R-3.3.6 puts the permanent mark in the artifact's failure transaction; `DeploymentFailed` belongs to that
    /// transaction's acceptance (as `DeploymentStarted` does, `onAccepted`). Red when: a suspended leader, which submits
    /// nothing, still routes `DeploymentFailed`.
    @Test
    void fatalFailure_whileSuspended_routesNoDeploymentFailed_beforeAnyAcceptance() {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        SlFixtures.seedGoal(h, CORE_1, ORDERS, SliceGoal.LOADED, SlFixtures.G1);
        SlFixtures.seedRow(h, CORE_1, ORDERS, SliceObservedState.LOADING, SlFixtures.G1, 11L);
        h.activate();
        h.deliver(new ClusterInput.WitnessStale());
        h.clearLog();
        new NodeSim(h, CORE_1, process(11L)).fails(ORDERS, true);
        h.drain();
        assertThat(h.submits(SubmitKind.FAILURE)).as("suspended: no failure transaction").isEmpty();
        assertThat(h.effects(ClusterEffect.Route.class)
                    .stream()
                    .filter(route -> route.event() instanceof RoutedEvent.DeploymentFailed)
                    .toList()).as("nothing was accepted, so nothing is announced")
                  .isEmpty();
    }

    // -- prior item 6: shared judgment ops dropped -----------------------------------------------------------------------
    /// R-4.2.5: an observed UNLOADED clears `unresponsive[n]`. The clear rides the artifact's failure transaction together with
    /// another pair's failure. That pair's row moves before the transaction applies, so it is refused and its only pair is
    /// split out. Red when: the shared judgment mutation (the clear) is dropped with it and nothing re-derives it, so the node
    /// stays excluded until the 10T expiry although it answered.
    @Test
    void sharedClear_survivesARefusalInWhichEveryPairMoved() {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1, CORE_2, CORE_3);
        var g2 = new Generation(1L, 2L);
        var g3 = new Generation(1L, 3L);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        SlFixtures.seedJudgment(h,
                                ORDERS,
                                new ArtifactJudgmentValue(false,
                                                          Map.of(),
                                                          Map.of(CORE_2,
                                                                 new UnresponsiveEntry(SlFixtures.G1,
                                                                                       SliceGoal.LOADED,
                                                                                       FsmHarness.START_WALL))));
        SlFixtures.seedGoal(h, CORE_2, ORDERS, SliceGoal.UNLOADED, g2);
        SlFixtures.seedRow(h, CORE_2, ORDERS, SliceObservedState.UNLOADING, g2, 12L);
        SlFixtures.seedGoal(h, CORE_3, ORDERS, SliceGoal.LOADED, g3);
        SlFixtures.seedRow(h, CORE_3, ORDERS, SliceObservedState.LOADING, g3, 13L);
        h.activate();
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.clearLog();
        new NodeSim(h, CORE_2, process(12L)).writes(ORDERS, SliceObservedState.UNLOADED, g2);
        new NodeSim(h, CORE_3, process(13L)).fails(ORDERS, false);
        h.drain();
        var failure = h.submits(SubmitKind.FAILURE).getLast();

        assertThat(failure.transaction().mutations().stream().map(mutation -> mutation.key()).toList()).as("one transaction: the judgment and CORE_3's UNLOADED")
                  .contains(new ArtifactJudgmentKey(ORDERS),
                            new SliceDesiredKey(CORE_3, ORDERS));
        new NodeSim(h, CORE_3, process(13L)).writes(ORDERS, SliceObservedState.UNLOADING, g3);
        h.drain();
        var verdict = h.applyHeld(failure.correlationId());

        assertThat(verdict).isInstanceOf(Settlement.Refused.class);
        h.held.clear();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.settleHeld(failure, verdict);
        h.advance(120_000L);
        assertThat(h.store.get(new ArtifactJudgmentKey(ORDERS))
                          .map(value -> ((ArtifactJudgmentValue) value).unresponsive()
                                                                       .containsKey(CORE_2))
                          .or(false)).as("CORE_2 answered UNLOADED: its exclusion is cleared, not left to the 10T expiry")
                  .isFalse();
    }

    // -- new: C9 removal lost while suspended --------------------------------------------------------------------------
    /// C9 / G-R.1: the removal batch (target, apply, judgment removes). Red when: an AppBlueprintRemove handled while the leader
    /// value does not name this node is dropped with the suspended flush and never re-derived after the leader value names
    /// this node again (the blueprint is already unindexed, and no reconcile step removes a target with no blueprint), so the
    /// removed blueprint's slices keep their target and stay placed.
    @Test
    void blueprintRemoval_handledWhileSuspended_isNotLost() {
        var h = LcSupport.deployed(1);

        h.deliver(new ClusterInput.WitnessStale());
        h.seedRemove(new AppBlueprintKey(blueprintId(APP)));
        h.drain();
        h.commitLeader(FsmHarness.SELF, 2L);
        h.drain();
        h.advance(60_000L);
        assertThat(h.store.get(new SliceTargetKey(ORDERS.base())).isPresent()).as("the removed blueprint's target is removed")
                  .isFalse();
    }

    // -- new: J5 directive lost while suspended -------------------------------------------------------------------------
    /// 3.2 "CI-16 naming ctx.self(): rebuild the witness; re-derive pending intents; resume". Red when: a fresh core that
    /// joins while the leader value does not name this node never gets its CORE directive after the leader value names this
    /// node again: J5 runs only on the join input, the suspended flush dropped its write, and no reconcile step assigns
    /// directives.
    @Test
    void coreJoin_whileSuspended_getsItsDirectiveAfterResume() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3).activate();
        var fresh = node("core-9");

        w.h.deliver(new ClusterInput.WitnessStale());
        w.h.deliver(new ClusterInput.NodeJoined(fresh, w.admit(fresh, 0x9L)));
        w.h.commitLeader(FsmHarness.SELF, 2L);
        w.h.drain();
        w.h.advance(60_000L);
        assertThat(w.present(new ActivationDirectiveKey(fresh))).as("the CORE directive is written once this node leads again")
                  .isTrue();
    }

    /// R-4.2.5 "cleared by an observed UNLOADED". Red when: the clear decided while suspended is dropped with the flush and
    /// never re-derived, so the node stays excluded until the 10T expiry although it answered.
    @Test
    void clearUnresponsive_decidedWhileSuspended_isNotLost() {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1, CORE_2, CORE_3);
        var g2 = new Generation(1L, 2L);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        SlFixtures.seedJudgment(h,
                                ORDERS,
                                new ArtifactJudgmentValue(false,
                                                          Map.of(),
                                                          Map.of(CORE_2,
                                                                 new UnresponsiveEntry(SlFixtures.G1,
                                                                                       SliceGoal.LOADED,
                                                                                       FsmHarness.START_WALL))));
        SlFixtures.seedGoal(h, CORE_2, ORDERS, SliceGoal.UNLOADED, g2);
        SlFixtures.seedRow(h, CORE_2, ORDERS, SliceObservedState.UNLOADING, g2, 12L);
        h.activate();
        h.deliver(new ClusterInput.WitnessStale());
        new NodeSim(h, CORE_2, process(12L)).writes(ORDERS, SliceObservedState.UNLOADED, g2);
        h.drain();
        h.commitLeader(FsmHarness.SELF, 2L);
        h.drain();
        h.advance(120_000L);
        assertThat(excluded(h, CORE_2)).as("CORE_2 answered UNLOADED: its exclusion is cleared").isFalse();
    }

    // -- new: expiry uses LOAD_T for every stage -------------------------------------------------------------------------
    /// R-4.2.5 expiry at `10 x T`, and L-12 "excluded <= 10T". A goal LOADED over an ACTIVE row that never deactivates is
    /// retracted at 3 x 30 s (DEACTIVATING, R-6.1.4: 90 s). Red when: the exclusion outlives 10 x 30 s because the expiry uses
    /// the LOADING T (120 s) for every stage, i.e. 20 min.
    @Test
    void deactivationExclusion_expiresAtTenTimesTheDeactivationTimeout() {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        SlFixtures.seedGoal(h, CORE_1, ORDERS, SliceGoal.LOADED, SlFixtures.G1);
        SlFixtures.seedRow(h, CORE_1, ORDERS, SliceObservedState.ACTIVE, SlFixtures.G1, 11L);
        h.activate();
        h.advance(3 * 30_000L + 31_000L);
        assertThat(excluded(h, CORE_1)).as("retracted at 3 x 30 s").isTrue();
        h.advance(10 * 30_000L + 31_000L);
        assertThat(h.warns("slice-unresponsive-expired")).as("expired at 10 x 30 s").isNotEmpty();
    }

    private static boolean excluded(FsmHarness h, NodeId node) {
        return h.store.get(new ArtifactJudgmentKey(ORDERS))
                      .map(value -> ((ArtifactJudgmentValue) value).unresponsive()
                                                                   .containsKey(node))
                      .or(false);
    }

    // -- new: two worker joins in one drain mint one community ----------------------------------------------------------
    /// J5 (worker): mint the community if absent + WORKER directive. Red when: two workers of a new source joining in one
    /// drain both build a mint expecting the community absent; the second is refused (the community moved) and nothing
    /// re-derives its directive, so it stays without one.
    @Test
    void twoWorkerJoins_inOneDrain_bothGetADirective() {
        var w1 = node("worker-1");
        var w2 = node("worker-2");
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3)
                       .seams(seams -> seams.withCommunityPolicies(Map.of())
                                            .withMemberSource(Map.of(w1, "east", w2, "east")))
                       .activate();

        w.h.deliverTogether(new ClusterInput.WorkerJoin(w1, w.admit(w1, 21L)),
                            new ClusterInput.WorkerJoin(w2, w.admit(w2, 22L)));
        w.h.advance(60_000L);
        assertThat(w.present(new ActivationDirectiveKey(w1))).isTrue();
        assertThat(w.present(new ActivationDirectiveKey(w2))).as("the second worker is directed too").isTrue();
    }

    // -- new: rollback bound ----------------------------------------------------------------------------------------------
    /// G-U.2 "rollback is one attempt-fenced transaction, <= 6 submissions"; 6.2 "rollback: <= 6, then abandon". Red when: a
    /// rollback refused with nothing moved is submitted 7 times (1 + 6 resubmissions).
    @Test
    void rollback_refusedWithNothingMoved_isSubmittedAtMostSixTimes() {
        var h = LcSupport.deployed(1);
        var host = SlFixtures.hosts(h, ORDERS).getFirst();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        new NodeSim(h, host, process(11L)).fails(ORDERS, true);
        h.drain();
        assertThat(h.submits(SubmitKind.ROLLBACK)).as("a fatal failure mid-apply rolls back").isNotEmpty();
        var current = h.submits(SubmitKind.ROLLBACK).getLast();

        for (var i = 0; i < 12; i++) {
            var before = h.submits(SubmitKind.ROLLBACK).size();

            h.settleHeld(current,
                         new Settlement.Refused(h.committedLeader(), Map.of()));
            if (h.submits(SubmitKind.ROLLBACK).size() == before) {
                break;
            }

            current = h.submits(SubmitKind.ROLLBACK).getLast();
        }

        assertThat(h.submits(SubmitKind.ROLLBACK)).as("at most six submissions").hasSizeLessThanOrEqualTo(6);
    }

    // -- new: R-5.7.4 a pair in at most one transaction per drain ----------------------------------------------------------
    /// R-5.7.4: "a pair appears in at most one transaction per drain". A desired key with goal UNLOADED and no observed row is
    /// both garbage (step 10) and a vacant pair placement may take (step 3). Red when: one flush carries the GC removal and the
    /// new goal for the same desired key in two transactions.
    @Test
    void desiredKey_isInAtMostOneTransactionPerDrain() {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        SlFixtures.seedGoal(h, CORE_1, ORDERS, SliceGoal.UNLOADED, SlFixtures.G1);
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.activate();
        var duplicated = new ArrayList<AetherKey>();

        for (var step : h.steps) {
            var seen = new HashSet<AetherKey>();

            step.stream()
                .filter(ClusterEffect.Submit.class::isInstance)
                .map(ClusterEffect.Submit.class::cast)
                .forEach(submit -> submit.transaction()
                                         .mutations()
                                         .stream()
                                         .map(mutation -> mutation.key())
                                         .filter(SliceDesiredKey.class::isInstance)
                                         .distinct()
                                         .forEach(key -> {
                             if (!seen.add(key)) {
                             duplicated.add(key);
                         }
                         }));
        }

        assertThat(duplicated).as("no desired key in two transactions of one drain").isEmpty();
    }
}
