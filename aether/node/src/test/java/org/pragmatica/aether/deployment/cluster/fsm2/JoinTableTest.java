// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.config.cluster.CommunityPlacement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// The join table (spec C15, C16, J0 to J9) and the fenced late-departure sequences (R-4.3.0), on the real applier and with
/// a real `BootTokens` registry behind every admission.
class JoinTableTest {
    private static final NodeId W1 = node("worker-1");
    private static final long A = 0xAAAAL;
    private static final long B = 0xBBBBL;

    private MbWorld world() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 1L, 0x1111L);
        w.admit(CORE_2, 1L, A);
        w.admit(CORE_3, 1L, 0x3333L);

        return w;
    }

    private static boolean touches(ClusterEffect.Submit submit, Class<?> keyType) {
        return submit.transaction()
                     .mutations()
                     .stream()
                     .anyMatch(m -> keyType.isInstance(m.key()));
    }

    private static Option<NodeDepartureValue> written(ClusterEffect.Submit submit) {
        return submit.transaction()
                     .mutations()
                     .stream()
                     .filter(m -> m.key() instanceof NodeDepartureKey)
                     .findFirst()
                     .flatMap(m -> m.replacement()
                                    .stream()
                                    .findFirst())
                     .map(NodeDepartureValue.class::cast)
                     .map(Option::some)
                     .orElse(Option.none());
    }

    // NT-88 J0 + NT-121 (iii)
    // Red when: with C17 in flight there is no ADMITTED write expecting what the departure write expected.
    @Test
    void j0_joinDuringDeparture_writesAdmittedExpectingWhatTheDepartureExpected() {
        var w = world().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admit(CORE_2, 2L, A)));
        var admission = w.h.submits(SubmitKind.ADMISSION);

        assertThat(admission).hasSize(1);
        var mutation = admission.getFirst().transaction().mutations().getFirst();

        assertThat(mutation.expected().isEmpty()).as("the departure expected an absent record").isTrue();
        assertThat(written(admission.getFirst()).map(NodeDepartureValue::state)).isEqualTo(Option.some(DepartureState.ADMITTED));
        assertThat(written(admission.getFirst()).map(NodeDepartureValue::epoch)).isEqualTo(Option.some(2L));
    }

    // NT-88 J1 worker
    // Red when: any write.
    @Test
    void j1_supersededWorker_isRefusedWithoutAnyWrite() {
        var w = world().seams(s -> s.withSuperseded(Set.of(W1))).activate();

        w.h.clearLog();
        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admit(W1, 1L, B)));
        assertThat(w.h.submits()).isEmpty();
        assertThat(w.counts(W1)).isFalse();
    }

    // NT-88 J1 core
    // Red when: any refusal of admission, or a CORE directive assigned to a superseded id (M-6).
    @Test
    void j1_supersededCore_isAdmitted_butGetsNoCoreDirective() {
        var w = MbWorld.world(CORE_1, CORE_3);

        w.seams(s -> s.withSuperseded(Set.of(CORE_3))).activate();
        w.h.clearLog();
        w.h.deliver(new ClusterInput.NodeJoined(CORE_3, w.admit(CORE_3, 1L, 0x3333L)));
        assertThat(w.h.submits().stream().anyMatch(s -> touches(s, ActivationDirectiveKey.class))).isFalse();
        assertThat(w.h.model().admission(CORE_3).isPresent()).as("admission is recorded").isTrue();
        assertThat(w.counts(CORE_3)).as("membership decided: the core counts").isTrue();
    }

    // NT-88 J2
    // Red when: a STALE record (from C17s) is not replaced by ADMITTED when the worker joins.
    @Test
    void j2_staleRecord_isReplacedByAdmitted_onWorkerJoin() {
        var w = world();

        w.admit(W1, 1L, B);
        w.community("c1", 5, CommunityState.ACTIVE)
         .directive(W1,
                    Fixtures.worker("c1"))
         .record(W1, DepartureState.DEPARTED, 1L, B, DepartureCause.STALE)
         .activate();
        w.h.clearLog();
        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admit(W1, 1L, B)));
        assertThat(w.recordOf(W1).map(NodeDepartureValue::state)).isEqualTo(Option.some(DepartureState.ADMITTED));
        assertThat(w.h.submits()
                      .getFirst()
                      .transaction()
                      .mutations()
                      .getFirst()
                      .expected()
                      .map(v -> ((NodeDepartureValue) v).cause())).as("expecting the STALE value")
                  .isEqualTo(Option.some(Option.some(DepartureCause.STALE)));
        assertThat(w.counts(W1)).isTrue();
    }

    // NT-88 J3
    // Red when: a same-process worker (same token, higher epoch) is not re-admitted (fenced ADMITTED replacement, witnessed
    // on the directive), or is minted, or is not counted.
    @Test
    void j3_sameProcessWorker_isReadmitted_fenced_withoutMint() {
        var w = world();

        w.admit(W1, 1L, B);
        w.community("c1", 5, CommunityState.ACTIVE)
         .directive(W1,
                    Fixtures.worker("c1"))
         .record(W1, DepartureState.DEPARTED, 1L, B, DepartureCause.WORKER_LEAVE)
         .activate();
        w.h.clearLog();
        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admit(W1, 2L, B)));
        var submit = w.h.submits(SubmitKind.ADMISSION).getFirst();

        assertThat(submit.transaction().mutations().getFirst().expected().map(v -> ((NodeDepartureValue) v).state())).isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(submit.transaction().guards()).as("witnessed on the directive")
                  .anyMatch(g -> g.key() instanceof ActivationDirectiveKey);
        assertThat(w.h.submits().stream().anyMatch(s -> touches(s, CommunityKey.class))).as("no mint").isFalse();
        assertThat(w.counts(W1)).isTrue();
    }

    // NT-88 J3 unknown-token arm
    // Red when: a worker whose admission token is 0 and whose DEPARTED record carries a known token is not re-admitted
    // (unknown is the same process, C8).
    @Test
    void j3_unknownToken_isTheSameProcess_andIsReadmitted() {
        var w = world();

        w.community("c1", 5, CommunityState.ACTIVE)
         .directive(W1,
                    Fixtures.worker("c1"))
         .record(W1, DepartureState.DEPARTED, 1L, B, DepartureCause.WORKER_LEAVE)
         .activate();
        var unknown = w.admit(W1, 2L, 0L);

        assertThat(unknown.tokenKnown()).as("the real registry has recorded no token").isFalse();
        w.h.deliver(new ClusterInput.WorkerJoin(W1, unknown));
        assertThat(w.recordOf(W1).map(NodeDepartureValue::state)).isEqualTo(Option.some(DepartureState.ADMITTED));
    }

    // NT-88 J8 worker
    // Red when: a known-different-token WorkerJoin under a departed id produces any write, directive or mint, or counts n.
    @Test
    void j8_differentProcessWorker_isRefused() {
        var w = world();

        w.admit(W1, 1L, A);
        w.community("c1", 5, CommunityState.ACTIVE)
         .directive(W1,
                    Fixtures.worker("c1"))
         .record(W1, DepartureState.DEPARTED, 1L, A, DepartureCause.WORKER_LEAVE)
         .activate();
        w.h.clearLog();
        w.tokens.admit(W1, B);
        w.h.deliver(new ClusterInput.WorkerJoin(W1, new Admission(2L, process(B))));
        assertThat(w.h.submits()).isEmpty();
        assertThat(w.h.warns("identity-process-mismatch")).hasSize(1);
        assertThat(w.counts(W1)).isFalse();
    }

    // NT-88 J8 core
    // Red when: any refusal of admission, no fenced ADMITTED(e, p) write, n left uncounted, or n's earlier rows adopted or
    // counted (N4-2).
    @Test
    void j8_differentProcessCore_isRecorded_andItsEarlierRowsAreStale() {
        var w = world();

        w.directive(CORE_2,
                    ActivationDirectiveValue.core())
         .target(ORDERS, 1, "CORE_ONLY")
         .record(CORE_2, DepartureState.DEPARTED, 1L, A, DepartureCause.NODE_REMOVED)
         .instance(CORE_2,
                   ORDERS,
                   SliceGoal.ACTIVE,
                   SliceObservedState.ACTIVE,
                   gen(1),
                   A)
         .activate();
        w.h.clearLog();
        w.tokens.admit(CORE_2, B);
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, new Admission(2L, process(B))));
        assertThat(w.recordOf(CORE_2).map(NodeDepartureValue::state)).isEqualTo(Option.some(DepartureState.ADMITTED));
        assertThat(w.recordOf(CORE_2).map(r -> r.process()
                                                .bootToken())).isEqualTo(Option.some(B));
        assertThat(w.counts(CORE_2)).isTrue();
        assertThat(Rows.liveCount(w.h.model(),
                                  ORDERS) + Rows.activeCount(w.h.model(),
                                                             ORDERS)).as("the process-A row is not counted")
                  .isZero();
        assertThat(w.h.model().hasStray(MbWorld.pair(CORE_2, ORDERS))).as("a stale row is never a stray").isFalse();
        assertThat(w.h.warns("identity-process-mismatch")).hasSize(1);
    }

    // NT-88 J5
    // Red when: a fresh NodeId gets no assign (core) or no mint (worker).
    @Test
    void j5_freshIds_getADirective_andAWorkerGetsItsCommunity() {
        var w = world().activate();
        var fresh = node("core-9");

        w.h.clearLog();
        w.h.deliver(new ClusterInput.NodeJoined(fresh, w.admit(fresh, 1L, 0x9L)));
        assertThat(w.stored(new ActivationDirectiveKey(fresh)).map(v -> ((ActivationDirectiveValue) v).role())).isEqualTo(Option.some(ActivationDirectiveValue.CORE));
        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admit(W1, 1L, B)));
        assertThat(w.stored(new ActivationDirectiveKey(W1)).map(v -> ((ActivationDirectiveValue) v).role())).isEqualTo(Option.some(ActivationDirectiveValue.WORKER));
        assertThat(w.present(new CommunityKey("default-w-0"))).as("the community is minted with the directive").isTrue();
    }

    // NT-88 J6
    // Red when: any write for a node that already has its directive.
    @Test
    void j6_nodeWithDirective_isNoop() {
        var w = world();

        w.directive(CORE_2, ActivationDirectiveValue.core()).activate();
        w.h.clearLog();
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2, 1L)));
        assertThat(w.h.submits()).isEmpty();
    }

    // NT-88 J9
    // Red when: an unmatched combination (a same-process DEPARTED with an equal epoch) produces a write, produces no
    // join-unmatched WARN, or is not re-evaluated on the next tick.
    @Test
    void j9_unmatchedCombination_warns_writesNothing_andIsRetriedOnTheTick() {
        var w = world();

        w.directive(CORE_2,
                    ActivationDirectiveValue.core())
         .record(CORE_2, DepartureState.DEPARTED, 2L, A, DepartureCause.NODE_REMOVED)
         .activate();
        w.h.clearLog();
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admit(CORE_2, 2L, A)));
        assertThat(w.h.submits()).isEmpty();
        assertThat(w.h.warns("join-unmatched")).hasSize(1);
        w.h.advance(w.h.settings.timing().tick() + 1);
        assertThat(w.h.warns("join-unmatched").size()).as("evaluated again on the tick").isGreaterThan(1);
    }

    // NT-121 (i)
    // Red when: a departed same-process worker with no directive is dropped instead of deferred (J7), or not minted after
    // the record goes. (The record outlives its worker only while residue remains, R-4.3.5: here an unremoved row.)
    @Test
    void j7_departedWorkerWithoutDirective_isDeferred_thenMinted_whenTheRecordGoes() {
        var w = world();

        w.record(W1, DepartureState.DEPARTED, 1L, B, DepartureCause.WORKER_LEAVE)
         .instance(W1,
                   ORDERS,
                   SliceGoal.ACTIVE,
                   SliceObservedState.ACTIVE,
                   gen(1),
                   B)
         .target(ORDERS, 1, "CORE_ONLY");
        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.activate();
        w.h.clearLog();
        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admit(W1, 1L, B)));
        assertThat(w.h.submits(SubmitKind.DIRECTIVE)).as("deferred: no mint while the record stands").isEmpty();
        assertThat(w.h.submits(SubmitKind.ADMISSION)).as("deferred: no write while the record stands").isEmpty();
        assertThat(w.h.warns("join-unmatched")).as("J7 defers silently; only J9 warns").isEmpty();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.seedRemove(new NodeDepartureKey(W1));
        w.h.drain();
        assertThat(w.present(new ActivationDirectiveKey(W1))).as("minted once the record is gone").isTrue();
    }

    // NT-121 (ii)
    // Red when: a worker of a DISSOLVING community becomes counted or placeable after re-admission.
    @Test
    void j3_dissolvingCommunity_leavesTheReadmittedWorkerUncounted() {
        var w = world();

        w.admit(W1, 1L, B);
        w.community("gone", 5, CommunityState.DISSOLVING)
         .directive(W1,
                    Fixtures.worker("gone"))
         .record(W1, DepartureState.DEPARTED, 1L, B, DepartureCause.WORKER_LEAVE)
         .target(ORDERS, 1, "WORKERS_ONLY")
         .activate();
        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admit(W1, 2L, B)));
        assertThat(w.counts(W1)).isFalse();
        assertThat(w.present(new SliceDesiredKey(W1, ORDERS))).isFalse();
    }

    // NT-124 order A
    // Red when: the late C17 applies any mutation (record, desired removal, route removal); n leaves the counting set after
    // ADMITTED commits; C17b runs; or C17 is resubmitted after the re-read shows ADMITTED.
    @Test
    void lateDeparture_afterTheAdmission_isRefusedAsAWhole_orderA() {
        var w = MbWorld.world(CORE_1, CORE_2);

        w.admit(CORE_1, 1L, 0x1111L);
        w.admit(CORE_2, 1L, A);
        w.target(ORDERS, 1, "CORE_ONLY")
         .directive(CORE_2,
                    ActivationDirectiveValue.core())
         .instance(CORE_2,
                   ORDERS,
                   SliceGoal.ACTIVE,
                   SliceObservedState.ACTIVE,
                   gen(1),
                   A)
         .routes(CORE_2, ORDERS)
         .activate();
        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        var departure = w.h.submits(SubmitKind.DEPARTURE).getFirst();

        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admit(CORE_2, 2L, A)));
        var admission = w.h.submits(SubmitKind.ADMISSION).getFirst();

        w.h.applyHeld(admission.correlationId());
        w.h.drain();
        assertThat(w.counts(CORE_2)).as("counted from the ADMITTED commit").isTrue();
        w.h.settleHeld(departure, new Settlement.Failed("lost"));
        assertThat(w.counts(CORE_2)).isTrue();
        var late = w.h.applyHeld(departure.correlationId());

        w.h.drain();
        assertThat(late).as("the applier refuses the whole late transaction").isInstanceOf(Settlement.Refused.class);
        assertThat(w.recordOf(CORE_2).map(NodeDepartureValue::state)).isEqualTo(Option.some(DepartureState.ADMITTED));
        assertThat(w.present(new SliceDesiredKey(CORE_2, ORDERS))).isTrue();
        assertThat(w.present(new NodeRoutesKey(CORE_2, ORDERS))).isTrue();
        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("C17b never runs").isEmpty();
        assertThat(w.h.submits(SubmitKind.DEPARTURE)).as("not resubmitted").hasSize(1);
        assertThat(w.counts(CORE_2)).isTrue();
    }

    // NT-124 order B
    // Red when: n is not counted from the read in which the record is ignorable, the record is not replaced by
    // ADMITTED(e2) expecting DEPARTED(e1), or C17b applies after the replacement.
    @Test
    void lateDeparture_committedBeforeTheAdmission_isIgnoredOnRead_orderB() {
        var w = MbWorld.world(CORE_1, CORE_2);

        w.admit(CORE_1, 1L, 0x1111L);
        w.admit(CORE_2, 1L, A);
        w.target(ORDERS, 1, "CORE_ONLY")
         .directive(CORE_2,
                    ActivationDirectiveValue.core())
         .instance(CORE_2,
                   ORDERS,
                   SliceGoal.ACTIVE,
                   SliceObservedState.ACTIVE,
                   gen(1),
                   A)
         .activate();
        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        var departure = w.h.submits(SubmitKind.DEPARTURE).getFirst();

        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admit(CORE_2, 2L, A)));
        var first = w.h.submits(SubmitKind.ADMISSION).getFirst();

        w.h.applyHeld(departure.correlationId());
        w.h.drain();
        var refused = w.h.applyHeld(first.correlationId());

        assertThat(refused).isInstanceOf(Settlement.Refused.class);
        w.h.settleHeld(first, refused);
        assertThat(w.counts(CORE_2)).as("counted from that read: DEPARTED(e1) < e2, same process").isTrue();
        var rewrite = w.h.submits(SubmitKind.ADMISSION).getLast();
        var mutation = rewrite.transaction().mutations().getFirst();

        assertThat(mutation.expected().map(v -> ((NodeDepartureValue) v).state())).isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(mutation.replacement().map(v -> ((NodeDepartureValue) v).epoch())).isEqualTo(Option.some(2L));
        w.h.applyHeld(rewrite.correlationId());
        w.h.drain();
        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("no C17b for an ignored record, nor after the replacement")
                  .isEmpty();
        assertThat(w.present(new SliceObservedKey(CORE_2, ORDERS))).isTrue();
    }

    // NT-124 control
    // Red when: with no re-admission the late C17 does not land as a genuine departure followed by C17b.
    @Test
    void lateDeparture_withoutReadmission_landsAsAGenuineDeparture() {
        var w = MbWorld.world(CORE_1, CORE_2);

        w.admit(CORE_1, 1L, 0x1111L);
        w.admit(CORE_2, 1L, A);
        w.target(ORDERS, 1, "CORE_ONLY")
         .directive(CORE_2,
                    ActivationDirectiveValue.core())
         .instance(CORE_2,
                   ORDERS,
                   SliceGoal.ACTIVE,
                   SliceObservedState.ACTIVE,
                   gen(1),
                   A)
         .activate();
        w.h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        var departure = w.h.submits(SubmitKind.DEPARTURE).getFirst();

        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.applyHeld(departure.correlationId());
        w.h.drain();
        assertThat(w.recordOf(CORE_2).map(NodeDepartureValue::state)).isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(w.present(new SliceObservedKey(CORE_2, ORDERS))).as("C17b ran").isFalse();
    }

    // NT-36
    // Red when: a seed node gets a CORE directive (or a non-seed node does not).
    @Test
    void coreJoin_seedGetsNoDirective_nonSeedDoes() {
        var w = MbWorld.worldWithSeeds(Set.of(CORE_1), CORE_1, CORE_2);

        w.activate();
        w.h.deliver(new ClusterInput.NodeJoined(CORE_1, w.admit(CORE_1, 1L, 0x1111L)));
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admit(CORE_2, 1L, A)));
        assertThat(w.present(new ActivationDirectiveKey(CORE_1))).isFalse();
        assertThat(w.present(new ActivationDirectiveKey(CORE_2))).isTrue();
    }

    // NT-37
    // Red when: there is no 2 s retry when nothing is allocatable after a core joined.
    @Test
    void coreJoin_withNothingAllocatable_retriesIn2Seconds() {
        var w = MbWorld.world(CORE_2).seams(s -> s.withReady(Set.of()));

        w.activate();
        w.h.clearLog();
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admit(CORE_2, 1L, A)));
        var retry = w.h.effects(ClusterEffect.Schedule.class)
                       .stream()
                       .filter(s -> s.timer()
                                     .subject()
                                     .equals("join-retry"))
                       .toList();

        assertThat(retry).hasSize(1);
        assertThat(retry.getFirst().delayMs()).isEqualTo(2_000L);
        var passes = w.h.metricTotal("aether_deployment_fsm_reconcile_passes_total", t -> true);

        w.h.advance(2_000L);
        assertThat(w.h.metricTotal("aether_deployment_fsm_reconcile_passes_total", t -> true)).isGreaterThan(passes);
    }

    private static final CommunityPlacement.Location POOL = new CommunityPlacement.Location("pool", Option.none(), 0, 1);

    private Map<String, CommunityPlacement> policies() {
        return Map.of("a",
                      new CommunityPlacement("a", 4, List.of(POOL)),
                      "b",
                      new CommunityPlacement("b", 4, List.of(POOL)));
    }

    private String choiceOf(boolean departedDirectiveKept) {
        var w = world();
        var live = node("wa-live");
        var gone = node("wa-gone");
        var other = node("wb-live");
        var fresh = node("w-new");

        w.community("a", 4, CommunityState.ACTIVE)
         .community("b", 4, CommunityState.ACTIVE)
         .directive(live,
                    Fixtures.worker("a"))
         .directive(other,
                    Fixtures.worker("b"));
        if (departedDirectiveKept) {
            w.directive(gone,
                        Fixtures.worker("a"))
             .record(gone, DepartureState.DEPARTED, 1L, 0x77L, DepartureCause.WORKER_LEAVE);
        }

        w.seams(s -> s.withCommunityPolicies(policies())
                      .withMemberSource(Map.of(fresh, "pool"))).activate();
        w.h.deliver(new ClusterInput.WorkerJoin(fresh, w.admit(fresh, 1L, 0x55L)));

        return w.stored(new ActivationDirectiveKey(fresh))
                .map(v -> ((ActivationDirectiveValue) v).communityId())
                .or("none");
    }

    // NT-39
    // Red when: the least-filled choice is wrong; or a departed id whose directive is kept (Q1) is counted, so that the
    // choice differs from the one made with that directive absent.
    @Test
    void workerMint_choosesTheLeastFilledCommunity_andIgnoresDepartedIds() {
        var withDeparted = choiceOf(true);
        var without = choiceOf(false);

        assertThat(without).isEqualTo("a");
        assertThat(withDeparted).as("the departed id's directive does not change the choice").isEqualTo(without);
    }

    // NT-39 (least filled)
    // Red when: the more-filled community is chosen.
    @Test
    void workerMint_prefersTheLessFilledCommunity() {
        var w = world();
        var fresh = node("w-new");

        w.community("a", 4, CommunityState.ACTIVE)
         .community("b", 4, CommunityState.ACTIVE)
         .directive(node("a1"),
                    Fixtures.worker("a"))
         .directive(node("a2"),
                    Fixtures.worker("a"))
         .directive(node("b1"),
                    Fixtures.worker("b"));
        w.seams(s -> s.withCommunityPolicies(policies())
                      .withMemberSource(Map.of(fresh, "pool"))).activate();
        w.h.deliver(new ClusterInput.WorkerJoin(fresh, w.admit(fresh, 1L, 0x55L)));
        assertThat(w.stored(new ActivationDirectiveKey(fresh)).map(v -> ((ActivationDirectiveValue) v).communityId())).isEqualTo(Option.some("b"));
    }

    // NT-40
    // Red when: a non-WORKER directive leaves n out of the recorded set; or a directive remove for a departed n does not
    // collect the record.
    @Test
    void directives_nonWorkerStaysRecorded_andRemovalOfADepartedNodeCollectsTheRecord() {
        var w = world();

        w.directive(CORE_2, ActivationDirectiveValue.core()).activate();
        assertThat(w.h.model().directives.containsKey(CORE_2)).as("a non-WORKER directive is recorded").isTrue();
        assertThat(Rows.recordedWorker(w.h.model(), CORE_2)).isFalse();
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        assertThat(w.recordOf(CORE_2).isPresent()).isTrue();
        w.h.clearLog();
        w.h.seedRemove(new ActivationDirectiveKey(CORE_2));
        w.h.drain();
        assertThat(w.h.submits(SubmitKind.DIRECTIVE_REMOVAL).stream().anyMatch(s -> touches(s, NodeDepartureKey.class))).as("R-4.3.3: one transaction removes the record")
                  .isTrue();
        assertThat(w.recordOf(CORE_2).isEmpty()).as("R-4.3.3: the record goes with the directive").isTrue();
    }
}
