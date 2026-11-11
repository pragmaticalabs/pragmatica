// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodePlacementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodePlacementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.MbWorld.C;
import static org.pragmatica.aether.deployment.cluster.fsm2.MbWorld.GOV;
import static org.pragmatica.aether.deployment.cluster.fsm2.MbWorld.tokenOf;
import static org.assertj.core.api.Assertions.assertThat;


/// The departure backstop (spec C9 to C11, R-2.15, step 7): a lost departure input is recorded by reconcile, per node class,
/// fenced, record-only. Bounds come from the real defaults; announcements are real Puts at the real reannounce rate.
class BackstopTest {
    private static final NodeId W1 = node("w1");
    private static final NodeId W2 = node("w2");
    private static final NodeId M1 = node("m1");
    private static final NodeId M2 = node("m2");
    private static final NodeId M3 = node("m3");
    private static final NodeId M4 = node("m4");
    private static final long BW = ms(bounds().workerRemoval());
    private static final long BRM = ms(bounds().coreRemoval());

    private static boolean removes(ClusterEffect.Submit submit, NodeId node) {
        return submit.transaction()
                     .mutations()
                     .stream()
                     .anyMatch(m -> m.replacement()
                                     .isEmpty() && concerns(m.key(),
                                                            node));
    }

    private static boolean concerns(AetherKey key, NodeId node) {
        return switch (key) {
            case SliceObservedKey k -> k.nodeId().equals(node);
            case SliceDesiredKey k -> k.nodeId().equals(node);
            case NodeRoutesKey k -> k.nodeId().equals(node);
            case ActivationDirectiveKey k -> k.nodeId().equals(node);
            case NodePlacementKey k -> k.nodeId().equals(node);
            default -> false;
        };
    }

    private static boolean wrote(ClusterEffect.Submit submit, NodeId node) {
        return submit.transaction()
                     .mutations()
                     .stream()
                     .anyMatch(m -> m.key() instanceof NodeDepartureKey k && k.nodeId()
                                                                              .equals(node));
    }

    /// Community C of w1 and four live members; ORDERS (2 instances, WORKERS_ONLY) runs on w1 and m1.
    private MbWorld world126() {
        var w = MbWorld.world(CORE_1, CORE_2);
        var all = Set.of(W1, M1, M2, M3, M4);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, 0x2222L);
        w.workers(C, 10, all, W1, M1, M2, M3, M4).target(ORDERS, 2, "WORKERS_ONLY");
        w.instance(W1, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(W1)).routes(W1, ORDERS);
        w.instance(M1, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(M1));
        w.h.seedQuietly(new NodePlacementKey(W1), new NodePlacementValue("default", Option.none(), "i-1"));
        w.activate();
        w.announce(C, GOV, W1, M1, M2, M3, M4);
        w.refreshSeams(s -> s.withLocalAlive(Option.some(Set.of(W1, M1, M2, M3, M4))));

        return w;
    }

    private static final List<NodeId> WITHOUT_W1 = List.of(M1, M2, M3, M4);
    private static final Set<NodeId> ALIVE_WITHOUT_W1 = Set.of(M1, M2, M3, M4);

    /// t0: C's next re-announcement omits w1 and the evidence for it expires; the WorkerLeave input is never delivered. The
    /// leader changes a second later; the new leader's Rebuild synthesises no departure.
    private MbWorld lostDeparture() {
        var w = world126();

        w.announce(C, GOV, M1, M2, M3, M4);
        w.refreshSeams(s -> s.withLocalAlive(Option.some(ALIVE_WITHOUT_W1)));
        w.h.advance(1_000L);
        w.h.deliver(new ClusterInput.Deactivate());
        w.h.commitLeader(FsmHarness.SELF, 2L);
        w.h.deliver(new ClusterInput.Activate());
        w.h.clearLog();

        return w;
    }

    // NT-126 (a)
    // Red when: before the first fresh announcement the new leader sees, and before B_w of continuous absence from then,
    // any NodeDepartureKey(w1) write, any removal of w1's keys, or any directive mutation is submitted.
    @Test
    void lostDeparture_beforeTheBound_nothingIsRecordedOrRemoved() {
        var w = lostDeparture();

        assertThat(w.since(W1)).as("a cold leader has seen no announcement: no absence yet").isEqualTo(Option.none());
        w.evolve(C, GOV, ALIVE_WITHOUT_W1, WITHOUT_W1, 5_000L);
        var since = w.since(W1).or(0L);

        assertThat(since).as("the first fresh announcement starts the clock").isGreaterThan(0L);
        w.evolveUntil(since + bounds().workerRemoval() - 1_000_000L, C, GOV, ALIVE_WITHOUT_W1, WITHOUT_W1);
        assertThat(w.h.submits().stream().anyMatch(s -> wrote(s, W1) || removes(s, W1))).isFalse();
        assertThat(w.inferred()).isEmpty();
    }

    // NT-126 (b), (b')
    // Red when: after B_w + one tick no C17s is submitted; it carries anything but the STALE Put, unfenced; no WARN; C17b does
    // not remove w1's rows, desired keys and routes in one guarded transaction; placed(a) is not restored; the directive or
    // placement key is touched by any FSM write; the record is not collected with the directive; assignedByCommunity counted w1.
    @Test
    void lostDeparture_afterTheBound_recordsStale_thenC17bRemovesRows_directiveWaits() {
        var w = lostDeparture();

        w.h.deliver(new ClusterInput.WorkerJoin(W2, w.admit(W2, tokenOf(W2))));
        var alive = Set.of(M1, M2, M3, M4, W2);
        var listed = List.of(M1, M2, M3, M4, W2);

        w.evolve(C, GOV, alive, listed, 5_000L);
        var since = w.since(W1).or(0L);

        w.evolveUntil(since + bounds().workerRemoval() + 1_000_000L + 31_000_000_000L, C, GOV, alive, listed);
        var inferred = w.inferred();

        assertThat(inferred).hasSize(1);
        var mutations = inferred.getFirst().transaction().mutations();

        assertThat(mutations).as("the Put alone").hasSize(1);
        assertThat(mutations.getFirst().key()).isEqualTo(new NodeDepartureKey(W1));
        assertThat(mutations.getFirst().expected().isEmpty()).as("fenced on the observed record: absent").isTrue();
        assertThat(mutations.getFirst().replacement().map(v -> ((NodeDepartureValue) v).cause())).isEqualTo(Option.some(Option.some(DepartureCause.STALE)));
        assertThat(w.h.warns("departure-inferred")).hasSize(1);
        var cleanup = w.h.submits(SubmitKind.CLEANUP);

        assertThat(cleanup).hasSize(1);
        assertThat(cleanup.getFirst().transaction().guards()).anyMatch(g -> g.key()
                                                                             .equals(new NodeDepartureKey(W1)));
        assertThat(w.present(new SliceObservedKey(W1, ORDERS))).isFalse();
        assertThat(w.present(new SliceDesiredKey(W1, ORDERS))).isFalse();
        assertThat(w.present(new NodeRoutesKey(W1, ORDERS))).isFalse();
        assertThat(w.present(new ActivationDirectiveKey(W1))).as("the directive waits (Q1)").isTrue();
        assertThat(w.present(new NodePlacementKey(W1))).isTrue();
        assertThat(w.h.submits()
                      .stream()
                      .flatMap(s -> s.transaction()
                                     .mutations()
                                     .stream())
                      .anyMatch(m -> m.key() instanceof ActivationDirectiveKey k && k.nodeId()
                                                                                     .equals(W1) || m.key() instanceof NodePlacementKey p && p.nodeId()
                                                                                                                                              .equals(W1))).isFalse();
        assertThat(Rows.placed(w.h.model(), ORDERS)).as("placed(a) restored by a fresh goal").isEqualTo(2);
        assertThat(w.h.model().assignedByCommunity.get(C).map(set -> set.contains(W1))).as("not counted by the community accounting")
                  .isEqualTo(Option.some(false));
        w.h.seedRemove(new ActivationDirectiveKey(W1));
        w.h.drain();
        assertThat(w.recordOf(W1).isEmpty()).as("R-4.3.3: confirmed termination collects the record").isTrue();
    }

    // NT-126 (c) first arm
    // Red when: C17s applies although w1 healed (J0 ADMITTED committed first), C17b runs, or w1 stops being counted.
    @Test
    void lostDeparture_healedBeforeTheRecordCommits_refusesTheInference() {
        var w = lostDeparture();

        w.evolve(C, GOV, ALIVE_WITHOUT_W1, WITHOUT_W1, 5_000L);
        var since = w.since(W1).or(0L);

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.evolveUntil(since + bounds().workerRemoval() + 1_000_000L + 31_000_000_000L,
                      C,
                      GOV,
                      ALIVE_WITHOUT_W1,
                      WITHOUT_W1);
        var inferred = w.inferred().getFirst();

        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admit(W1, tokenOf(W1))));
        var admission = w.h.submits(SubmitKind.ADMISSION).getFirst();

        w.h.applyHeld(admission.correlationId());
        w.h.drain();
        var late = w.h.applyHeld(inferred.correlationId());

        w.h.drain();
        w.announce(C, GOV, W1, M1, M2, M3, M4);
        w.refreshSeams(s -> s.withLocalAlive(Option.some(Set.of(W1, M1, M2, M3, M4))));
        assertThat(late).isInstanceOf(Settlement.Refused.class);
        assertThat(w.recordOf(W1).map(NodeDepartureValue::state)).isEqualTo(Option.some(DepartureState.ADMITTED));
        assertThat(w.h.submits(SubmitKind.CLEANUP)).isEmpty();
        assertThat(w.counts(W1)).isTrue();
    }

    // NT-126 (c) second arm
    // Red when: the heal after C17s commits is not restored by J2, or C17b applies after that replacement.
    @Test
    void lostDeparture_healedAfterTheRecordCommits_isRestoredByJ2() {
        var w = lostDeparture();

        w.evolve(C, GOV, ALIVE_WITHOUT_W1, WITHOUT_W1, 5_000L);
        var since = w.since(W1).or(0L);

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.evolveUntil(since + bounds().workerRemoval() + 1_000_000L + 31_000_000_000L,
                      C,
                      GOV,
                      ALIVE_WITHOUT_W1,
                      WITHOUT_W1);
        w.h.applyHeld(w.inferred().getFirst().correlationId());
        w.h.drain();
        var cleanup = w.h.submits(SubmitKind.CLEANUP).getFirst();

        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admit(W1, tokenOf(W1))));
        w.h.applyHeld(w.h.submits(SubmitKind.ADMISSION).getLast().correlationId());
        w.h.drain();
        assertThat(w.recordOf(W1).map(NodeDepartureValue::state)).isEqualTo(Option.some(DepartureState.ADMITTED));
        var verdict = w.h.applyHeld(cleanup.correlationId());

        assertThat(verdict).as("C17b is refused: its witness moved").isInstanceOf(Settlement.Refused.class);
        assertThat(w.present(new SliceObservedKey(W1, ORDERS))).isTrue();
        assertThat(w.h.warns("departure-inferred-reversed")).as("D.6 row 7: the reversal closes departure-inferred").hasSize(1);
        assertThat(w.h.warns("departure-inferred-confirmed")).as("a reversed departure is never confirmed").isEmpty();
    }

    // D.6 row 6 (know 7cc9e79f61), the subject-gone close
    // Red when: an inferred departure whose record is retired (R-2.10) does not close departure-inferred with departure-inferred-confirmed,
    // same subject, reason subject-gone; or the confirmation is raised while the record still stands.
    @Test
    void inferredDeparture_isConfirmed_whenItsIdentityIsRetired() {
        var w = lostDeparture();

        w.evolve(C, GOV, ALIVE_WITHOUT_W1, WITHOUT_W1, 5_000L);
        var since = w.since(W1).or(0L);

        w.evolveUntil(since + bounds().workerRemoval() + 1_000_000L + 31_000_000_000L, C, GOV, ALIVE_WITHOUT_W1, WITHOUT_W1);
        assertThat(w.h.warns("departure-inferred")).hasSize(1);
        assertThat(w.h.warns("departure-inferred-confirmed")).as("not while the record stands").isEmpty();
        w.h.seedRemove(new ActivationDirectiveKey(W1));
        w.h.drain();
        assertThat(w.recordOf(W1).isEmpty()).as("the identity is retired").isTrue();
        var confirmed = w.h.warns("departure-inferred-confirmed");

        assertThat(confirmed).hasSize(1);
        assertThat(confirmed.getFirst().subject()).isEqualTo(W1.id());
        assertThat(confirmed.getFirst().message()).contains(Recoveries.SUBJECT_GONE);
    }

    // D.6 row 21 (know 7cc9e79f61). The subject is a class label, which never leaves the model, so there is no subject-gone arm.
    // Red when: a class reported worker-absence-unjudgeable is not closed by worker-absence-judgeable, same subject, once a node of it is
    // judged again; or the recovery is raised while the class is still unjudgeable.
    @Test
    void unjudgeableClass_isClosed_whenANodeOfItIsJudgedAgain() {
        var w = MbWorld.world(CORE_1);
        var w6 = node("w6");

        w.admit(CORE_1, 0x1111L);
        w.workers(C, 10, Set.of(), w6);
        w.instance(w6, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(w6)).target(ORDERS, 1, "WORKERS_ONLY");
        w.seams(s -> s.withLocalAlive(Option.none()));
        w.activate();
        for (var i = 0; i < 4; i++) {
            w.announce(C, GOV, w6);
            w.h.advance(30_000L);
        }

        var opener = w.h.warns("worker-absence-unjudgeable");

        assertThat(opener).hasSize(1);
        assertThat(w.h.warns("worker-absence-judgeable")).as("still unjudgeable").isEmpty();
        w.refreshSeams(s -> s.withLocalAlive(Option.some(Set.of(w6))));
        for (var i = 0; i < 4; i++) {
            w.announce(C, GOV, w6);
            w.h.advance(30_000L);
        }

        var judgeable = w.h.warns("worker-absence-judgeable");

        assertThat(judgeable).hasSize(1);
        assertThat(judgeable.getFirst().subject()).isEqualTo(opener.getFirst().subject());
    }

    // NT-126 (d)
    // Red when: a node still present in its community's fresh roster is ever recorded, for 10 x B_w.
    @Test
    void presentNode_isNeverRecorded_whateverItsTokenAndLocalSignal() {
        var w = MbWorld.world(CORE_1);
        var w3 = node("w3");

        w.admit(CORE_1, 0x1111L);
        w.workers(C, 10, ALIVE_WITHOUT_W1, w3, M1, M2, M3, M4);
        w.h.admissions.put(w3, w.admit(w3, 0L));
        w.instance(w3,
                   ORDERS,
                   SliceGoal.ACTIVE,
                   SliceObservedState.ACTIVE,
                   gen(1),
                   0L)
         .target(ORDERS, 1, "WORKERS_ONLY")
         .activate();
        w.evolve(C, GOV, ALIVE_WITHOUT_W1, List.of(w3, M1, M2, M3, M4), 10 * BW);
        assertThat(w.h.submits().stream().anyMatch(s -> wrote(s, w3) || removes(s, w3))).isFalse();
        assertThat(w.present(new SliceObservedKey(w3, ORDERS))).isTrue();
    }

    // NT-126 (e)
    // Red when: absentSince is set before the first fresh Put reaches the new leader, or C17s fires before that Put + B_w.
    @Test
    void coldLeader_recordsNothing_untilItHasSeenAFreshAnnouncement_andB_w() {
        var w = lostDeparture();
        var w4 = W1;

        w.h.advance(150_000L);
        assertThat(w.since(w4)).as("no fresh Put: the announcement reads stale").isEqualTo(Option.none());
        assertThat(w.inferred()).isEmpty();
        w.announce(C, GOV, M1, M2, M3, M4);
        w.refreshSeams(s -> s.withLocalAlive(Option.some(ALIVE_WITHOUT_W1)));
        var since = w.since(w4).or(0L);

        assertThat(since).isGreaterThan(0L);
        w.evolveUntil(since + bounds().workerRemoval() - 1_000_000L, C, GOV, ALIVE_WITHOUT_W1, WITHOUT_W1);
        assertThat(w.inferred()).as("not before the first fresh Put + B_w").isEmpty();
        w.evolve(C, GOV, ALIVE_WITHOUT_W1, WITHOUT_W1, 2_000L + 31_000L);
        assertThat(w.inferred()).hasSize(1);
    }

    // NT-127 (a)
    // Red when: a live worker that appears only in a governor announcement (or only in localAlive) is ever recorded: any C17s,
    // NodeDepartureKey write, removal of its keys, or change of its counts, for 2 x B_rm and 3 x B_w. The worker is alone in its
    // community: with company, a core-view judgment would make the whole community absent and the breaker would hide the defect.
    @Test
    void liveWorker_visibleInOneSignalOnly_isNeverRecorded() {
        for (var inRoster : new boolean[]{true, false}) {
            var w = MbWorld.world(CORE_1);
            var w5 = node("w5");
            var alive = inRoster
                        ? Set.<NodeId> of()
                        : Set.<NodeId> of(w5);
            var listed = inRoster
                         ? List.of(w5)
                         : List.<NodeId> of();

            w.admit(CORE_1, 0x1111L);
            w.workers(C, 10, alive, w5).target(ORDERS, 1, "WORKERS_ONLY");
            w.instance(w5, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(w5)).activate();
            var before = Rows.activeCount(w.h.model(), ORDERS);

            w.evolve(C, GOV, alive, listed, Math.max(2 * BRM, 3 * BW));
            assertThat(w.h.submits().stream().anyMatch(s -> wrote(s, w5) || removes(s, w5))).as("inRoster=" + inRoster)
                      .isFalse();
            assertThat(Rows.activeCount(w.h.model(), ORDERS)).isEqualTo(before);
        }
    }

    // NT-127 (b)
    // Red when: with no announcement, only dissolved announcements, or an unwired local supplier, any C17s or departure
    // record is submitted over 10 x B_w, or worker-absence-unjudgeable is not raised exactly once per Active incarnation.
    @Test
    void noSignal_noRecording_andTheUnjudgeableWarningIsRaisedOnce() {
        for (var variant : List.of("none", "dissolved", "unwired")) {
            var w = MbWorld.world(CORE_1);
            var w6 = node("w6");

            w.admit(CORE_1, 0x1111L);
            if (variant.equals("dissolved")) {
                w.admit(w6, tokenOf(w6));
                w.directive(w6, ActivationDirectiveValue.worker());
                w.seams(s -> s.withCoreView(Option.some(Set.of(CORE_1)))
                              .withLocalAlive(Option.some(Set.of())));
            } else {
                w.workers(C, 10, Set.of(), w6);
            }

            w.instance(w6,
                       ORDERS,
                       SliceGoal.ACTIVE,
                       SliceObservedState.ACTIVE,
                       gen(1),
                       tokenOf(w6))
             .target(ORDERS, 1, "WORKERS_ONLY");
            if (variant.equals("unwired")) {
                w.seams(s -> s.withLocalAlive(Option.none()));
            }

            w.activate();
            if (variant.equals("dissolved")) {
                w.announceDissolved(C, GOV, M1);
            }

            if (variant.equals("unwired")) {
                w.announce(C, GOV, M1);
            }

            for (var i = 0L; i < 10 * BW / 30_000L; i++) {
                w.h.advance(30_000L);
                if (variant.equals("dissolved")) {
                    w.announceDissolved(C, GOV, M1);
                }

                if (variant.equals("unwired")) {
                    w.announce(C, GOV, M1);
                }
            }

            assertThat(w.h.submits().stream().anyMatch(s -> wrote(s, w6) || removes(s, w6))).as(variant).isFalse();
            assertThat(w.h.warns("worker-absence-unjudgeable")).as(variant).hasSize(1);
        }
    }

    // NT-127 (c)
    // Red when: C17s is submitted before B_w of continuous absence, not after B_w + one tick, carries more than the STALE Put, or
    // is not fenced on the observed record (a concurrent ADMITTED that commits first must refuse it as a whole); or the
    // continuity sub-arm fires before a fresh B_w from the second disappearance.
    @Test
    void absentPastItsBound_isRecorded_fenced_andPresenceResetsTheClock() {
        var w = MbWorld.world(CORE_1);
        var w7 = node("w7");

        w.admit(CORE_1, 0x1111L);
        w.workers(C, 10, ALIVE_WITHOUT_W1, w7, M1, M2, M3, M4).target(ORDERS, 1, "WORKERS_ONLY");
        w.instance(w7, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(w7)).activate();
        w.evolve(C, GOV, ALIVE_WITHOUT_W1, WITHOUT_W1, 5_000L);
        var first = w.since(w7).or(0L);

        assertThat(first).isGreaterThan(0L);
        w.evolveUntil(first + bounds().workerRemoval() - 1_000_000L - 1_000_000_000L,
                      C,
                      GOV,
                      ALIVE_WITHOUT_W1,
                      WITHOUT_W1);
        assertThat(w.inferred()).isEmpty();
        w.refreshSeams(s -> s.withLocalAlive(Option.some(Set.of(M1, M2, M3, M4, w7))));
        assertThat(w.since(w7)).as("one sample in localAlive resets the clock").isEqualTo(Option.none());
        w.refreshSeams(s -> s.withLocalAlive(Option.some(ALIVE_WITHOUT_W1)));
        var second = w.since(w7).or(0L);

        w.evolveUntil(second + bounds().workerRemoval() - 1_000_000L, C, GOV, ALIVE_WITHOUT_W1, WITHOUT_W1);
        assertThat(w.inferred()).as("not before a fresh B_w").isEmpty();
        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.evolve(C, GOV, ALIVE_WITHOUT_W1, WITHOUT_W1, 2_000L + 31_000L);
        var inferred = w.inferred();

        assertThat(inferred).hasSize(1);
        assertThat(inferred.getFirst().transaction().mutations()).hasSize(1);
        w.h.deliver(new ClusterInput.WorkerJoin(w7, w.admit(w7, tokenOf(w7))));
        w.h.applyHeld(w.h.submits(SubmitKind.ADMISSION).getLast().correlationId());
        w.h.drain();
        assertThat(w.h.applyHeld(inferred.getFirst().correlationId())).as("a concurrent ADMITTED refuses it as a whole")
                  .isInstanceOf(Settlement.Refused.class);
        assertThat(w.recordOf(w7).map(NodeDepartureValue::state)).isEqualTo(Option.some(DepartureState.ADMITTED));
    }

    // NT-127 (d)
    // Red when: C17s fires for a core before B_rm or not after B_rm + one tick; fires with coreView() = none; or a core's
    // absence consults rosters or localAlive.
    @Test
    void core_isJudgedAgainstTheCoreViewAlone() {
        var w = MbWorld.world(CORE_1, CORE_2);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, 0x2222L);
        w.directive(CORE_2,
                    ActivationDirectiveValue.core())
         .target(ORDERS, 1, "CORE_ONLY")
         .instance(CORE_2,
                   ORDERS,
                   SliceGoal.ACTIVE,
                   SliceObservedState.ACTIVE,
                   gen(1),
                   0x2222L);
        w.seams(s -> s.withCoreView(Option.some(Set.of(CORE_1)))
                      .withLocalAlive(Option.some(Set.of(CORE_1, CORE_2))));
        w.activate();
        w.announce(C, GOV, CORE_2);
        w.refreshSeams(s -> s);
        var since = w.since(CORE_2).or(0L);

        assertThat(since).as("a core's absence never consults rosters or localAlive").isGreaterThan(0L);
        w.h.advance(BRM - 1_000L);
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.inferred()).isEmpty();
        w.h.advance(1_001L + 31_000L);
        assertThat(w.inferred()).hasSize(1);
    }

    // NT-127 (d) core view unwired
    // Red when: C17s ever fires for a core while the core view is none.
    @Test
    void core_withNoCoreView_isNeverRecorded() {
        var w = MbWorld.world(CORE_1, CORE_2);

        w.admit(CORE_2, 0x2222L);
        w.directive(CORE_2, ActivationDirectiveValue.core()).activate();
        w.h.advance(3 * BRM);
        assertThat(w.inferred()).isEmpty();
        assertThat(w.h.warns("worker-absence-unjudgeable")).hasSize(1);
    }

    // #2868: a seed core never receives an activation directive, and the live views (counted cores, core view) drop it when it departs.
    private static MbWorld departedSeedCore(Set<NodeId> seedCores) {
        var w = MbWorld.worldWithSeedCores(seedCores, CORE_1, CORE_2);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, 0x2222L);
        w.target(ORDERS, 1, "CORE_ONLY")
         .instance(CORE_2,
                   ORDERS,
                   SliceGoal.ACTIVE,
                   SliceObservedState.ACTIVE,
                   gen(1),
                   0x2222L);

        return w;
    }

    private static MbWorld withoutCore2(MbWorld w) {
        return w.seams(s -> s.withCountedCores(Set.of(CORE_1))
                             .withCoreView(Option.some(Set.of(CORE_1)))
                             .withLocalAlive(Option.some(Set.of(CORE_1))));
    }

    private static void assertRecordedAfterBrm_andRowsRemoved(MbWorld w) {
        w.refreshSeams(s -> s);
        assertThat(w.since(CORE_2).or(0L)).as("judged ABSENT as a core, not UNJUDGEABLE as a worker").isGreaterThan(0L);
        assertThat(w.h.warns("worker-absence-unjudgeable")).as("no unjudgeable class").isEmpty();
        w.h.advance(BRM - 1_000L);
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.inferred()).as("not before B_rm").isEmpty();
        w.h.advance(1_001L + 31_000L);
        var inferred = w.inferred();

        assertThat(inferred).hasSize(1);
        var mutation = inferred.getFirst().transaction().mutations().getFirst();

        assertThat(mutation.key()).isEqualTo(new NodeDepartureKey(CORE_2));
        assertThat(mutation.replacement().map(v -> ((NodeDepartureValue) v).cause())).isEqualTo(Option.some(Option.some(DepartureCause.STALE)));
        assertThat(w.h.warns("departure-inferred")).singleElement().satisfies(warn -> assertThat(warn.subject()).isEqualTo(CORE_2.id()));
        assertThat(w.present(new SliceObservedKey(CORE_2, ORDERS))).as("C17b removed the observed row").isFalse();
        assertThat(w.present(new SliceDesiredKey(CORE_2, ORDERS))).as("C17b removed the desired row").isFalse();
    }

    // #2868 (1): Red when: a directive-less seed core is classed by the live views alone (the seed-core disjunct of classOf dropped): once it leaves
    // them it is an unassigned worker, UNJUDGEABLE with no rosters, so it is never recorded and its rows stay.
    @Test
    void seedCore_withoutDirective_lostDeparture_isRecordedAfterB_rm_andRowsRemoved() {
        var w = withoutCore2(departedSeedCore(Set.of(CORE_1, CORE_2)));

        w.activate();
        assertRecordedAfterBrm_andRowsRemoved(w);
    }

    // #2868 (2), the E04 r1 ordering: the seed core goes DEAD before this leader activates, so NodeRemoved lands in Dormant and is dropped
    // (ClusterFsm.whileDormant). Red when: the backstop cannot stand in for that edge because the departed seed core is not classed CORE.
    @Test
    void seedCore_deadBeforeActivation_droppedEdge_rowsRemovedAfterActivation() {
        var w = departedSeedCore(Set.of(CORE_1, CORE_2));

        withoutCore2(w).h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        assertThat(w.h.submits()).as("the edge is dropped while Dormant").isEmpty();
        w.activate();
        assertThat(w.recordOf(CORE_2).isEmpty()).as("no departure record from the dropped edge").isTrue();
        assertRecordedAfterBrm_andRowsRemoved(w);
    }

    // #2868 (3), regression: Red when: the seed set leaks into the class of a node outside it (every directive-less node classed CORE). A
    // directive-less non-seed node that left the views stays an unassigned worker: with no rosters it is UNJUDGEABLE, never recorded.
    @Test
    void nonSeedWorker_withoutDirective_keepsTheWorkerClass_andIsNeverRecorded() {
        var w = withoutCore2(departedSeedCore(Set.of(CORE_1)));

        w.activate();
        assertThat(Absence.classOf(w.h.model(), new Ctx(w.h.settings, w.h.wallMs, w.h.monoNs), CORE_2)).isEqualTo(Absence.NodeClass.UNASSIGNED_WORKER);
        assertThat(Absence.classOf(w.h.model(), new Ctx(w.h.settings, w.h.wallMs, w.h.monoNs), CORE_1)).as("control: the seed core").isEqualTo(Absence.NodeClass.CORE);
        w.h.advance(3 * BRM);
        assertThat(w.inferred()).isEmpty();
        assertThat(w.present(new SliceObservedKey(CORE_2, ORDERS))).isTrue();
        assertThat(w.h.warns("worker-absence-unjudgeable")).singleElement().satisfies(warn -> assertThat(warn.subject()).isEqualTo("worker"));
    }

    // Red when: a directive-less seed core recorded STALE by the backstop is not re-admitted by membership's join (J2, same process).
    @Test
    void seedCore_recordedStale_isReadmittedOnJoin_sameProcess() {
        var w = withoutCore2(departedSeedCore(Set.of(CORE_1, CORE_2)));

        w.activate();
        assertRecordedAfterBrm_andRowsRemoved(w);
        assertThat(w.counts(CORE_2)).as("departed: not counted").isFalse();
        w.refreshSeams(s -> s.withCountedCores(Set.of(CORE_1, CORE_2))
                             .withCoreView(Option.some(Set.of(CORE_1, CORE_2)))
                             .withLocalAlive(Option.some(Set.of(CORE_1, CORE_2))));
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admit(CORE_2, 0x2222L)));
        assertThat(w.recordOf(CORE_2).map(NodeDepartureValue::state)).isEqualTo(Option.some(DepartureState.ADMITTED));
        assertThat(w.counts(CORE_2)).isTrue();
        assertThat(w.since(CORE_2).isEmpty()).as("no absence clock once back in the core view").isTrue();
    }

    // Red when: a restarted (different process) seed core recorded STALE is refused instead of taking J8's core arm.
    @Test
    void seedCore_recordedStale_isReadmittedOnJoin_differentProcess() {
        var w = withoutCore2(departedSeedCore(Set.of(CORE_1, CORE_2)));

        w.activate();
        assertRecordedAfterBrm_andRowsRemoved(w);
        w.refreshSeams(s -> s.withCountedCores(Set.of(CORE_1, CORE_2))
                             .withCoreView(Option.some(Set.of(CORE_1, CORE_2)))
                             .withLocalAlive(Option.some(Set.of(CORE_1, CORE_2))));
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admit(CORE_2, 0x3333L)));
        assertThat(w.recordOf(CORE_2).map(NodeDepartureValue::state)).isEqualTo(Option.some(DepartureState.ADMITTED));
        assertThat(w.counts(CORE_2)).isTrue();
        assertThat(w.h.warns("join-refused-process")).isEmpty();
    }

    // #2887 (1): the boot topology snapshot lacks CORE_2 (cloud-discovery majority boot); consensus later installs it. Red when: classOf reads only
    // the boot-time Settings.seedCores (the committedCores disjunct dropped): CORE_2 stays an unassigned worker, UNJUDGEABLE, rows never removed.
    @Test
    void seedCore_missingAtBoot_committedLater_departsBeforeActivation_rowsRemoved() {
        var w = withoutCore2(departedSeedCore(Set.of(CORE_1))).seams(s -> s.withCommittedCores(Set.of(CORE_1, CORE_2)));

        w.activate();
        assertRecordedAfterBrm_andRowsRemoved(w);
    }

    // #2887 (2), leader change / restart: the new leader's first activation samples the committed voters fresh, and the dropped NodeRemoved
    // edge (the departure preceded activation) is stood in for by the backstop. Red when: the class depended on state the old leader held.
    @Test
    void seedCore_missingAtBoot_newLeaderSamplesCommittedSet_droppedEdge_rowsRemoved() {
        var w = departedSeedCore(Set.of(CORE_1));

        withoutCore2(w).seams(s -> s.withCommittedCores(Set.of(CORE_1, CORE_2))).h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.activate();
        assertThat(w.recordOf(CORE_2).isEmpty()).as("no departure record from the dropped edge").isTrue();
        assertRecordedAfterBrm_andRowsRemoved(w);
    }

    // #2887 (3): a core that appears in the committed set only after activation is classed CORE from the next seam sample; before it, a worker.
    @Test
    void seedCore_missingAtBoot_classChangesWhenTheCommittedSetCatchesUp() {
        var w = withoutCore2(departedSeedCore(Set.of(CORE_1)));

        w.activate();
        assertThat(Absence.classOf(w.h.model(), new Ctx(w.h.settings, w.h.wallMs, w.h.monoNs), CORE_2)).as("before").isEqualTo(Absence.NodeClass.UNASSIGNED_WORKER);
        w.refreshSeams(s -> s.withCommittedCores(Set.of(CORE_1, CORE_2)));
        assertThat(Absence.classOf(w.h.model(), new Ctx(w.h.settings, w.h.wallMs, w.h.monoNs), CORE_2)).as("after").isEqualTo(Absence.NodeClass.CORE);
    }
}
