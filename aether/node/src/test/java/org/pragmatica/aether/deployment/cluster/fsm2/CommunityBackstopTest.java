// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.CommunityPlacementReconciler;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodePlacementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityPlacementOperationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodePlacementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.MbWorld.C;
import static org.pragmatica.aether.deployment.cluster.fsm2.MbWorld.GOV;
import static org.pragmatica.aether.deployment.cluster.fsm2.MbWorld.tokenOf;
import static org.assertj.core.api.Assertions.assertThat;


/// Community-level absence rules (spec C11, R-2.15, R-2.16, R-2.17, NT-08, NT-09, NT-35, NT-128): the retiring exemption, the
/// freshness requirement, the bounded residual and its reversal, and the community breaker.
class CommunityBackstopTest {
    private static final NodeId M1 = node("m1");
    private static final NodeId M2 = node("m2");
    private static final NodeId M3 = node("m3");
    private static final NodeId M4 = node("m4");
    private static final NodeId M5 = node("m5");
    private static final long BW = ms(bounds().workerRemoval());

    private static final String CONFIG = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [source.pool]
        type = "forge"
        zones = ["old", "new"]
        [source.pool.core]
        count = 3
        [source.pool.worker]
        count = 1
        [community.stable]
        target_size = TARGET
        [community.stable.placement.destination]
        source = "pool"
        zone = "new"
        """;

    private static boolean recordsOrRemoves(MbWorld w, NodeId node) {
        return w.h.submits()
                  .stream()
                  .anyMatch(s -> s.transaction()
                                  .mutations()
                                  .stream()
                                  .anyMatch(m -> switch (m.key()) {
            case NodeDepartureKey k -> k.nodeId().equals(node);
            case SliceObservedKey k -> k.nodeId().equals(node) && m.replacement().isEmpty();
            case SliceDesiredKey k -> k.nodeId().equals(node) && m.replacement().isEmpty();
            case NodeRoutesKey k -> k.nodeId().equals(node) && m.replacement().isEmpty();
            default -> false;
        }));
    }

    /// The real placement reconciler (CPR) over the harness's store; its provider effects are inert stubs.
    private static CommunityPlacementReconciler placement(MbWorld w, Set<NodeId> admitted) {
        var actuator = new CommunityPlacementReconciler.Actuator() {
            @Override
            public Result<String> sourceBinding(SourceName source) {
                return Result.success("binding");
            }

            @Override
            public Promise<Unit> create(CommunityPlacementOperationValue operation) {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Boolean> retirementSafe(CommunityPlacementOperationValue operation) {
                return Promise.success(true);
            }

            @Override
            public Promise<Unit> drain(CommunityPlacementOperationValue operation) {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> terminate(CommunityPlacementOperationValue operation) {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Boolean> previousInstanceExists(CommunityPlacementOperationValue operation) {
                return Promise.success(true);
            }
        };

        return CommunityPlacementReconciler.communityPlacementReconciler(FsmHarness.SELF,
                                                                         w.h.store,
                                                                         commands -> Promise.success(w.h.store.<Object> process(w.h.store.createBatch(commands))),
                                                                         () -> true,
                                                                         node -> true,
                                                                         () -> admitted,
                                                                         () -> 10,
                                                                         actuator,
                                                                         _ -> {},
                                                                         (_, _) -> {},
                                                                         TimeSpan.timeSpan(60_000).millis());
    }

    private static void seedConfig(MbWorld w, int target, long version) {
        w.h.seed(ClusterConfigKey.CURRENT,
                 new ClusterConfigValue(Option.some(CONFIG.replace("TARGET", Integer.toString(target))),
                                        "test",
                                        "1.0.0",
                                        List.of(),
                                        3,
                                        3,
                                        "forge",
                                        version,
                                        0));
    }

    // NT-128 (a)
    // Red when: a retiring community's live worker is recorded: any C17s, NodeDepartureKey write, or removal of its rows, desired
    // keys or routes is submitted by the deployment FSM over 10 x B_w, with D's targetSize written by the REAL reconciler.
    @Test
    void retiringCommunity_liveWorkerIsNeverRecorded_whileTheRealReconcilerRetiresIt() {
        var w = MbWorld.world(CORE_1);
        var w8 = node("w8");

        w.admit(CORE_1, 1L, 0x1111L);
        w.workers("stable", 1, Set.of(M1), w8, M1).target(ORDERS, 1, "WORKERS_ONLY");
        w.instance(w8, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(w8)).routes(w8, ORDERS);
        w.h.seedQuietly(new NodePlacementKey(w8), new NodePlacementValue("pool", Option.some("old"), "old-instance"));
        w.h.seedQuietly(new CommunityKey("stable"),
                        new CommunityValue("pool",
                                           AetherValue.ActivationDirectiveValue.WORKER,
                                           1,
                                           CommunityState.ACTIVE,
                                           FsmHarness.START_WALL,
                                           Option.none()));
        seedConfig(w, 1, 1L);
        w.activate();
        seedConfig(w, 0, 2L);
        placement(w, Set.of(CORE_1, w8, M1)).reconcile().await().unwrap();
        w.h.drain();
        assertThat(w.h.store.getTyped(new CommunityKey("stable"),
                                      CommunityValue.class).map(CommunityValue::targetSize)).as("alignCommunity wrote targetSize = 0 through the applier")
                  .isEqualTo(Option.some(0));
        assertThat(w.h.model().communities.get("stable").map(CommunityValue::targetSize)).as("the FSM saw it")
                  .isEqualTo(Option.some(0));
        w.evolve("stable", GOV, Set.of(M1), List.of(M1), 10 * BW);
        assertThat(recordsOrRemoves(w, w8)).isFalse();
        assertThat(w.inferred()).isEmpty();
        assertThat(w.present(new ActivationDirectiveKey(w8))).as("only the placement reconciler removes it").isTrue();
        assertThat(w.present(new NodePlacementKey(w8))).isTrue();
    }

    // NT-128 (a) defensive arm
    // Red when: a lingering worker of a DISSOLVED community is recorded.
    @Test
    void dissolvedCommunity_lingeringWorkerIsNeverRecorded() {
        var w = MbWorld.world(CORE_1);
        var w8 = node("w8");

        w.admit(CORE_1, 1L, 0x1111L);
        w.workers("gone", 5, Set.of(M1), w8, M1).target(ORDERS, 1, "WORKERS_ONLY");
        w.h.seedQuietly(new CommunityKey("gone"),
                        new CommunityValue("pool",
                                           AetherValue.ActivationDirectiveValue.WORKER,
                                           5,
                                           CommunityState.DISSOLVED,
                                           FsmHarness.START_WALL,
                                           Option.some(1L)));
        w.instance(w8, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(w8)).activate();
        w.evolve("gone", GOV, Set.of(M1), List.of(M1), 10 * BW);
        assertThat(recordsOrRemoves(w, w8)).isFalse();
    }

    // NT-128 (b)
    // Red when: a stale announcement leads to a recording: after the announcement's age passes S_ann, over 10 x B_w; or a fresh Put
    // that arrives once and stops; or, on a cold leader that has seen no Put, absentSince is ever set.
    @Test
    void staleAnnouncement_meansNoRecording() {
        var w = MbWorld.world(CORE_1);
        var w9 = node("w9");

        w.admit(CORE_1, 1L, 0x1111L);
        w.workers(C, 10, Set.of(M1, M2, M3, M4), w9, M1, M2, M3, M4).target(ORDERS, 1, "WORKERS_ONLY");
        w.instance(w9, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(w9));
        w.h.seedQuietly(new GovernorAnnouncementKey(C), roster(GOV, false, M1, M2, M3, M4));
        w.activate();
        for (var i = 0L; i < BW / 30_000L; i++) {
            w.h.advance(30_000L);
            assertThat(w.since(w9)).as("a cold leader: absentSince is never set").isEqualTo(Option.none());
        }

        w.announce(C, GOV, M1, M2, M3, M4);
        assertThat(w.since(w9)).as("the first fresh Put starts the clock").isNotEqualTo(Option.none());
        for (var i = 0L; i < 10 * BW / 30_000L; i++) {
            w.h.advance(30_000L);
        }

        assertThat(w.since(w9)).as("the announcement went stale: the clock is reset").isEqualTo(Option.none());
        assertThat(recordsOrRemoves(w, w9)).isFalse();
    }

    private MbWorld residualWorld() {
        var w = MbWorld.world(CORE_1);
        var w10 = node("w10");
        var all = Set.of(w10, M1, M2, M3, M4);

        w.admit(CORE_1, 1L, 0x1111L);
        w.workers(C, 10, all, w10, M1, M2, M3, M4).target(ORDERS, 1, "WORKERS_ONLY").target(BILLING, 1, "WORKERS_ONLY");
        w.instance(w10, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(w10));
        w.instance(w10, BILLING, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(w10));
        w.h.seedQuietly(new NodePlacementKey(w10), new NodePlacementValue("default", Option.none(), "i-10"));
        w.activate();
        w.announce(C, GOV, w10, M1, M2, M3, M4);
        w.refreshSeams(s -> s.withLocalAlive(Option.some(all)));

        return w;
    }

    // NT-128 (c)
    // Red when: the residual is not reachable (no DEPARTED(STALE)); C17b removes more than slice rows, desired keys and routes; the
    // directive or placement key is touched; a and b are not re-placed with exactly one new goal per affected instance; or, on the
    // governor's correction, C11f does not run J2 (ADMITTED expecting the STALE value), departure-inferred-reversed is not raised
    // (and counted once), w10 is not counted again, or its NodeId or token changed.
    @Test
    void wrongGovernorView_replacesOnce_thenIsReadmittedWhenTheViewHeals() {
        var w10 = node("w10");
        var w = residualWorld();
        var others = List.of(M1, M2, M3, M4);
        var aliveOthers = Set.of(M1, M2, M3, M4);

        w.evolve(C, GOV, aliveOthers, others, 5_000L);
        var since = w.since(w10).or(0L);

        w.evolveUntil(since + bounds().workerRemoval() + 1_000_000L + 31_000_000_000L, C, GOV, aliveOthers, others);
        assertThat(w.recordOf(w10).flatMap(NodeDepartureValue::cause)).as("the residual is reachable")
                  .isEqualTo(Option.some(DepartureCause.STALE));
        assertThat(w.present(new SliceObservedKey(w10, ORDERS)) || w.present(new SliceObservedKey(w10, BILLING))).isFalse();
        assertThat(w.present(new ActivationDirectiveKey(w10)) && w.present(new NodePlacementKey(w10))).as("identity persists")
                  .isTrue();
        for (var artifact : List.of(ORDERS, BILLING)) {
            var elsewhere = others.stream().filter(m -> w.present(new SliceDesiredKey(m, artifact))).toList();

            assertThat(elsewhere).as("exactly one new goal for " + artifact).hasSize(1);
        }

        var tokenBefore = w.h.model().admissions.get(w10).map(Admission::token);
        var reversedBefore = w.h.metricTotal("aether_deployment_fsm_departures_inferred_reversed_total", t -> true);
        var all = List.of(w10, M1, M2, M3, M4);

        w.evolve(C, GOV, Set.copyOf(all), all, 31_000L);
        assertThat(w.recordOf(w10).map(NodeDepartureValue::state)).as("J2 replaced the STALE record")
                  .isEqualTo(Option.some(DepartureState.ADMITTED));
        assertThat(w.h.warns("departure-inferred-reversed")).hasSize(1);
        assertThat(w.h.metricTotal("aether_deployment_fsm_departures_inferred_reversed_total", t -> true)).isEqualTo(reversedBefore + 1);
        assertThat(w.counts(w10)).isTrue();
        assertThat(w.h.model().admissions.get(w10).map(Admission::token)).isEqualTo(tokenBefore);
    }

    // NT-128 (c) variant
    // Red when: the reappearance in localAlive (not in the roster) does not re-admit.
    @Test
    void wrongGovernorView_reappearanceInLocalAlive_alsoReadmits() {
        var w10 = node("w10");
        var w = residualWorld();
        var others = List.of(M1, M2, M3, M4);
        var aliveOthers = Set.of(M1, M2, M3, M4);

        w.evolve(C, GOV, aliveOthers, others, 5_000L);
        var since = w.since(w10).or(0L);

        w.evolveUntil(since + bounds().workerRemoval() + 1_000_000L + 31_000_000_000L, C, GOV, aliveOthers, others);
        assertThat(w.recordOf(w10).flatMap(NodeDepartureValue::cause)).isEqualTo(Option.some(DepartureCause.STALE));
        w.refreshSeams(s -> s.withLocalAlive(Option.some(Set.of(w10, M1, M2, M3, M4))));
        w.h.drain();
        assertThat(w.recordOf(w10).map(NodeDepartureValue::state)).isEqualTo(Option.some(DepartureState.ADMITTED));
    }

    private MbWorld sixWorkers() {
        var w = MbWorld.world(CORE_1);
        var members = new NodeId[]{node("e1"), node("e2"), node("e3"), node("e4"), node("e5"), node("e6")};

        w.admit(CORE_1, 1L, 0x1111L);
        w.workers("e-comm", 10, Set.of(members), members).target(ORDERS, 1, "WORKERS_ONLY");
        for (var member : members) {
            w.instance(member, BILLING, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(member));
        }

        w.activate();

        return w;
    }

    // NT-128 (d)
    // Red when: any C17s is submitted for any worker of E while the breaker holds; departure-inferred-suppressed is not raised
    // once; the suppressed counter is not incremented; or, when the roster heals to omit one worker, recording does not resume
    // after that worker's own B_w.
    @Test
    void breaker_suppressesMassRecording_thenResumesWhenItClears() {
        var w = sixWorkers();
        var e = List.of(node("e1"), node("e2"), node("e3"), node("e4"), node("e5"), node("e6"));
        var present = List.of(e.get(4), e.get(5));

        w.evolve("e-comm", GOV, Set.copyOf(present), present, 3 * BW);
        assertThat(w.inferred()).isEmpty();
        assertThat(w.h.warns("departure-inferred-suppressed")).hasSize(1);
        assertThat(w.h.metricTotal("aether_deployment_fsm_departures_inferred_suppressed_total",
                                   t -> "e-comm".equals(t.get("community")))).isGreaterThan(0L);
        var healed = List.of(e.get(1), e.get(2), e.get(3), e.get(4), e.get(5));

        w.evolve("e-comm", GOV, Set.copyOf(healed), healed, 31_000L + 30_000L);
        assertThat(w.inferred().stream().map(s -> s.transaction()
                                                   .key())).as("the one still absent worker is recorded once its own B_w elapsed")
                  .containsExactly(new NodeDepartureKey(e.get(0)));
    }

    // NT-128 floor arm
    // Red when: with a two-member community and one absent worker (below the floor of 2) no C17s fires after B_w.
    @Test
    void breaker_floorOfTwo_doesNotHoldBackASingleAbsentWorker() {
        var w = MbWorld.world(CORE_1);
        var f1 = node("f1");
        var f2 = node("f2");

        w.admit(CORE_1, 1L, 0x1111L);
        w.workers("f-comm", 10, Set.of(f1, f2), f1, f2).target(ORDERS, 1, "WORKERS_ONLY");
        w.instance(f1, BILLING, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(f1)).activate();
        w.evolve("f-comm", GOV, Set.of(f2), List.of(f2), BW + 31_000L + 30_000L);
        assertThat(w.inferred().stream().map(s -> s.transaction()
                                                   .key())).containsExactly(new NodeDepartureKey(f1));
    }

    // NT-08
    // Red when: a worker that is in no roster and not alive is counted while the roster signal exists (control: in a roster ->
    // counted); or, while a guard holds, any of its rows or its directive is removed or it gets a C17s: absent <= B_w since
    // activation; present in any signal; its class unjudgeable; its community retiring; or its community's breaker holding.
    @Test
    void restoredWorker_guards_hold() {
        var w = MbWorld.world(CORE_1);
        var wx = node("wx");

        w.admit(CORE_1, 1L, 0x1111L);
        w.workers(C, 10, Set.of(M1, M2), wx, M1, M2).target(ORDERS, 1, "WORKERS_ONLY");
        w.instance(wx, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(wx)).activate();
        w.announce(C, GOV, M1, M2);
        w.refreshSeams(s -> s.withLocalAlive(Option.some(Set.of(M1, M2))));
        assertThat(w.counts(wx)).as("in no roster, not alive: not counted").isFalse();
        w.evolve(C, GOV, Set.of(M1, M2), List.of(M1, M2), BW - 5_000L);
        assertThat(recordsOrRemoves(w, wx)).as("absent <= B_w since activation").isFalse();
        assertThat(w.present(new ActivationDirectiveKey(wx))).isTrue();
        w.evolve(C, GOV, Set.of(M1, M2, wx), List.of(M1, M2), 40_000L);
        assertThat(w.counts(wx)).as("present in localAlive: counted").isTrue();
        assertThat(recordsOrRemoves(w, wx)).isFalse();
        w.evolve(C, GOV, Set.of(M1, M2), List.of(M1, M2, wx), 40_000L);
        assertThat(w.counts(wx)).as("control: in a roster -> counted").isTrue();
    }

    // NT-08 (stray rows)
    // Red when: a stray row on a non-counting worker is adopted or condemned; control: in a roster the stray is handled.
    @Test
    void strayRow_onANonCountingWorker_isNeitherAdoptedNorCondemned() {
        var w = MbWorld.world(CORE_1);
        var wx = node("wx");

        w.admit(CORE_1, 1L, 0x1111L);
        w.workers(C, 10, Set.of(M1, M2), wx, M1, M2).target(ORDERS, 1, "WORKERS_ONLY");
        w.h.seedQuietly(new SliceObservedKey(wx, ORDERS),
                        row(SliceObservedState.ACTIVE, 1L, gen(1), process(tokenOf(wx))));
        w.h.seedQuietly(new GovernorAnnouncementKey(C), roster(GOV, false, M1, M2));
        w.activate();
        w.announce(C, GOV, M1, M2);
        w.refreshSeams(s -> s.withLocalAlive(Option.some(Set.of(M1, M2))));
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.present(new SliceDesiredKey(wx, ORDERS))).as("not adopted").isFalse();
        assertThat(w.h.warns("stray-instance")).as("not condemned").isEmpty();
        w.evolve(C, GOV, Set.of(M1, M2), List.of(M1, M2, wx), 40_000L);
        assertThat(w.present(new SliceDesiredKey(wx, ORDERS))).as("control: counted -> adopted").isTrue();
    }

    // NT-09
    // Red when: a roster put dropping a member yields no replacement goal.
    @Test
    void rosterPutDroppingAMember_yieldsAReplacementGoal() {
        var w = MbWorld.world(CORE_1);
        var spare = M3;

        w.admit(CORE_1, 1L, 0x1111L);
        w.workers(C, 10, Set.of(M1, M2, spare), M1, M2, spare).target(ORDERS, 2, "WORKERS_ONLY");
        w.instance(M1, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(M1));
        w.instance(M2, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(M2));
        w.activate();
        w.announce(C, GOV, M1, M2, spare);
        w.refreshSeams(s -> s.withLocalAlive(Option.some(Set.of(M1, M2, spare))));
        assertThat(w.present(new SliceDesiredKey(spare, ORDERS))).isFalse();
        w.refreshSeams(s -> s.withLocalAlive(Option.some(Set.of(M1, spare))));
        w.announce(C, GOV, M1, spare);
        assertThat(w.present(new SliceDesiredKey(spare, ORDERS))).as("the dropped member's instance no longer counts: replaced")
                  .isTrue();
    }

    // NT-35
    // Red when: the sentinel (unwired core view) does not suppress the sweeps, or a stale core's rows are removed without a
    // prior committed STALE record.
    @Test
    void staleCore_rowsGoOnlyAfterACommittedStaleRecord() {
        var w = MbWorld.world(CORE_1, CORE_2);

        w.admit(CORE_1, 1L, 0x1111L);
        w.admit(CORE_2, 1L, 0x2222L);
        w.directive(CORE_2,
                    ActivationDirectiveValue.core())
         .target(ORDERS, 1, "CORE_ONLY")
         .instance(CORE_2,
                   ORDERS,
                   SliceGoal.ACTIVE,
                   SliceObservedState.ACTIVE,
                   gen(1),
                   0x2222L)
         .record(CORE_2, DepartureState.ADMITTED, 1L, 0x2222L, null);
        w.seams(s -> s.withCoreView(Option.some(Set.of(CORE_1))));
        w.activate();
        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.advance(ms(bounds().coreRemoval()) + 31_000L);
        assertThat(w.inferred()).hasSize(1);
        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("no row removal before the STALE record commits").isEmpty();
        assertThat(w.present(new SliceObservedKey(CORE_2, ORDERS))).isTrue();
        w.h.applyHeld(w.inferred().getFirst().correlationId());
        w.h.drain();
        assertThat(w.h.submits(SubmitKind.CLEANUP)).hasSize(1);
    }

    // NT-128 breaker boundary arms
    // Red when: the breaker holds at exactly half (at least half instead of more than half), or at a single member (floor of 2
    // lowered to 1): recording is then held back where the spec records.
    @Test
    void breaker_exactlyHalf_andASingleMemberCommunity_arePastTheBreaker() {
        var four = MbWorld.world(CORE_1);
        var g = List.of(node("g1"), node("g2"), node("g3"), node("g4"));

        four.admit(CORE_1, 1L, 0x1111L);
        four.workers("g-comm", 10, Set.copyOf(g), g.toArray(new NodeId[0])).target(ORDERS, 1, "WORKERS_ONLY");
        for (var member : g) {
            four.instance(member, BILLING, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(member));
        }

        four.activate();
        var present = List.of(g.get(2), g.get(3));

        four.evolve("g-comm", GOV, Set.copyOf(present), present, BW + 31_000L + 30_000L);
        assertThat(four.inferred().stream().map(s -> s.transaction()
                                                      .key())).as("2 of 4 is exactly half: not more than half, the breaker does not hold")
                  .containsExactlyInAnyOrder(new NodeDepartureKey(g.get(0)),
                                             new NodeDepartureKey(g.get(1)));
        var single = MbWorld.world(CORE_1);
        var only = node("only");

        single.admit(CORE_1, 1L, 0x1111L);
        single.workers("s-comm", 10, Set.of(), only).target(ORDERS, 1, "WORKERS_ONLY");
        single.instance(only, BILLING, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(only)).activate();
        single.evolve("s-comm", GOV, Set.of(), List.of(M1), BW + 31_000L + 30_000L);
        assertThat(single.inferred().stream().map(s -> s.transaction()
                                                        .key())).as("the floor of 2 covers a single-worker community")
                  .containsExactly(new NodeDepartureKey(only));
    }
}
