// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.blueprint.BlueprintId;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.assertj.core.api.Assertions.assertThat;


/// NT-01, NT-02, NT-03, NT-07, NT-91, NT-107: the outer states, the incarnation, the timers and the witness.
class LifecycleTest {
    // NT-01
    // Red when: an input delivered during Rebuild is missing afterwards, or a handler ran on an empty model.
    @Test
    void rebuild_replaysHeldInputs_inOrder_beforeAnyHandlerRuns() {
        var h = leader();
        var target = SliceTargetValue.sliceTargetValue(ORDERS.version(), 3, 1);

        h.step(new ClusterInput.Activate());
        assertThat(h.state).isInstanceOf(ClusterState.Rebuilding.class);
        h.step(new ClusterInput.KvPut(new SliceTargetKey(ORDERS.base()), target));
        h.step(new ClusterInput.KvRemove(new SliceTargetKey(BILLING.base())));
        assertThat(((ClusterState.Rebuilding) h.state).held()).as("held, not handled on an empty model").hasSize(2);
        h.drain();
        assertThat(h.isActive()).isTrue();
        assertThat(h.model().targets.get(ORDERS.base()).isPresent()).as("the input that arrived during Rebuild is in the model")
                  .isTrue();
    }

    // NT-02
    // Red when: submissions after Deactivate > 0, or the tick timer survives.
    @Test
    void deactivate_cancelsTimers_andNothingIsSubmittedAfterwards() {
        var h = active();

        assertThat(h.timers).as("control: the incarnation armed its timers").isNotEmpty();
        h.deliver(new ClusterInput.Deactivate());
        assertThat(h.state).isInstanceOf(ClusterState.Dormant.class);
        assertThat(h.timers).as("the tick timer does not survive").isEmpty();
        assertThat(h.effects(ClusterEffect.Cancel.class).stream().map(cancel -> cancel.timer()
                                                                                      .kind())).contains(TimerId.Kind.TICK,
                                                                                                         TimerId.Kind.POST_ACTIVATION);
        var before = h.submits().size();

        h.advance(120_000L);
        publish(h, blueprint("org.example:app:1.0.0", false, ATTEMPT, slice(ORDERS, 1, 1, Set.of())));
        assertThat(h.submits()).as("no submission after Deactivate").hasSize(before);
    }

    // NT-02 (the rollback arm of ABO#rollbackRefusedWhileTheLeaderDeactivates_isNotResubmitted)
    // Red when: a refusal that arrives after Deactivate is resubmitted.
    @Test
    void deactivate_thenRefusal_isNotResubmitted() {
        var h = active();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        publish(h, blueprint("org.example:app:1.0.0", false, ATTEMPT, slice(ORDERS, 1, 1, Set.of())));
        var held = h.submits(SubmitKind.TARGETS).getFirst();

        h.deliver(new ClusterInput.Deactivate());
        h.policy = FsmHarness.SettlePolicy.APPLY;
        var count = h.submits().size();

        h.settleHeld(held, h.refused(held));
        assertThat(h.submits()).hasSize(count);
    }

    // NT-03
    // Red when: a second Activate rebuilds again.
    @Test
    void secondActivate_isIgnored_andDoesNotRebuild() {
        var h = active();
        var model = h.model();

        h.clearLog();
        h.deliver(new ClusterInput.Activate());
        assertThat(h.effects(ClusterEffect.RequestRebuild.class)).isEmpty();
        assertThat(h.model()).as("the model is the same value: no rebuild").isSameAs(model);
        assertThat(h.state).isInstanceOf(ClusterState.Active.class);
    }

    // NT-07
    // Red when: Rebuild does not schedule exactly one 2 s reconcile + one 30 s tick.
    @Test
    void rebuild_schedulesExactlyOnePostActivationReconcile_andOneTick() {
        var h = leader();

        h.activate();
        var schedules = h.effects(ClusterEffect.Schedule.class);

        assertThat(schedules.stream().filter(s -> s.timer()
                                                   .kind() == TimerId.Kind.POST_ACTIVATION).toList()).singleElement()
                  .satisfies(s -> assertThat(s.delayMs()).isEqualTo(2_000L));
        assertThat(schedules.stream().filter(s -> s.timer()
                                                   .kind() == TimerId.Kind.TICK).toList()).singleElement()
                  .satisfies(s -> assertThat(s.delayMs()).isEqualTo(30_000L));
        assertThat(schedules).hasSize(2);
    }

    // NT-91
    // Red when: when the committed leader is another node, the FSM resubmits anything, or does not enter WitnessStale.
    @Test
    void committedLeaderIsAnotherNode_refusalIsNotResubmitted_andTheFsmIsSuspended() {
        var h = active();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        publish(h, blueprint("org.example:app:1.0.0", false, ATTEMPT, slice(ORDERS, 1, 1, Set.of())));
        var held = h.submits(SubmitKind.TARGETS).getFirst();

        h.commitLeader(CORE_2, 2L);
        h.drain();
        assertThat(((ClusterState.Active) h.state).suspended(h.settings)).as("WitnessStale").isTrue();
        var count = h.submits().size();

        h.settleHeld(held, h.refused(held));
        assertThat(h.submits()).as("nothing resubmitted").hasSize(count);
        assertThat(h.metricTotal("aether_deployment_fsm_submissions_total",
                                 t -> "refused_deposed".equals(t.get("outcome")))).isPositive();
        h.advance(60_000L);
        assertThat(h.submits()).hasSize(count);
    }

    // NT-107
    // Red when: activation before the mirror names self builds a transaction; a CI-16 naming self does not resume; a CI-16 naming another node resumes.
    @Test
    void activationBeforeTheMirrorNamesSelf_buildsNoTransaction_untilTheLeaderValueNamesSelf() {
        var h = FsmHarness.harness();

        h.commitLeader(CORE_2, 1L);
        h.seams = Seams.UNWIRED.withCountedCores(Set.of(CORE_1, CORE_2)).withReady(Set.of(CORE_1, CORE_2));
        h.activate();
        publish(h, blueprint("org.example:app:1.0.0", false, ATTEMPT, slice(ORDERS, 1, 1, Set.of())));
        assertThat(h.submits()).as("no transaction while the mirror names another node").isEmpty();
        h.commitLeader(CORE_3, 2L);
        h.drain();
        h.advance(60_000L);
        assertThat(h.submits()).as("a CI-16 naming another node does not resume").isEmpty();
        h.commitLeader(FsmHarness.SELF, 3L);
        h.drain();
        assertThat(h.submits(SubmitKind.TARGETS)).as("a CI-16 naming self resumes: the open apply is re-driven")
                  .isNotEmpty();
        assertThat(h.submits().getFirst().transaction().leader().viewSequence()).isEqualTo(3L);
    }
}
