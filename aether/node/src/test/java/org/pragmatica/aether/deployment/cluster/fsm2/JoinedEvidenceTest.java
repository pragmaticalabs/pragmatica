// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
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


/// What supersedes a committed DEPARTED record (spec R-2.11, R-4.3.0, C11f, C17b; D-4 (ii), G-3, G-4, H-1): evidence this leader observed
/// **after** its `Rebuild`. NT-129 (the `Rebuild` seed never supersedes), NT-130 (a worker's lost re-admission is recovered by post-`Rebuild`
/// evidence), NT-131 (C17b is deferred for a liveness-pending core), NT-133 (a same-leader graceful leave is never re-admitted on a stale
/// announcement), and the clearing of `joined` by a removal input and by C17s.
class JoinedEvidenceTest {
    private static final long TOKEN_2 = 0x2222L;
    private static final long E = 3L;
    private static final NodeId N = CORE_2;
    private static final NodeId W1 = node("w1");
    private static final NodeId M1 = node("m1");
    private static final NodeId M2 = node("m2");
    private static final NodeId M3 = node("m3");
    private static final NodeId M4 = node("m4");
    private static final Set<NodeId> ALL_CORES = Set.of(CORE_1, CORE_2, CORE_3);
    private static final Set<NodeId> OTHER_CORES = Set.of(CORE_1, CORE_3);
    private static final Set<NodeId> COMMUNITY = Set.of(W1, M1, M2, M3, M4);
    private static final Set<NodeId> COMMUNITY_WITHOUT_W1 = Set.of(M1, M2, M3, M4);
    private static final long SUSPICION_MS = ms(bounds().coreSuspicion());
    private static final long TICK_MS = 30_000L;
    private static final long BW = ms(bounds().workerRemoval());

    // -- worlds ----------------------------------------------------------------------------------------------------
    /// Five-core scene of NT-129: the old leader A committed `DEPARTED(E, p)` for n and crashed; this FSM is the new leader B, whose own
    /// membership view still knows n (its admission, its place in the counted cores). n holds ACTIVE rows for ORDERS, with routes.
    private MbWorld deadCore() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.admit(CORE_3, 0x3333L);

        return w.target(ORDERS, 2, "CORE_ONLY")
                .directive(CORE_1, ActivationDirectiveValue.core())
                .directive(CORE_2, ActivationDirectiveValue.core())
                .instance(CORE_1, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), 0x1111L)
                .instance(CORE_2, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), TOKEN_2)
                .routes(CORE_2, ORDERS)
                .record(CORE_2, DepartureState.DEPARTED, E, TOKEN_2, DepartureCause.NODE_REMOVED);
    }

    private MbWorld deadCoreSeenBy(Set<NodeId> view) {
        return deadCore().seams(s -> s.withCoreView(Option.some(view)));
    }

    /// Community C with w1 and four live members; ORDERS (2 instances) runs on w1 and m1. `alive` is the local alive view at `Rebuild`.
    private MbWorld communityOfW1(Set<NodeId> alive) {
        var w = MbWorld.world(CORE_1, CORE_2);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, 0x2222L);
        w.workers(C, 10, alive, W1, M1, M2, M3, M4).target(ORDERS, 2, "WORKERS_ONLY");
        w.instance(W1, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(W1)).routes(W1, ORDERS);
        w.instance(M1, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(M1));

        return w;
    }

    private static Option<DepartureState> stateOf(MbWorld w, NodeId node) {
        return w.recordOf(node)
                .map(NodeDepartureValue::state);
    }

    private static Option<Long> epochOf(MbWorld w, NodeId node) {
        return w.recordOf(node)
                .map(NodeDepartureValue::epoch);
    }

    private static boolean admitsAt(MbWorld w, NodeId node, long epoch) {
        return w.h.submits(SubmitKind.ADMISSION)
                  .stream()
                  .anyMatch(submit -> submit.transaction()
                                            .mutations()
                                            .stream()
                                            .anyMatch(m -> m.key().equals(new NodeDepartureKey(node)) && m.replacement()
                                                                                                          .map(v -> ((NodeDepartureValue) v).epoch() == epoch && ((NodeDepartureValue) v).state() == DepartureState.ADMITTED)
                                                                                                          .or(false)));
    }

    private static void run(MbWorld w, long ms) {
        for (var spent = 0L; spent < ms; spent += TICK_MS) {
            w.h.advance(Math.min(TICK_MS, ms - spent));
        }
    }

    // -- NT-129: the Rebuild seed never supersedes -------------------------------------------------------------------
    // (a) Dead n.
    // Red when: before B's own NodeRemoved(n) or B's suspicion window, B counts n or its ACTIVE rows toward placed(a) or activeCount, writes any
    // ADMITTED record for n, does not build C17b guarded on DEPARTED(e, p) at C1, or does not re-place the shortfall within one reconcile.
    @Test
    void nt129a_deadCore_thatTheNewLeadersMembershipStillKnows_isNotCounted_andItsRowsAreCleanedUp() {
        var w = deadCore().activate();

        assertThat(w.counts(N)).as("a committed departure is positive evidence; a lagging membership view is not").isFalse();
        assertThat(Rows.activeCount(w.h.model(), ORDERS)).as("n's ACTIVE row is not counted").isEqualTo(1);
        assertThat(Rows.placed(w.h.model(), ORDERS)).as("the shortfall is re-placed within one reconcile").isEqualTo(2);
        assertThat(w.h.submits(SubmitKind.ADMISSION)).as("no ADMITTED write for n").isEmpty();
        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("C17b at C1").isNotEmpty();
        var guard = w.h.submits(SubmitKind.CLEANUP).getFirst().transaction().guards().getFirst();

        assertThat(guard.key()).isEqualTo(new NodeDepartureKey(N));
        assertThat(guard.expected().map(v -> ((NodeDepartureValue) v).epoch())).as("guarded on DEPARTED(e)").isEqualTo(Option.some(E));
        assertThat(w.present(new SliceObservedKey(N, ORDERS))).as("n's rows are removed").isFalse();
        run(w, SUSPICION_MS + 2 * TICK_MS);
        assertThat(stateOf(w, N)).as("still DEPARTED: nothing superseded it").isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(w.counts(N)).isFalse();
    }

    // Red when: the same holds with a membership view that is wired but does not list n (the new leader's view lags the other way).
    @Test
    void nt129a_deadCore_whenTheCoreViewIsWiredWithoutN_isNotCounted() {
        var w = deadCoreSeenBy(OTHER_CORES).activate();

        assertThat(w.counts(N)).isFalse();
        assertThat(w.h.submits(SubmitKind.ADMISSION)).isEmpty();
        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("n is not liveness-pending: C17b is built").isNotEmpty();
    }

    // (b) Live n, join input.
    // Red when: B does not write ADMITTED(e + 1, p) expecting DEPARTED(e, p) after processing a NodeJoined(n) it saw after its Rebuild, or n is not
    // counted after that commits.
    @Test
    void nt129b_liveCore_withAJoinInput_isSuperseded_andRewrittenAsTheNextEpoch() {
        var w = deadCore().activate();

        assertThat(w.counts(N)).isFalse();
        w.h.clearLog();
        w.h.deliver(new ClusterInput.NodeJoined(N, w.admissionOf(N)));

        assertThat(admitsAt(w, N, E + 1)).as("ADMITTED(e + 1) ...").isTrue();
        var admission = w.h.submits(SubmitKind.ADMISSION).getFirst().transaction().mutations().getFirst();

        assertThat(admission.expected().map(v -> ((NodeDepartureValue) v).state())).as("... expecting the DEPARTED value")
                  .isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(epochOf(w, N)).isEqualTo(Option.some(E + 1));
        assertThat(w.counts(N)).as("counted after the commit").isTrue();
    }

    // (c) Live n, liveness evidence (cores).
    // Red when: B supersedes before n has been listed for one full suspicion window after Rebuild, or does not supersede (the step-10 rewrite to
    // ADMITTED(e + 1)) once it has.
    @Test
    void nt129c_liveCore_listedForOneFullWindow_isSuperseded_notBefore() {
        var w = deadCoreSeenBy(ALL_CORES).activate();

        assertThat(w.counts(N)).isFalse();
        run(w, SUSPICION_MS - 2_000L);
        assertThat(w.counts(N)).as("before the window ends").isFalse();
        assertThat(w.h.submits(SubmitKind.ADMISSION)).isEmpty();
        w.h.advance(4_000L);
        assertThat(admitsAt(w, N, E + 1)).as("the window is complete").isTrue();
        assertThat(epochOf(w, N)).isEqualTo(Option.some(E + 1));
        assertThat(w.counts(N)).isTrue();
    }

    // Red when: leaving the core view restarts nothing: a core that dropped out of the view for one sample and came back is superseded one
    // window after its first listing instead of one window after the new one (the listing must be continuous).
    @Test
    void nt129c_liveCore_thatLeftTheViewForASample_startsItsWindowAgain() {
        var w = deadCoreSeenBy(ALL_CORES).activate();

        run(w, SUSPICION_MS - 2 * TICK_MS);
        w.refreshSeams(s -> s.withCoreView(Option.some(OTHER_CORES)));
        w.refreshSeams(s -> s.withCoreView(Option.some(ALL_CORES)));
        run(w, 4 * TICK_MS);
        assertThat(w.h.submits(SubmitKind.ADMISSION)).as("one window after the first listing, but not after the second").isEmpty();
        run(w, SUSPICION_MS);
        assertThat(epochOf(w, N)).as("one full window after the second listing").isEqualTo(Option.some(E + 1));
    }

    // (d) Worker.
    // Red when: anything but a WorkerJoin(w) (or C11f) supersedes the record of a worker with a committed WORKER_LEAVE whose directive, roster
    // and local alive view all still list it at Rebuild.
    @Test
    void nt129d_worker_isSupersededOnlyByAWorkerJoin() {
        var w = communityOfW1(COMMUNITY);

        w.h.seedQuietly(new GovernorAnnouncementKey(C), roster(GOV, false, W1, M1, M2, M3, M4));
        w.record(W1, DepartureState.DEPARTED, E, tokenOf(W1), DepartureCause.WORKER_LEAVE).activate();
        run(w, 3 * BW);
        assertThat(w.counts(W1)).as("the directive, the roster and localAlive at Rebuild are not evidence").isFalse();
        assertThat(w.h.submits(SubmitKind.ADMISSION)).isEmpty();
        assertThat(stateOf(w, W1)).isEqualTo(Option.some(DepartureState.DEPARTED));
        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admissionOf(W1)));
        assertThat(epochOf(w, W1)).as("a WorkerJoin supersedes").isEqualTo(Option.some(E + 1));
    }

    // (e) Reverse failure, documented.
    // Red when: a live n that B never re-announces and that is not in B's core SWIM view is counted (fail-closed is the ruled direction).
    @Test
    void nt129e_liveCore_neverReannounced_staysUncounted() {
        var w = deadCoreSeenBy(OTHER_CORES).activate();

        run(w, 10 * 60_000L);
        assertThat(w.counts(N)).isFalse();
        assertThat(w.h.submits(SubmitKind.ADMISSION)).isEmpty();
        assertThat(stateOf(w, N)).isEqualTo(Option.some(DepartureState.DEPARTED));
    }

    // -- NT-130: a worker's lost re-admission is recovered by post-Rebuild evidence ----------------------------------------
    private MbWorld lostReadmission(DepartureCause cause, Set<NodeId> aliveAtRebuild) {
        var w = communityOfW1(aliveAtRebuild);

        w.record(W1, DepartureState.DEPARTED, E, tokenOf(W1), cause).activate();

        return w;
    }

    // (a) Fresh roster.
    // Red when: B does not run C11f -> J3 (ADMITTED(e + 1) expecting DEPARTED(e)) within one tick of the Put that lists w after Rebuild, or w is not
    // counted after it commits.
    @Test
    void nt130a_aFreshRoster_listingW_readmitsIt() {
        var w = lostReadmission(DepartureCause.WORKER_LEAVE, COMMUNITY_WITHOUT_W1);

        assertThat(w.counts(W1)).isFalse();
        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("w is not a core, so never liveness-pending: C17b ran").isNotEmpty();
        w.announce(C, GOV, W1, M1, M2, M3, M4);

        assertThat(admitsAt(w, W1, E + 1)).isTrue();
        assertThat(epochOf(w, W1)).isEqualTo(Option.some(E + 1));
        assertThat(w.counts(W1)).isTrue();
        assertThat(w.h.warns("worker-readmitted-on-evidence")).as("a non-STALE re-admission says why").hasSize(1);
    }

    // (b) localAlive.
    // Red when: the same does not hold through a localAlive sample taken after Rebuild that lists w.
    @Test
    void nt130b_aLocalAliveSample_listingW_readmitsIt() {
        var w = lostReadmission(DepartureCause.WORKER_LEAVE, COMMUNITY_WITHOUT_W1);

        w.announce(C, GOV, M1, M2, M3, M4);
        assertThat(w.counts(W1)).isFalse();
        w.refreshSeams(s -> s.withLocalAlive(Option.some(COMMUNITY)));

        assertThat(epochOf(w, W1)).isEqualTo(Option.some(E + 1));
        assertThat(w.counts(W1)).isTrue();
    }

    // (c) Pre-Rebuild state is not evidence.
    // Red when: B supersedes on a roster committed before its Rebuild that lists w (no later Put arrives), or on the localAlive view of the Rebuild
    // snapshot.
    @Test
    void nt130c_preRebuildRosterAndLocalAlive_areNotEvidence() {
        var w = communityOfW1(COMMUNITY);

        w.h.seedQuietly(new GovernorAnnouncementKey(C), roster(GOV, false, W1, M1, M2, M3, M4));
        w.record(W1, DepartureState.DEPARTED, E, tokenOf(W1), DepartureCause.WORKER_LEAVE).activate();
        run(w, 10 * BW);

        assertThat(w.h.submits(SubmitKind.ADMISSION)).isEmpty();
        assertThat(w.counts(W1)).isFalse();
        assertThat(w.h.warns("worker-readmitted-on-evidence")).isEmpty();
    }

    // (d) STALE unchanged.
    // Red when: a DEPARTED(STALE) record no longer takes J2 through C11f, whether it was committed before Rebuild or by this leader.
    @Test
    void nt130d_staleRecord_stillTakesJ2_whoeverRecordedIt() {
        var inherited = lostReadmission(DepartureCause.STALE, COMMUNITY_WITHOUT_W1);

        inherited.announce(C, GOV, W1, M1, M2, M3, M4);
        assertThat(epochOf(inherited, W1)).as("inherited STALE").isEqualTo(Option.some(E + 1));
        assertThat(inherited.h.warns("worker-readmitted-on-evidence")).as("STALE reports departure-inferred-reversed instead").isEmpty();
        assertThat(inherited.h.warns("departure-inferred-reversed")).hasSize(1);

        var own = communityOfW1(COMMUNITY_WITHOUT_W1);

        own.activate();
        own.h.seed(new NodeDepartureKey(W1),
                   new NodeDepartureValue(DepartureState.DEPARTED, 0L, process(tokenOf(W1)), Option.some(DepartureCause.STALE), own.h.wallMs));
        own.h.drain();
        assertThat(own.counts(W1)).isFalse();
        own.announce(C, GOV, W1, M1, M2, M3, M4);
        assertThat(epochOf(own, W1)).as("recorded by this leader after its Rebuild").isEqualTo(Option.some(1L));
    }

    // (e) Inherited only (H-1).
    // Red when: a DEPARTED(WORKER_LEAVE) committed by B itself, after its Rebuild, is re-admitted by a later fresh announcement or localAlive sample.
    @Test
    void nt130e_aDepartureThisLeaderDecided_isNotReadmittedByEvidence() {
        var w = communityOfW1(COMMUNITY).activate();

        w.announce(C, GOV, W1, M1, M2, M3, M4);
        w.h.deliver(new ClusterInput.WorkerLeave(W1));
        assertThat(stateOf(w, W1)).isEqualTo(Option.some(DepartureState.DEPARTED));
        w.announce(C, GOV, W1, M1, M2, M3, M4);
        w.refreshSeams(s -> s.withLocalAlive(Option.some(COMMUNITY)));

        assertThat(stateOf(w, W1)).isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(w.counts(W1)).isFalse();
    }

    // Red when: `inherited` keeps an id after its record changed: a worker re-admitted from an inherited record, then departed again by this leader,
    // is re-admitted by the next stale announcement.
    @Test
    void nt130e_inherited_leavesTheSetWhenTheRecordChanges() {
        var w = lostReadmission(DepartureCause.WORKER_LEAVE, COMMUNITY_WITHOUT_W1);

        w.announce(C, GOV, W1, M1, M2, M3, M4);
        assertThat(epochOf(w, W1)).isEqualTo(Option.some(E + 1));
        assertThat(w.h.model().inherited.contains(W1)).as("the record changed").isFalse();
        w.h.deliver(new ClusterInput.WorkerLeave(W1));
        assertThat(epochOf(w, W1)).as("departed again by this leader, keeping the epoch of the admission it ends").isEqualTo(Option.some(E + 1));
        w.announce(C, GOV, W1, M1, M2, M3, M4);
        w.refreshSeams(s -> s.withLocalAlive(Option.some(COMMUNITY)));

        assertThat(stateOf(w, W1)).isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(w.counts(W1)).isFalse();
    }

    // -- NT-131: C17b is deferred for a liveness-pending core -------------------------------------------------------------
    // (a) Deferral.
    // Red when: B submits C17b for n at C1, or on any tick, while n stays listed and its window has not elapsed, or n or its rows are counted meanwhile.
    @Test
    void nt131a_c17b_isDeferredWhileTheCoreStaysListed() {
        var w = deadCoreSeenBy(ALL_CORES).activate();

        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("not at C1").isEmpty();
        run(w, SUSPICION_MS - 2_000L);
        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("not on any tick of the window").isEmpty();
        assertThat(w.counts(N)).isFalse();
        assertThat(Rows.activeCount(w.h.model(), ORDERS)).as("n's rows are not counted").isEqualTo(1);
        assertThat(w.present(new SliceObservedKey(N, ORDERS))).as("and not removed").isTrue();
    }

    // (b) Positive resolution.
    // Red when: B does not supersede (step-10 ADMITTED(e + 1) expecting DEPARTED(e)) after n stayed listed for one full window, or any of n's rows was
    // removed at any point.
    @Test
    void nt131b_c17b_neverRuns_whenTheWindowResolvesPositively() {
        var w = deadCoreSeenBy(ALL_CORES).activate();

        run(w, SUSPICION_MS + 2 * TICK_MS);

        assertThat(epochOf(w, N)).isEqualTo(Option.some(E + 1));
        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("no removal at any point").isEmpty();
        assertThat(w.present(new SliceObservedKey(N, ORDERS))).isTrue();
        assertThat(w.counts(N)).isTrue();
    }

    // (c) Negative resolution.
    // Red when: C17b does not run within one tick after n leaves coreView before the window ends, or after NodeRemoved(n) arrives.
    @Test
    void nt131c_c17b_runs_whenTheCoreLeavesTheView() {
        var w = deadCoreSeenBy(ALL_CORES).activate();

        run(w, SUSPICION_MS / 2);
        assertThat(w.h.submits(SubmitKind.CLEANUP)).isEmpty();
        w.refreshSeams(s -> s.withCoreView(Option.some(OTHER_CORES)));
        w.h.advance(TICK_MS);

        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("within one tick").isNotEmpty();
        assertThat(w.present(new SliceObservedKey(N, ORDERS))).isFalse();
    }

    @Test
    void nt131c_c17b_runs_whenARemovalInputArrives_evenIfTheViewStillListsTheCore() {
        var w = deadCoreSeenBy(ALL_CORES).activate();

        assertThat(w.h.submits(SubmitKind.CLEANUP)).isEmpty();
        w.h.deliver(new ClusterInput.NodeRemoved(N));
        w.h.advance(TICK_MS);

        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("the removal input is fresher than the sample").isNotEmpty();
        assertThat(w.present(new SliceObservedKey(N, ORDERS))).isFalse();
        run(w, SUSPICION_MS + 2 * TICK_MS);
        assertThat(w.h.submits(SubmitKind.ADMISSION)).as("and the stale listing does not accrue liveness afterwards").isEmpty();
        assertThat(w.counts(N)).isFalse();
    }

    // (d) Unwired.
    // Red when: with coreView = none, C17b is deferred.
    @Test
    void nt131d_c17b_isNotDeferred_whenTheCoreViewIsUnwired() {
        var w = deadCore().activate();

        assertThat(w.h.model().seams().coreView()).isEqualTo(Option.none());
        assertThat(w.h.submits(SubmitKind.CLEANUP)).isNotEmpty();
        assertThat(w.present(new SliceObservedKey(N, ORDERS))).isFalse();
    }

    // -- NT-133: a same-leader graceful leave is never re-admitted on a stale announcement ------------------------------------
    private MbWorld leaderOfAWorkerThatLeaves(boolean selfShutdown) {
        var w = communityOfW1(COMMUNITY).activate();

        w.announce(C, GOV, W1, M1, M2, M3, M4);
        w.refreshSeams(s -> s.withLocalAlive(Option.some(COMMUNITY)));
        w.h.clearLog();
        w.h.deliver(selfShutdown
                    ? new ClusterInput.SelfShutdown(W1)
                    : new ClusterInput.WorkerLeave(W1));

        return w;
    }

    private static void staleAnnouncement(MbWorld w) {
        w.announce(C, GOV, W1, M1, M2, M3, M4);
        w.refreshSeams(s -> s.withLocalAlive(Option.some(COMMUNITY)));
    }

    // (a) Graceful leave.
    // Red when: L writes any ADMITTED record for w, counts w, places onto w, or raises worker-readmitted-on-evidence, before a WorkerJoin(w) arrives.
    @Test
    void nt133a_gracefulLeave_isNotReadmittedByAStaleAnnouncement() {
        var w = leaderOfAWorkerThatLeaves(false);

        assertThat(stateOf(w, W1)).isEqualTo(Option.some(DepartureState.DEPARTED));
        staleAnnouncement(w);

        assertThat(w.h.submits(SubmitKind.ADMISSION)).isEmpty();
        assertThat(w.counts(W1)).isFalse();
        assertThat(SlFixtures.hosts(w.h, ORDERS)).doesNotContain(W1);
        assertThat(w.h.warns("worker-readmitted-on-evidence")).isEmpty();
        assertThat(stateOf(w, W1)).isEqualTo(Option.some(DepartureState.DEPARTED));
    }

    // (b) The same for SELF_SHUTDOWN.
    @Test
    void nt133b_selfShutdown_isNotReadmittedByAStaleAnnouncement() {
        var w = leaderOfAWorkerThatLeaves(true);

        staleAnnouncement(w);

        assertThat(w.h.submits(SubmitKind.ADMISSION)).isEmpty();
        assertThat(w.counts(W1)).isFalse();
        assertThat(w.h.warns("worker-readmitted-on-evidence")).isEmpty();
    }

    // (c) Control.
    // Red when: a WorkerJoin(w) of the same process does not take J3: ADMITTED(e + 1) expecting DEPARTED(e).
    @Test
    void nt133c_aWorkerJoin_readmitsTheWorker() {
        var w = leaderOfAWorkerThatLeaves(false);

        staleAnnouncement(w);
        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admissionOf(W1)));

        assertThat(admitsAt(w, W1, 1L)).as("DEPARTED(0) + 1").isTrue();
        assertThat(epochOf(w, W1)).isEqualTo(Option.some(1L));
        assertThat(w.counts(W1)).isTrue();
    }

    // (d) Repetition.
    // Red when: any of ten consecutive stale announcements listing w re-admits it (the cycle repeats).
    @Test
    void nt133d_tenStaleAnnouncements_neverReadmit() {
        var w = leaderOfAWorkerThatLeaves(false);

        for (var i = 0; i < 10; i++) {
            staleAnnouncement(w);
            w.h.advance(w.reannounceMs());
        }

        assertThat(w.h.submits(SubmitKind.ADMISSION)).isEmpty();
        assertThat(w.counts(W1)).isFalse();
    }

    // -- joined is cleared by a removal input and by C17s ------------------------------------------------------------------
    // Red when: a removal input does not clear `joined[n]`: a core that joined and was then removed is superseded on commit, rewritten as ADMITTED and
    // counted although membership removed it.
    @Test
    void removalInput_clearsJoined_soTheDepartureStands() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.admit(CORE_3, 0x3333L);
        w.directive(CORE_2, ActivationDirectiveValue.core()).activate();
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2)));
        assertThat(w.h.model().joined.contains(CORE_2)).isTrue();
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));

        assertThat(w.h.model().joined.contains(CORE_2)).isFalse();
        assertThat(w.h.model().isDeparted(CORE_2)).as("the committed DEPARTED is not superseded").isTrue();
        assertThat(w.counts(CORE_2)).isFalse();
        assertThat(stateOf(w, CORE_2)).isEqualTo(Option.some(DepartureState.DEPARTED));
        run(w, 3 * TICK_MS);
        assertThat(stateOf(w, CORE_2)).as("never rewritten as ADMITTED").isEqualTo(Option.some(DepartureState.DEPARTED));
    }

    // Red when: a C17s submission does not clear `joined[n]` (the lost removal input it stands in for would have): the STALE record is superseded at once
    // and a core's rows never reach C17b.
    @Test
    void c17s_clearsJoined_soTheStaleRecordReachesC17b() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.admit(CORE_3, 0x3333L);
        w.target(ORDERS, 1, "CORE_ONLY")
         .directive(CORE_2, ActivationDirectiveValue.core())
         .instance(CORE_2, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), TOKEN_2)
         .seams(s -> s.withCoreView(Option.some(ALL_CORES)))
         .activate();
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2)));
        assertThat(w.h.model().joined.contains(CORE_2)).isTrue();
        w.refreshSeams(s -> s.withCoreView(Option.some(OTHER_CORES)));
        run(w, ms(bounds().coreRemoval()) + 3 * TICK_MS);

        assertThat(w.inferred()).as("the backstop recorded n").isNotEmpty();
        assertThat(stateOf(w, CORE_2)).isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(w.h.model().joined.contains(CORE_2)).isFalse();
        assertThat(w.h.model().isDeparted(CORE_2)).isTrue();
        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("C17b removes the rows").isNotEmpty();
        assertThat(w.present(new SliceObservedKey(CORE_2, ORDERS))).isFalse();
    }
}
