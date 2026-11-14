// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// #3193 (CTO rulings f407aba5dd, 6b6de53d51): a departed WORKER's directive is retired by the FSM itself, in C17b, as LEGACY retires it
/// at `NodeRemoved`. The governor's roster is derived from the directives, so the directive's removal is what drops the worker from the
/// roster and what `COMMUNITY_MEMBER_LEFT` is projected from. The record goes in the same transaction, its exact-value removal being the
/// guard (ruling item 3, design 12 item 4b), so no leader change can leave it behind. A core's directive stays (R-4.3.1). Removal is
/// terminal for the id; a process admitted under it again is a new admission, which J5 directs (#3028).
class WorkerDepartureRetirementTest {
    private static final long TOKEN = 0x4444L;
    private final NodeId worker = node("worker-1");

    private MbWorld world() {
        var w = MbWorld.world(CORE_1);

        w.admit(worker, TOKEN);

        return w.community("c1", 5, CommunityState.ACTIVE)
                .directive(worker, Fixtures.worker("c1"))
                .activate();
    }

    private static void settle(MbWorld w) {
        w.h.drain();
        w.h.advance(w.h.settings.timing().tick() * 3);
        w.h.drain();
    }

    private static boolean removes(ClusterEffect.Submit submit, AetherKey key) {
        return submit.transaction()
                     .mutations()
                     .stream()
                     .anyMatch(mutation -> mutation.key().equals(key) && mutation.replacement().isEmpty() && mutation.expected().isPresent());
    }

    private long directiveRemovals(MbWorld w) {
        return w.h.submits()
                  .stream()
                  .filter(submit -> removes(submit, new ActivationDirectiveKey(worker)))
                  .count();
    }

    // Red when: the departed worker keeps its directive (no C17b carries it), or its record is not retired in the SAME C17b
    // (ruling 6b6de53d51 item 3: the old two-transaction model left the record to a later R-4.3.3).
    @Test
    void workerLeave_retiresTheDirective_andTheRecord_inOneTransaction() {
        var w = world();

        w.h.deliver(new ClusterInput.WorkerLeave(worker));
        settle(w);

        assertThat(w.h.submits(SubmitKind.CLEANUP)
                      .stream()
                      .anyMatch(submit -> removes(submit, new ActivationDirectiveKey(worker))
                                          && removes(submit, new NodeDepartureKey(worker))))
                  .as("one C17b removes the worker's directive and its record, each at its exact value")
                  .isTrue();
        assertThat(w.h.submits(SubmitKind.DIRECTIVE_REMOVAL)).as("no R-4.3.3 follows: the record is already gone").isEmpty();
        assertThat(w.present(new ActivationDirectiveKey(worker))).as("the directive is retired").isFalse();
        assertThat(w.recordOf(worker).isPresent()).as("the record is retired with it").isFalse();
    }

    // F2 — Red when: the leader is deposed after C17b commits and before anything else commits, and the record survives the successor
    // (the two-transaction model left R-4.3.3 to the deposed leader, and no reconcile step derives it again, NT-106).
    @Test
    void leaderChange_afterC17bCommits_leavesNoRecordBehind() {
        var w = world();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.WorkerLeave(worker));
        w.h.applyHeld(w.h.submits(SubmitKind.DEPARTURE).getFirst().correlationId());
        w.h.drain();
        settle(w);
        var cleanup = w.h.submits(SubmitKind.CLEANUP);

        assertThat(cleanup).as("C17b submitted and held").isNotEmpty();
        assertThat(cleanup).as("every C17b carries the record's removal")
                  .allMatch(submit -> removes(submit, new NodeDepartureKey(worker)));

        w.h.deliver(new ClusterInput.Deactivate());
        assertThat(w.h.applyHeld(cleanup.getFirst().correlationId())).as("C17b commits as its leader goes")
                  .isInstanceOf(Settlement.Accepted.class);
        w.h.commitLeader(FsmHarness.SELF, 2L);
        w.h.drain();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.activate();
        settle(w);

        assertThat(w.present(new ActivationDirectiveKey(worker))).as("the directive is retired").isFalse();
        assertThat(w.recordOf(worker).isPresent()).as("the successor finds no orphaned record").isFalse();
    }

    // v3227a p2, one-transaction model — Red when: a committed C17b leaves the record standing without the directive (the window the
    // two-transaction model opened until R-4.3.3), or the same process admitted again under the retired id is not directed and counted.
    @Test
    void rejoinAfterC17b_findsNoWindow_andIsDirectedAgain() {
        var w = world();

        w.h.loseKinds = Set.of(SubmitKind.DIRECTIVE_REMOVAL);
        w.h.deliver(new ClusterInput.WorkerLeave(worker));
        w.h.drain();

        assertThat(w.present(new ActivationDirectiveKey(worker))).isFalse();
        assertThat(w.recordOf(worker).isPresent()).as("no window: the record went with the directive, no R-4.3.3 needed").isFalse();

        w.h.loseKinds = Set.of();
        w.h.deliver(new ClusterInput.WorkerJoin(worker, w.admit(worker, TOKEN)));
        settle(w);

        assertThat(w.stored(new ActivationDirectiveKey(worker))
                    .map(value -> ((ActivationDirectiveValue) value).role()))
                  .as("the new admission is directed as WORKER")
                  .isEqualTo(Option.some(ActivationDirectiveValue.WORKER));
        assertThat(w.counts(worker)).as("and counted").isTrue();
    }

    // v3227a p3 — Red when: a C17b derived before a same-process rejoin committed is applied after it and deletes the rejoined worker's
    // directive; the record's exact-value removal (expecting DEPARTED, now ADMITTED) must refuse the whole transaction.
    @Test
    void rejoinWhileC17bInFlight_staleC17bIsRefused_andTheDirectiveSurvives() {
        var w = world();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.WorkerLeave(worker));
        w.h.applyHeld(w.h.submits(SubmitKind.DEPARTURE).getFirst().correlationId());
        w.h.drain();
        settle(w);
        var cleanup = w.h.submits(SubmitKind.CLEANUP);

        assertThat(cleanup).as("C17b submitted and held").isNotEmpty();

        w.h.deliver(new ClusterInput.WorkerJoin(worker, w.admit(worker, TOKEN)));
        w.h.drain();
        var admission = w.h.submits(SubmitKind.ADMISSION);

        assertThat(admission).as("J3 submitted").isNotEmpty();
        w.h.applyHeld(admission.getLast().correlationId());
        w.h.drain();
        cleanup.forEach(stale -> assertThat(w.h.applyHeld(stale.correlationId())).as("the stale C17b is refused")
                                                                                  .isInstanceOf(Settlement.Refused.class));
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        settle(w);

        assertThat(w.present(new ActivationDirectiveKey(worker))).as("the directive survives the stale C17b").isTrue();
        assertThat(w.recordOf(worker).map(NodeDepartureValue::state)).as("the rejoin's record survives it too")
                  .isEqualTo(Option.some(DepartureState.ADMITTED));
    }

    // v3227a p4 — Red when: a duplicate WorkerLeave submits a second directive removal, or a WorkerLeave of an unknown node submits a
    // directive mutation or a record removal.
    @Test
    void duplicateAndUnknownDeparture_retireNothingTwice() {
        var w = world();

        w.h.deliver(new ClusterInput.WorkerLeave(worker));
        settle(w);
        w.h.deliver(new ClusterInput.WorkerLeave(worker));
        settle(w);

        assertThat(directiveRemovals(w)).as("exactly one directive removal").isEqualTo(1L);

        var ghost = node("ghost-9");

        w.h.clearLog();
        w.h.deliver(new ClusterInput.WorkerLeave(ghost));
        settle(w);

        assertThat(w.h.submits()
                      .stream()
                      .flatMap(submit -> submit.transaction().mutations().stream())
                      .anyMatch(mutation -> mutation.key() instanceof ActivationDirectiveKey
                                            || mutation.key() instanceof NodeDepartureKey && mutation.replacement().isEmpty()))
                  .as("no directive mutation and no record removal for an unknown node")
                  .isFalse();
    }

    // Red when: a departed core loses its directive (the removal is not limited to WORKER directives).
    @Test
    void coreDeparture_keepsTheDirective() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, 0x2222L);
        w.admit(CORE_3, 0x3333L);
        w.directive(CORE_2, ActivationDirectiveValue.core()).activate();
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        settle(w);

        assertThat(w.recordOf(CORE_2).map(NodeDepartureValue::state)).isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(w.present(new ActivationDirectiveKey(CORE_2))).as("a core's directive waits for source-confirmed termination").isTrue();
    }

    // #3028 — Red when: a worker that comes back after its directive was retired is not directed again.
    @Test
    void returningWorker_afterRetirement_isDirectedAgain() {
        var w = world();

        w.h.deliver(new ClusterInput.WorkerLeave(worker));
        settle(w);
        assertThat(w.present(new ActivationDirectiveKey(worker))).isFalse();

        w.h.deliver(new ClusterInput.WorkerJoin(worker, w.admit(worker, TOKEN + 1)));
        settle(w);

        assertThat(w.stored(new ActivationDirectiveKey(worker))
                    .map(value -> ((ActivationDirectiveValue) value).role()))
                  .as("J5 assigns the returning worker a WORKER directive again (its community is J5's choice, not c1's)")
                  .isEqualTo(Option.some(ActivationDirectiveValue.WORKER));
    }

    // #3028 — Red when: a falsely departed worker that rejoins (same process) before C17b loses its directive.
    @Test
    void falselyDepartedWorker_rejoiningBeforeC17b_keepsItsDirective() {
        var w = world();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.WorkerLeave(worker));
        var departure = w.h.submits(SubmitKind.DEPARTURE).getFirst().correlationId();

        w.h.deliver(new ClusterInput.WorkerJoin(worker, w.admit(worker, TOKEN)));
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.applyHeld(departure);
        settle(w);

        assertThat(w.present(new ActivationDirectiveKey(worker))).as("the rejoined worker keeps its directive").isTrue();
        assertThat(w.recordOf(worker).map(NodeDepartureValue::state)).isNotEqualTo(Option.some(DepartureState.DEPARTED));
    }

    // #3193 kill symptom (ruling f407aba5dd) — Red when: C17b keeps the directive of an INFERRED (DEPARTED, STALE) worker departure, so the
    // governor roster never drops it and COMMUNITY_MEMBER_LEFT is never projected.
    @Test
    void inferredStaleDeparture_retiresTheDirective_andTheRecord() {
        var w = MbWorld.world(CORE_1);

        w.admit(worker, TOKEN);
        w.community("c1", 5, CommunityState.ACTIVE)
         .directive(worker, Fixtures.worker("c1"))
         .record(worker, DepartureState.DEPARTED, 0L, TOKEN, DepartureCause.STALE)
         .activate();
        settle(w);

        assertThat(w.present(new ActivationDirectiveKey(worker))).as("the inferred departure's directive is retired").isFalse();
        assertThat(w.recordOf(worker).isPresent()).as("C17b retires the record with the directive").isFalse();
    }
}
