// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.KvPut;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
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

    // -- the extra arms of the supersession machinery -------------------------------------------------------------------------
    // Red when: the liveness-promoted core's ADMITTED rewrite is written more than once while its write is in flight (every reconcile pass would
    // submit another one).
    @Test
    void rewrite_isWrittenOnce_whileItsWriteIsInFlight() {
        var w = deadCoreSeenBy(ALL_CORES).activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        run(w, SUSPICION_MS + TICK_MS);
        for (var i = 0; i < 5; i++) {
            w.h.deliver(new ClusterInput.Reconcile());
        }

        assertThat(w.h.submits(SubmitKind.ADMISSION)).hasSize(1);
    }

    // Red when: a core promoted by liveness alone (its admission carries no known token) is rewritten with the unknown process, erasing what the
    // committed record knows (C8: unknown is the same process, and the record keeps the process it names).
    @Test
    void rewrite_keepsTheRecordedProcess_whenTheAdmissionCarriesNoToken() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.directive(CORE_2, ActivationDirectiveValue.core())
         .record(CORE_2, DepartureState.DEPARTED, E, TOKEN_2, DepartureCause.NODE_REMOVED)
         .seams(s -> s.withCoreView(Option.some(ALL_CORES)))
         .activate();
        run(w, SUSPICION_MS + 2 * TICK_MS);

        assertThat(epochOf(w, N)).isEqualTo(Option.some(E + 1));
        assertThat(w.recordOf(N).map(NodeDepartureValue::process)).isEqualTo(Option.some(process(TOKEN_2)));
    }

    // Red when: a removal that the leader orders after a join whose ADMITTED write is still in its own outbox (the same drain) is dropped as obsolete
    // once that write lands, so a node that membership removed stays admitted.
    @Test
    void removalOrderedAfterAJoin_inTheSameDrain_isStillRecorded() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.directive(CORE_2, ActivationDirectiveValue.core())
         .record(CORE_2, DepartureState.DEPARTED, 1L, TOKEN_2, DepartureCause.NODE_REMOVED)
         .activate();
        w.h.deliverTogether(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2)), new ClusterInput.NodeRemoved(CORE_2));

        assertThat(stateOf(w, CORE_2)).as("the departure stands").isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(epochOf(w, CORE_2)).as("it ends the admission that landed first").isEqualTo(Option.some(2L));
        assertThat(w.counts(CORE_2)).isFalse();
    }

    // Red when: a removal that stopped a core's liveness clock leaves the veto in place after the core left the view and came back: the core never
    // accrues liveness again.
    @Test
    void veto_endsWhenTheCoreLeavesTheView() {
        var w = deadCoreSeenBy(ALL_CORES).activate();

        w.h.deliver(new ClusterInput.NodeRemoved(N));
        w.refreshSeams(s -> s.withCoreView(Option.some(OTHER_CORES)));
        w.refreshSeams(s -> s.withCoreView(Option.some(ALL_CORES)));
        run(w, SUSPICION_MS + 2 * TICK_MS);

        assertThat(epochOf(w, N)).as("listed again for a full window after the removal").isEqualTo(Option.some(E + 1));
    }

    // Red when: a core view that goes unwired keeps the old liveness clocks: the window then ends a window after the first listing, not after the
    // view came back.
    @Test
    void unwiredView_dropsTheClocks() {
        var w = deadCoreSeenBy(ALL_CORES).activate();

        run(w, SUSPICION_MS - 2 * TICK_MS);
        w.refreshSeams(s -> s.withCoreView(Option.none()));
        w.refreshSeams(s -> s.withCoreView(Option.some(ALL_CORES)));
        run(w, 4 * TICK_MS);
        assertThat(w.h.submits(SubmitKind.ADMISSION)).as("not a window after the first listing").isEmpty();
        run(w, SUSPICION_MS);
        assertThat(epochOf(w, N)).as("a window after the view came back").isEqualTo(Option.some(E + 1));
    }

    // Red when: C11f re-admits a departed core (a core is re-admitted by membership's join input, never by a roster or the local alive view).
    @Test
    void c11f_neverReadmitsACore() {
        var w = deadCore().activate();

        w.refreshSeams(s -> s.withLocalAlive(Option.some(Set.of(N))));
        run(w, 3 * TICK_MS);

        assertThat(w.h.submits(SubmitKind.ADMISSION)).isEmpty();
        assertThat(w.counts(N)).isFalse();
    }

    // Red when: `inherited` is not exactly the non-STALE DEPARTED records of the Rebuild snapshot (a STALE record, an ADMITTED record or an absent one is in it).
    @Test
    void rebuild_seedsInherited_fromTheNonStaleDepartedRecordsOnly() {
        var left = node("left-w");
        var stale = node("stale-w");
        var w = MbWorld.world(CORE_1);

        w.record(left, DepartureState.DEPARTED, 1L, 0x7001L, DepartureCause.WORKER_LEAVE)
         .record(stale, DepartureState.DEPARTED, 1L, 0x7002L, DepartureCause.STALE)
         .record(CORE_1, DepartureState.ADMITTED, 1L, 0x1111L, null)
         .activate();

        assertThat(w.h.model().inherited.toList()).containsExactly(left);
        assertThat(w.h.model().joined.isEmpty()).as("joined starts empty").isTrue();
    }

    // Red when: the notification of the very record the Rebuild snapshot already holds takes the node out of `inherited` (only a changed record does).
    @Test
    void inherited_survivesTheRenotificationOfTheSameRecord() {
        var w = lostReadmission(DepartureCause.WORKER_LEAVE, COMMUNITY_WITHOUT_W1);
        var record = w.recordOf(W1).or((NodeDepartureValue) null);

        w.h.deliver(new KvPut(new NodeDepartureKey(W1), record));

        assertThat(w.h.model().inherited.contains(W1)).isTrue();
        w.announce(C, GOV, W1, M1, M2, M3, M4);
        assertThat(epochOf(w, W1)).isEqualTo(Option.some(E + 1));
    }

    // -- C15 / C16: which rows join the node (R-2.11 (a)) ----------------------------------------------------------------------
    // Red when: a join input that an admitting row handles (J0, J3, J5, J6) does not add the id to `joined`.
    @Test
    void joinInput_marksTheNodeJoined_inEveryAdmittingRow() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);
        var fresh = node("fresh-core");

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.admit(CORE_3, 0x3333L);
        w.directive(CORE_1, ActivationDirectiveValue.core())
         .directive(CORE_3, ActivationDirectiveValue.core())
         .record(CORE_3, DepartureState.DEPARTED, 1L, 0x3333L, DepartureCause.NODE_REMOVED)
         .activate();
        w.h.deliver(new ClusterInput.NodeJoined(CORE_1, w.admissionOf(CORE_1)));
        assertThat(w.h.model().joined.contains(CORE_1)).as("J6: a member with its directive").isTrue();
        w.h.deliver(new ClusterInput.NodeJoined(fresh, w.admit(fresh, 0x9L)));
        assertThat(w.h.model().joined.contains(fresh)).as("J5: a fresh id").isTrue();
        w.h.deliver(new ClusterInput.NodeJoined(CORE_3, w.admissionOf(CORE_3)));
        assertThat(w.h.model().joined.contains(CORE_3)).as("J3: a departed same-process core").isTrue();
        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2)));
        assertThat(w.h.model().joined.contains(CORE_2)).as("J0: a join while its departure is in flight").isTrue();
    }

    // Red when: a join that no admitting row handles (J1 refuses a superseded worker, J8 refuses a different process, J7 defers a worker without a
    // directive) adds the id to `joined`, so the DEPARTED record that the refusal leaves standing is superseded.
    @Test
    void joinInput_doesNotMarkTheNodeJoined_whenTheTableRefusesOrDefersIt() {
        var superseded = node("superseded-w");
        var foreign = node("foreign-w");
        var bare = node("bare-w");
        var w = communityOfW1(COMMUNITY);

        w.admit(superseded, 0xA1L);
        w.admit(foreign, 0xA2L);
        w.admit(bare, 0xA3L);
        w.directive(superseded, Fixtures.worker(C))
         .directive(foreign, Fixtures.worker(C))
         .record(superseded, DepartureState.DEPARTED, 1L, 0xA1L, DepartureCause.WORKER_LEAVE)
         .record(foreign, DepartureState.DEPARTED, 1L, 0xA2L, DepartureCause.WORKER_LEAVE)
         .record(bare, DepartureState.DEPARTED, 1L, 0xA3L, DepartureCause.WORKER_LEAVE)
         .seams(s -> s.withSuperseded(Set.of(superseded)))
         .activate();
        w.h.deliver(new ClusterInput.WorkerJoin(superseded, w.admissionOf(superseded)));
        assertThat(w.h.model().joined.contains(superseded)).as("J1").isFalse();
        w.h.deliver(new ClusterInput.WorkerJoin(foreign, new Admission(process(0xFFFFL))));
        assertThat(w.h.model().joined.contains(foreign)).as("J8 worker").isFalse();
        w.h.deliver(new ClusterInput.WorkerJoin(bare, w.admissionOf(bare)));
        assertThat(w.h.model().joined.contains(bare)).as("J7").isFalse();
        assertThat(w.h.submits(SubmitKind.ADMISSION)).isEmpty();
        assertThat(w.counts(superseded) || w.counts(foreign) || w.counts(bare)).isFalse();
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

    // Red when: a core promoted by a seam refresh between two ticks waits for the next tick to be rewritten (the promotion does not request a reconcile).
    @Test
    void nt129c_promotedByASeamRefreshBetweenTicks_isRewrittenAtOnce() {
        var w = deadCoreSeenBy(OTHER_CORES).activate();

        w.h.advance(10_000L);
        w.refreshSeams(s -> s.withCoreView(Option.some(ALL_CORES)));
        w.h.advance(SUSPICION_MS - 9_000L);
        assertThat(w.h.submits(SubmitKind.ADMISSION)).as("nine seconds short of the window").isEmpty();
        w.h.advance(10_000L);
        w.refreshSeams(s -> s.withCoreView(Option.some(ALL_CORES)));

        assertThat(admitsAt(w, N, E + 1)).as("the sample that completes the window rewrites the record at once, between ticks").isTrue();
    }

    // Red when: the leader writes a second WARN or a second ADMITTED for a worker whose C11f write is still in flight (every fresh announcement would).
    @Test
    void nt130a_freshRosters_whileTheReadmissionIsInFlight_writeAndWarnOnce() {
        var w = lostReadmission(DepartureCause.WORKER_LEAVE, COMMUNITY_WITHOUT_W1);

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        for (var i = 0; i < 3; i++) {
            w.announce(C, GOV, W1, M1, M2, M3, M4);
        }

        assertThat(w.h.submits(SubmitKind.ADMISSION)).hasSize(1);
        assertThat(w.h.warns("worker-readmitted-on-evidence")).hasSize(1);
    }

    // Red when: a core view that goes unwired keeps the veto of an earlier removal: the core, listed again, never accrues liveness.
    @Test
    void unwiredView_dropsTheVetoToo() {
        var w = deadCoreSeenBy(ALL_CORES).activate();

        w.h.deliver(new ClusterInput.NodeRemoved(N));
        w.refreshSeams(s -> s.withCoreView(Option.none()));
        w.refreshSeams(s -> s.withCoreView(Option.some(ALL_CORES)));
        run(w, SUSPICION_MS + 2 * TICK_MS);

        assertThat(epochOf(w, N)).isEqualTo(Option.some(E + 1));
    }

    // Red when: an id stays in `inherited` after its record was removed (R-4.3.3).
    @Test
    void inherited_leavesTheSetWhenTheRecordIsRemoved() {
        var w = lostReadmission(DepartureCause.WORKER_LEAVE, COMMUNITY_WITHOUT_W1);

        assertThat(w.h.model().inherited.contains(W1)).isTrue();
        w.h.seedRemove(new ActivationDirectiveKey(W1));
        w.h.drain();

        assertThat(w.recordOf(W1).isEmpty()).isTrue();
        assertThat(w.h.model().inherited.contains(W1)).isFalse();
    }

    // Red when: a second removal input for a node that is already recorded departed submits another C17 (a duplicate DEPARTED over the DEPARTED record).
    @Test
    void duplicateRemoval_ofAnAlreadyDepartedNode_submitsNothing() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.directive(CORE_2, ActivationDirectiveValue.core()).activate();
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        assertThat(stateOf(w, CORE_2)).isEqualTo(Option.some(DepartureState.DEPARTED));
        w.h.clearLog();
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));

        assertThat(w.h.submits()).isEmpty();
    }

    // Red when: a DEPARTED record that arrives for a node that is already joined (the C11c arm of the supersession) does not queue the ADMITTED rewrite
    // in the very step that indexes it, but only at a later reconcile.
    @Test
    void recordPut_ofASupersededDeparted_queuesTheRewriteInTheSameStep() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.directive(CORE_2, ActivationDirectiveValue.core()).activate();
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2)));
        var departed = new NodeDepartureValue(DepartureState.DEPARTED,
                                              0L,
                                              process(TOKEN_2),
                                              Option.some(DepartureCause.NODE_REMOVED),
                                              w.h.wallMs);
        var stepped = w.h.fsm.step(w.h.state, new ClusterInput.KvPut(new NodeDepartureKey(CORE_2), departed));
        var model = ((ClusterState.Active) stepped.state()).model();

        assertThat(model.outbox.stream().anyMatch(draft -> draft.kind() == SubmitKind.ADMISSION)).isTrue();
    }

    // Red when: the flag that tells a localAlive sample from the Rebuild snapshot is reset by the next step (the model copy drops it), so the sample
    // stops being evidence as soon as another input is handled.
    @Test
    void sampledAfterRebuild_isSetByTheFirstSample_andKept() {
        var w = communityOfW1(COMMUNITY).activate();

        assertThat(w.h.model().sampledAfterRebuild).as("the Rebuild snapshot's view is not a sample").isFalse();
        w.refreshSeams(s -> s.withLocalAlive(Option.some(COMMUNITY)));
        assertThat(w.h.model().sampledAfterRebuild).isTrue();
        w.h.advance(2 * TICK_MS);
        assertThat(w.h.model().sampledAfterRebuild).as("kept for the life of the incarnation").isTrue();
    }

    // Red when: a removal input for a node whose DEPARTED record was superseded by a join does not make the node departed again at once (the
    // clearing of `joined` does not recompute `departed`).
    @Test
    void removalInput_ofASupersededNode_makesItDepartedAgainAtOnce() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.directive(CORE_2, ActivationDirectiveValue.core())
         .record(CORE_2, DepartureState.DEPARTED, 1L, TOKEN_2, DepartureCause.NODE_REMOVED)
         .activate();
        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2)));
        assertThat(w.h.model().isDeparted(CORE_2)).as("superseded while its admission is in flight").isFalse();
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));

        assertThat(w.h.model().isDeparted(CORE_2)).as("the removal ends the evidence: departed again").isTrue();
        assertThat(w.counts(CORE_2)).isFalse();
    }

    // Red when: C11f hands the join table the unknown process instead of the worker's admission, so a worker of another known process is re-admitted
    // instead of refused by J8.
    @Test
    void nt130a_aWorkerOfAnotherKnownProcess_isRefusedByJ8_notReadmitted() {
        var w = communityOfW1(COMMUNITY_WITHOUT_W1);

        w.record(W1, DepartureState.DEPARTED, E, 0xDEADL, DepartureCause.WORKER_LEAVE).activate();
        w.announce(C, GOV, W1, M1, M2, M3, M4);

        assertThat(w.h.submits(SubmitKind.ADMISSION)).isEmpty();
        assertThat(w.h.warns("identity-process-mismatch")).isNotEmpty();
        assertThat(w.counts(W1)).isFalse();
    }

    // Red when: a Rebuild snapshot alone promotes a listed core at C1 (a legal window of one nanosecond is already complete at the first tick, never
    // at Rebuild itself).
    @Test
    void rebuild_neverPromotesAListedCore_evenWithTheSmallestLegalWindow() {
        var settings = Settings.settings(FsmHarness.SELF,
                                         DeploymentAtomicity.ALL_OR_NOTHING,
                                         Set.of(),
                                         AbsenceBounds.absenceBounds(255_000_000_000L, 170_000_000_000L, 60_000_000_000L, 1L).unwrap());
        var w = new MbWorld(settings);

        ALL_CORES.forEach(core -> w.cores.add(core));
        w.seams(s -> s.withCountedCores(ALL_CORES).withReady(ALL_CORES).withCoreView(Option.some(ALL_CORES)));
        w.admit(N, TOKEN_2);
        w.directive(N, ActivationDirectiveValue.core())
         .record(N, DepartureState.DEPARTED, E, TOKEN_2, DepartureCause.NODE_REMOVED)
         .activate();

        assertThat(w.h.model().joined.isEmpty()).as("not at Rebuild").isTrue();
        assertThat(w.h.submits(SubmitKind.ADMISSION)).isEmpty();
        assertThat(stateOf(w, N)).isEqualTo(Option.some(DepartureState.DEPARTED));
        w.h.advance(TICK_MS + 1);
        assertThat(epochOf(w, N)).as("at the first tick, once the window has elapsed").isEqualTo(Option.some(E + 1));
    }

    // -- NT-131 (e): a window resolving with a different known process takes J8's core arm (W3', CTO ruling 2026-10-14) -----------------
    private static final long P1 = TOKEN_2;
    private static final long P2 = 0x7777L;

    /// `DEPARTED(E, p1)` for core n; membership admitted another known process p2 for n; n stays in `coreView`. p1 holds the ORDERS row; with
    /// `p2Billing`, p2 holds a BILLING row too. `ordersToken` names the process that wrote the ORDERS row.
    private static MbWorld anotherProcess(long ordersToken, boolean p2Billing) {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, P2);
        w.admit(CORE_3, 0x3333L);
        w.target(ORDERS, 1, "CORE_ONLY")
         .directive(CORE_2, ActivationDirectiveValue.core())
         .instance(CORE_2, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), ordersToken)
         .record(CORE_2, DepartureState.DEPARTED, E, P1, DepartureCause.NODE_REMOVED);
        if (p2Billing) {
            w.target(BILLING, 1, "CORE_ONLY")
             .instance(CORE_2, BILLING, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), P2);
        }

        return w.seams(s -> s.withCoreView(Option.some(ALL_CORES)))
                .activate();
    }

    /// Advance tick by tick, asserting at every step that no row of n written by p2 was removed and that no C17b for n was submitted.
    private static void runKeeping(MbWorld w, long ms, Artifact... p2Rows) {
        for (var spent = 0L; spent < ms; spent += TICK_MS) {
            w.h.advance(Math.min(TICK_MS, ms - spent));
            assertThat(cleanupsOf(w, N)).as("no C17b for n at any point").isZero();
            for (var artifact : p2Rows) {
                assertThat(w.present(new SliceObservedKey(N, artifact))).as("p2's %s row is never removed", artifact).isTrue();
            }
        }
    }

    private static long cleanupsOf(MbWorld w, NodeId node) {
        return w.h.submits(SubmitKind.CLEANUP)
                  .stream()
                  .filter(submit -> submit.transaction()
                                          .mutations()
                                          .stream()
                                          .anyMatch(m -> m.key().equals(new NodeDepartureKey(node))
                                                         || m.key() instanceof SliceObservedKey key && key.nodeId().equals(node)))
                  .count();
    }

    /// Exactly one admission write for n, and it is J8's: `ADMITTED(E + 1, p2)` expecting the observed `DEPARTED(E, p1)`, with one WARN.
    private static void assertJ8CoreArm(MbWorld w) {
        var admissions = w.h.submits(SubmitKind.ADMISSION);

        assertThat(admissions).as("exactly one admission write").hasSize(1);
        var put = admissions.getFirst().transaction().mutations().getFirst();

        assertThat(put.key()).isEqualTo(new NodeDepartureKey(N));
        assertThat(put.expected().map(v -> (NodeDepartureValue) v).map(v -> v.state() + "/" + v.epoch() + "/" + v.process().bootToken()))
                  .as("expects the observed DEPARTED(E, p1)")
                  .isEqualTo(Option.some(DepartureState.DEPARTED + "/" + E + "/" + P1));
        assertThat(put.replacement().map(v -> (NodeDepartureValue) v).map(v -> v.state() + "/" + v.epoch() + "/" + v.process().bootToken()))
                  .as("ADMITTED(E + 1, p2)")
                  .isEqualTo(Option.some(DepartureState.ADMITTED + "/" + (E + 1) + "/" + P2));
        assertThat(w.h.warns("identity-process-mismatch")).as("J8's WARN, once").hasSize(1);
        assertThat(stateOf(w, N)).isEqualTo(Option.some(DepartureState.ADMITTED));
    }

    /// p1's ORDERS row is never counted or adopted: it is departed during the window, and stale (both tokens known, different) after it.
    private static void assertP1RowIgnored(MbWorld w) {
        assertThat(Rows.activeCount(w.h.model(), ORDERS) + Rows.liveCount(w.h.model(), ORDERS)).as("p1's row is not counted").isZero();
        assertThat(w.h.model().hasStray(MbWorld.pair(N, ORDERS))).as("p1's row is not adopted").isFalse();
    }

    // (e) W3', arm A: p1's row on ORDERS, p2's row on BILLING (v-2190 item 2 arm A, flipped).
    // Red unless, after the window, exactly one ADMITTED(E + 1, p2) is written expecting DEPARTED(E, p1) with WARN identity-process-mismatch; red if
    // p2's BILLING row is removed at any point, if any C17b runs for n, or if p1's row is counted or adopted.
    @Test
    void nt131e_differentKnownProcess_takesJ8sCoreArm_andKeepsP2sRows() {
        var w = anotherProcess(P1, true);

        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("deferred during the window").isEmpty();
        assertP1RowIgnored(w);
        runKeeping(w, SUSPICION_MS - 2_000L, BILLING);
        assertThat(w.h.submits(SubmitKind.ADMISSION)).as("not before the window resolved").isEmpty();
        assertP1RowIgnored(w);
        runKeeping(w, 2_000L + 2 * TICK_MS, BILLING);

        assertJ8CoreArm(w);
        assertP1RowIgnored(w);
        assertThat(w.present(new SliceObservedKey(N, ORDERS))).as("p1's stale row stays until n's own N0 overwrites it (J8)").isTrue();
        assertThat(w.counts(N)).as("n is counted, as membership decided").isTrue();
    }

    // (e) W3', arm B: every row of n is p2's (v-2190 item 2 arm B, flipped).
    // Red if a C17b removes p2's ORDERS row after the window, or the resolution writes anything but the one J8 admission.
    @Test
    void nt131e_differentKnownProcess_onlyP2Rows_areNeverRemoved() {
        var w = anotherProcess(P2, false);

        runKeeping(w, SUSPICION_MS + 2 * TICK_MS, ORDERS);

        assertJ8CoreArm(w);
        assertThat(Rows.activeCount(w.h.model(), ORDERS)).as("p2's current row counts once n is admitted").isEqualTo(1);
    }

    // (e) W3', arm C: p2 rewrites its row after the window (v-2190 item 2 arm C, flipped: each rewrite was removed again on the next tick).
    // Red if any rewrite by p2 is removed, or a C17b for n is submitted on any later tick.
    @Test
    void nt131e_differentKnownProcess_p2RewritesItsRow_afterTheWindow_isKept() {
        var w = anotherProcess(P2, false);

        runKeeping(w, SUSPICION_MS + 2 * TICK_MS, ORDERS);
        for (var i = 0; i < 3; i++) {
            w.h.seed(new SliceObservedKey(N, ORDERS), row(SliceObservedState.ACTIVE, 10L + i, gen(1), process(P2)));
            runKeeping(w, TICK_MS, ORDERS);
        }

        assertJ8CoreArm(w);
    }

    // (e) W3', arm D: J8's admission write FAILS at the resolution, and the next reconcile retries it. The reconcile sweeps C17b before it retries the
    // pending joins, so a C17b gated only on an admission in flight would remove p2's rows in that pass.
    // Red if any C17b for n is submitted or any of p2's rows is removed; red unless the retried write commits ADMITTED(E + 1, p2).
    @Test
    void nt131e_differentKnownProcess_failedAdmission_isRetried_andP2sRowsAreKept() {
        var w = anotherProcess(P2, false);

        runKeeping(w, SUSPICION_MS - 2_000L, ORDERS);
        w.h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        runKeeping(w, 2_000L + TICK_MS, ORDERS);
        assertThat(w.h.submits(SubmitKind.ADMISSION)).as("precondition: the resolution's write was submitted").isNotEmpty();
        assertThat(stateOf(w, N)).as("precondition: it failed").isEqualTo(Option.some(DepartureState.DEPARTED));
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        runKeeping(w, 2 * TICK_MS, ORDERS);

        assertThat(w.recordOf(N).map(v -> v.state() + "/" + v.epoch() + "/" + v.process().bootToken()))
                  .isEqualTo(Option.some(DepartureState.ADMITTED + "/" + (E + 1) + "/" + P2));
        assertThat(w.h.warns("identity-process-mismatch")).as("one WARN per write that goes out").isNotEmpty();
    }

    // (e) W3', arm E: while J8's admission write is in flight the DEPARTED record of the other process is still not superseded (R-4.3.0: J8 is not
    // supersession), so n is not counted until that write commits, and no C17b is built meanwhile.
    // Red if n counts before ADMITTED(E + 1, p2) commits, if a C17b is submitted while it is held, or if n is not counted once it commits.
    @Test
    void nt131e_differentKnownProcess_isCountedOnlyWhenTheAdmissionCommits() {
        var w = anotherProcess(P2, false);

        runKeeping(w, SUSPICION_MS - 2_000L, ORDERS);
        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        runKeeping(w, 2_000L, ORDERS);
        var admissions = w.h.submits(SubmitKind.ADMISSION);

        assertThat(admissions).as("precondition: J8's write is in flight").hasSize(1);
        assertThat(stateOf(w, N)).isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(w.counts(N)).as("not counted before the admission commits").isFalse();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.applyHeld(admissions.getFirst().correlationId());
        w.h.drain();

        assertJ8CoreArm(w);
        assertThat(w.counts(N)).as("counted once it commits").isTrue();
        assertThat(cleanupsOf(w, N)).isZero();
    }

    // (e) W3', control: an UNKNOWN process (C8) is not a different one, so the resolution keeps the pre-W3' behaviour: n is joined, the record is
    // superseded on read and rewritten by step 10 as ADMITTED(E + 1) keeping the process the record names; no J8, no C17b, the row is kept and counted.
    // Red if an unknown admission is routed to J8 (WARN identity-process-mismatch, an ADMITTED naming the unknown process) or C17b runs.
    @Test
    void nt131e_unknownProcess_isSupersededAndRewritten_notJ8() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_3, 0x3333L);
        w.target(ORDERS, 1, "CORE_ONLY")
         .directive(CORE_2, ActivationDirectiveValue.core())
         .instance(CORE_2, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), P1)
         .record(CORE_2, DepartureState.DEPARTED, E, P1, DepartureCause.NODE_REMOVED)
         .seams(s -> s.withCoreView(Option.some(ALL_CORES)))
         .activate();
        assertThat(w.h.model().admissions.get(N).or(Admission.UNKNOWN).tokenKnown()).as("precondition: n's process is unknown").isFalse();
        runKeeping(w, SUSPICION_MS + 2 * TICK_MS, ORDERS);

        assertThat(w.h.warns("identity-process-mismatch")).isEmpty();
        assertThat(w.h.submits(SubmitKind.ADMISSION)).as("the step-10 rewrite, once").hasSize(1);
        assertThat(w.recordOf(N).map(v -> v.state() + "/" + v.epoch() + "/" + v.process().bootToken()))
                  .isEqualTo(Option.some(DepartureState.ADMITTED + "/" + (E + 1) + "/" + P1));
        assertThat(Rows.activeCount(w.h.model(), ORDERS)).as("the row is current (C8) and counted").isEqualTo(1);
    }

    // W4. Red when: a removal input processed after Rebuild leaves the worker in `inherited`: a stale fresh announcement then re-admits a worker whose
    // departure the leader has just confirmed (the flicker).
    @Test
    void nt133_aRemovalInput_takesTheWorkerOutOfInherited() {
        var w = lostReadmission(DepartureCause.WORKER_LEAVE, COMMUNITY_WITHOUT_W1);

        assertThat(w.h.model().inherited.contains(W1)).isTrue();
        w.h.deliver(new ClusterInput.WorkerLeave(W1));
        assertThat(w.h.model().inherited.contains(W1)).as("the departure is now this leader's own decision").isFalse();
        w.announce(C, GOV, W1, M1, M2, M3, M4);
        w.refreshSeams(s -> s.withLocalAlive(Option.some(COMMUNITY)));

        assertThat(stateOf(w, W1)).isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(w.h.submits(SubmitKind.ADMISSION)).isEmpty();
        assertThat(w.counts(W1)).isFalse();
        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admissionOf(W1)));
        assertThat(epochOf(w, W1)).as("only a WorkerJoin supersedes it").isEqualTo(Option.some(E + 1));
    }
}
