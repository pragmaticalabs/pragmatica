// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// #3193 (owner ruling 2026-11-14): a departed WORKER's directive is retired by the FSM itself, in C17b, as LEGACY retires it at
/// `NodeRemoved`. The governor's roster is derived from the directives, so the directive's removal is what drops the worker from the roster
/// and what `COMMUNITY_MEMBER_LEFT` is projected from. Its commit takes R-4.3.3, which retires the record. A core's directive stays (R-4.3.1),
/// and a worker that comes back is directed again (#3028).
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

    // Red when: the departed worker keeps its directive (no C17b carries it), or its record is not retired after the directive goes.
    @Test
    void workerLeave_retiresTheDirective_andThenTheRecord() {
        var w = world();

        w.h.deliver(new ClusterInput.WorkerLeave(worker));
        settle(w);

        assertThat(w.h.submits(SubmitKind.CLEANUP)
                      .stream()
                      .flatMap(submit -> submit.transaction().mutations().stream())
                      .anyMatch(mutation -> mutation.key().equals(new ActivationDirectiveKey(worker))
                                            && mutation.replacement().isEmpty()))
                  .as("C17b removes the worker's directive at its exact value")
                  .isTrue();
        assertThat(w.present(new ActivationDirectiveKey(worker))).as("the directive is retired").isFalse();
        assertThat(w.recordOf(worker).isPresent()).as("R-4.3.3 retires the record once the directive is gone").isFalse();
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
}
