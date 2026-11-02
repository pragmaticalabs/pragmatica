// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.MbWorld.C;
import static org.pragmatica.aether.deployment.cluster.fsm2.MbWorld.GOV;
import static org.pragmatica.aether.deployment.cluster.fsm2.MbWorld.tokenOf;
import static org.assertj.core.api.Assertions.assertThat;


/// Process identity (spec C6, C8, corrected Q12, NT-123 a/b/d, NT-125): a row is stale only on positive evidence, a restart is
/// a new identity, and a leader that starts knowing no token changes nothing.
class IncarnationTest {
    private static final NodeId W1 = node("w1");
    private static final NodeId W2 = node("w2");
    private static final NodeId W3 = node("w3");
    private static final long P1 = 0xA001L;
    private static final long P2 = 0xB002L;

    // NT-123 (a)
    // Red when: w2 is not minted (J5) and placeable; w1's departure does not produce C17 then C17b removing w1's rows; any w1 row
    // is counted, adopted or routed to after DEPARTED commits; or placed(a) is not restored by a fresh goal.
    @Test
    void restart_isANewIdentity_theOldOneDeparts_andTheSliceIsReplaced() {
        var w = MbWorld.worldWithFloor(1, CORE_1);

        w.admit(CORE_1, 0x1111L);
        w.admit(W1, P1);
        w.community(C, 10, CommunityState.ACTIVE).directive(W1, Fixtures.worker(C)).target(ORDERS, 1, "WORKERS_ONLY");
        w.instance(W1, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(7), P1);
        w.h.seedQuietly(new GovernorAnnouncementKey(C), roster(GOV, false, W1));
        w.seams(s -> s.withCoreView(Option.some(Set.of(CORE_1)))
                      .withReady(Set.of(CORE_1, W1, W2))
                      .withLocalAlive(Option.some(Set.of(W1))));
        w.activate();
        w.h.deliver(new ClusterInput.WorkerLeave(W1));
        assertThat(w.h.submits(SubmitKind.DEPARTURE)).hasSize(1);
        assertThat(w.h.submits(SubmitKind.CLEANUP)).hasSize(1);
        assertThat(w.present(new SliceObservedKey(W1, ORDERS))).as("C17b removed w1's rows").isFalse();
        assertThat(Rows.activeCount(w.h.model(), ORDERS)).as("no w1 row counts after DEPARTED").isZero();
        assertThat(w.present(new SliceDesiredKey(W1, ORDERS))).as("not adopted").isFalse();
        w.h.deliver(new ClusterInput.WorkerJoin(W2, w.admit(W2, P2)));
        assertThat(w.stored(new ActivationDirectiveKey(W2)).isPresent()).as("w2 is minted (J5)").isTrue();
        w.announce(C, GOV, W2);
        w.refreshSeams(s -> s.withLocalAlive(Option.some(Set.of(W2))));
        assertThat(w.stored(new SliceDesiredKey(W2, ORDERS)).map(v -> ((SliceDesiredValue) v).goal())).as("placeable: placed(a) restored by a fresh goal")
                  .isEqualTo(Option.some(SliceGoal.LOADED));
        assertThat(Rows.placed(w.h.model(), ORDERS)).isEqualTo(1);
    }

    // NT-123 (b), worker
    // Red when: a WorkerJoin carrying p2 != p1 under a departed id produces any write, directive or count (J8).
    @Test
    void differentProcess_workerRejoin_leavesNoTrace() {
        var w = MbWorld.world(CORE_1);

        w.admit(CORE_1, 0x1111L);
        w.admit(W1, P1);
        w.community(C, 10, CommunityState.ACTIVE)
         .directive(W1,
                    Fixtures.worker(C))
         .record(W1, DepartureState.DEPARTED, 1L, P1, DepartureCause.WORKER_LEAVE)
         .activate();
        w.h.clearLog();
        w.tokens.admit(W1, P2);
        w.h.deliver(new ClusterInput.WorkerJoin(W1, new Admission(process(P2))));
        assertThat(w.h.submits()).isEmpty();
        assertThat(w.counts(W1)).isFalse();
    }

    // NT-123 (b), core and p1 rows
    // Red when: for cores any refusal of admission (C5); w1's p1 rows are counted, adopted, occupy the pair.
    @Test
    void differentProcess_coreRejoin_isAdmitted_andTheOldRowsDoNotCount() {
        var w = MbWorld.world(CORE_1, CORE_2);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, P1);
        w.directive(CORE_2, ActivationDirectiveValue.core()).target(ORDERS, 1, "CORE_ONLY");
        w.h.seedQuietly(new SliceObservedKey(CORE_2, ORDERS), row(SliceObservedState.ACTIVE, 1L, gen(7), process(P1)));
        w.activate();
        w.tokens.admit(CORE_2, P2);
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, new Admission(process(P2))));
        var pair = MbWorld.pair(CORE_2, ORDERS);

        assertThat(Rows.current(w.h.model(), pair).isEmpty()).as("a stale row reads as absent").isTrue();
        assertThat(w.present(new SliceDesiredKey(CORE_2, ORDERS))).as("the pair is vacant: placement writes a fresh goal over it")
                  .isTrue();
        assertThat(w.h.model().hasStray(pair)).isFalse();
        assertThat(Rows.liveCount(w.h.model(), ORDERS)).isZero();
    }

    // NT-123 (b), unknown arm (C8)
    // Red when: with the leader's registry holding no token for w1, the p1 rows are not counted and occupying the pair, or
    // anything is written for them.
    @Test
    void unknownToken_rowsCount_andNothingIsWritten() {
        var w = MbWorld.world(CORE_1);

        w.admit(CORE_1, 0x1111L);
        w.admit(W1, 0L);
        w.community(C, 10, CommunityState.ACTIVE).directive(W1, Fixtures.worker(C)).target(ORDERS, 1, "WORKERS_ONLY");
        w.instance(W1, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(7), P1).activate();
        w.h.advance(100_000L);
        assertThat(w.tokens.tokenOf(W1)).as("the real registry knows nothing").isZero();
        assertThat(Rows.activeCount(w.h.model(), ORDERS)).isEqualTo(1);
        assertThat(Rows.occupies(w.h.model(), MbWorld.pair(W1, ORDERS))).isTrue();
        assertThat(w.h.submits().stream().filter(s -> s.kind() != SubmitKind.COMMUNITY)).as("nothing is written for the rows")
                  .isEmpty();
    }

    // NT-123 (d)
    // Red when: the same process p1, joined again after its departure, does not keep its rows current, or the leader fails to adopt them.
    @Test
    void partitionHeal_sameProcess_keepsRowsCurrent_andTheLeaderAdoptsThem() {
        var w = MbWorld.worldWithFloor(1, CORE_1);

        w.admit(CORE_1, 0x1111L);
        w.admit(W1, P1);
        w.community(C, 10, CommunityState.ACTIVE).directive(W1, Fixtures.worker(C)).target(ORDERS, 1, "WORKERS_ONLY");
        w.instance(W1, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(7), P1);
        w.activate();
        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.WorkerLeave(W1));
        w.h.applyHeld(w.h.submits(SubmitKind.DEPARTURE).getFirst().correlationId());
        w.h.drain();
        var cleanup = w.h.submits(SubmitKind.CLEANUP).getFirst();

        assertThat(w.present(new SliceDesiredKey(W1, ORDERS))).as("C17 removed the desired key").isFalse();
        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admit(W1, P1)));
        w.h.applyHeld(w.h.submits(SubmitKind.ADMISSION).getLast().correlationId());
        w.h.drain();
        w.h.applyHeld(cleanup.correlationId());
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.refreshSeams(s -> s.withLocalAlive(Option.some(Set.of(W1))));
        w.announce(C, GOV, W1);
        for (var held : List.copyOf(w.h.held.values())) {
            w.h.applyHeld(held.correlationId());
            w.h.drain();
        }

        assertThat(w.present(new SliceObservedKey(W1, ORDERS))).as("the late C17b was refused: the rows are kept")
                  .isTrue();
        var adopted = w.stored(new SliceDesiredKey(W1, ORDERS)).map(v -> (SliceDesiredValue) v);

        assertThat(adopted.map(SliceDesiredValue::generation)).as("adopted at the row's own generation")
                  .isEqualTo(Option.some(gen(7)));
        assertThat(adopted.map(SliceDesiredValue::goal)).isEqualTo(Option.some(SliceGoal.ACTIVE));
    }

    // -- NT-125 -----------------------------------------------------------------------------------------------------
    private static final List<NodeId> WORKERS = List.of(W1, W2, W3);

    /// Three workers and two cores with ACTIVE rows stamped with their real tokens, desired keys committed, verdict SUCCEEDED.
    private MbWorld clusterBeforeTheNewLeader(boolean seamPresent) {
        var w = MbWorld.world(CORE_1, CORE_2);
        var alive = Set.of(W1, W2, W3);

        w.workersUnadmitted(C, 10, alive, W1, W2, W3).target(ORDERS, 5, "ALL");
        for (var node : List.of(W1, W2, W3, CORE_1, CORE_2)) {
            w.instance(node,
                       ORDERS,
                       SliceGoal.ACTIVE,
                       SliceObservedState.ACTIVE,
                       gen(3),
                       0x5000L + node.id().hashCode() % 0x100);
        }

        w.h.seedQuietly(new DeploymentOutcomeKey(blueprintId("org.example:app:1.0.0")),
                        DeploymentOutcomeValue.succeeded(FsmHarness.START_WALL, 1L, "a1"));
        w.h.seedQuietly(new GovernorAnnouncementKey(C), roster(GOV, false, W1, W2, W3));
        if (seamPresent) {
            for (var node : List.of(W1, W2, W3, CORE_1, CORE_2)) {
                w.admit(node, 0L);
            }
        }

        return w;
    }

    private static void assertNothingChanged(MbWorld w, String arm) {
        var model = w.h.model();

        assertThat(Rows.activeCount(model, ORDERS)).as(arm + ": activeCount").isEqualTo(5);
        assertThat(Rows.liveCount(model, ORDERS)).as(arm + ": liveCount").isEqualTo(5);
        assertThat(Rows.placed(model, ORDERS)).as(arm + ": placed").isEqualTo(5);
        assertThat(w.h.submits().stream().filter(s -> s.kind() != SubmitKind.COMMUNITY)).as(arm
                                                                                           + ": no goal, no removal, nothing submitted")
                  .isEmpty();
        assertThat(w.h.metricTotal("aether_deployment_fsm_stale_rows_total", t -> true)).as(arm + ": stale_rows_total")
                  .isZero();
        assertThat(w.stored(new DeploymentOutcomeKey(blueprintId("org.example:app:1.0.0")))
                    .map(v -> ((DeploymentOutcomeValue) v).status())).as(arm + ": verdict")
                  .isEqualTo(Option.some(DeploymentOutcomeStatus.SUCCEEDED));
    }

    // NT-125 (leader start, tokens unknown; same tokens learned; different token learned)
    // Red when: through Rebuild and the first three reconcile ticks any row is classified stale; liveCount, activeCount, placed
    // or the verdict differ; any goal or Remove is submitted; stale_rows_total > 0. Same tokens learned: anything changes or is
    // written. A different token learned for one worker: its rows still count, any is removed, or anything but a placement goal
    // is submitted.
    @Test
    void leaderStart_withUnknownTokens_changesNothing_andLearningATokenRemovesNothing() {
        for (var seamPresent : new boolean[]{true, false}) {
            var arm = seamPresent
                      ? "seam present but unrefreshed"
                      : "seam absent";
            var w = clusterBeforeTheNewLeader(seamPresent);

            w.activate();
            w.h.advance(3 * w.h.settings.timing().tick() + 1_000L);
            assertNothingChanged(w, arm);
            for (var node : List.of(W1, W2, W3)) {
                assertThat(Rows.isStale(w.h.model(),
                                        MbWorld.pair(node, ORDERS),
                                        w.h.model().observed
                                           .get(MbWorld.pair(node, ORDERS))
                                           .or(row(SliceObservedState.ACTIVE,
                                                   1L,
                                                   gen(1),
                                                   process(1L))))).as(arm)
                          .isFalse();
            }

            for (var node : List.of(W1, W2, W3, CORE_1, CORE_2)) {
                var rowToken = w.h.model().observed
                                  .get(MbWorld.pair(node, ORDERS))
                                  .map(row -> row.incarnation()
                                                 .bootToken())
                                  .or(0L);

                w.h.deliver(new ClusterInput.ProcessKnown(node, rowToken));
            }

            assertNothingChanged(w, arm + ", same tokens learned");
            w.h.deliver(new ClusterInput.ProcessKnown(W1, 0x7777_7777L));
            assertThat(Rows.activeCount(w.h.model(),
                                        ORDERS)).as(arm + ": the different-token row no longer counts")
                      .isEqualTo(4);
            assertThat(w.present(new SliceObservedKey(W1, ORDERS))).as(arm + ": and is not removed").isTrue();
            assertThat(w.h.submits()
                          .stream()
                          .flatMap(s -> s.transaction()
                                         .mutations()
                                         .stream())
                          .filter(m -> m.replacement()
                                        .isEmpty())).isEmpty();
            assertThat(w.h.metricTotal("aether_deployment_fsm_stale_rows_total", t -> true)).isGreaterThan(0L);
        }
    }

    // NT-125 (the arm the exact-equality mutation must turn red): with every token unknown, no row reads stale at Rebuild.
    // Red when: R-1.6.2 compares tokens by exact equality (draft 5.1) instead of on positive evidence.
    @Test
    void unknownIsNotStale_atRebuild() {
        var w = clusterBeforeTheNewLeader(true);

        w.activate();
        assertThat(WORKERS.stream()
                          .map(node -> Rows.current(w.h.model(),
                                                    MbWorld.pair(node, ORDERS))
                                           .isPresent())
                          .toList()).containsOnly(true);
    }
}
