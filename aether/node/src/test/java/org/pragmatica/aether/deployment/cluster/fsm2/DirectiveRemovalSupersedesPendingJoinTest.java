// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;
import java.util.function.Consumer;

import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;

/// #3603: a worker's join stays pending until its directive is observed and the next reconcile pass finds it. A directive removal handled
/// between that observation and that pass (the removal is already in the mailbox when the Put is handled, so the pass the Put queues runs
/// after it) left the pending join to derive the directive again behind the removal.
///
/// Every writer that reads the pending join is a trigger here: the first reconcile pass (`Joins.retryPending`), and a departure record's Put
/// or Remove (`DepartureFlow.onRecordPut` / `onRecordRemove`, which call `Joins.reevaluate`), delivered between the removal and that pass.
///
/// The input order is production's: the join, the directive's commit (Put) and its settlement, the directive's removal, then the first
/// reconcile pass, which the Put queued at the mailbox tail.
class DirectiveRemovalSupersedesPendingJoinTest {
    private static final NodeId WORKER = node("removed-directive-worker");

    private static MbWorld world() {
        return MbWorld.world(CORE_1, CORE_2, CORE_3)
                      .seams(seams -> seams.withMemberSource(Map.of(WORKER, "default")))
                      .activate();
    }

    private static boolean writesDirective(ClusterEffect.Submit submit) {
        return submit.transaction()
                     .mutations()
                     .stream()
                     .anyMatch(mutation -> mutation.key() instanceof ActivationDirectiveKey);
    }

    private static NodeDepartureValue admitted(MbWorld w) {
        return new NodeDepartureValue(DepartureState.ADMITTED, 1L, w.admissionOf(WORKER).process(), Option.none(), FsmHarness.START_WALL);
    }

    /// The join, the directive's commit (Put) and settlement, the removal, then `inWindow` (delivered before the pass the Put queued), then the drain.
    private static MbWorld removedBeforeTheFirstPass(MbWorld w, Consumer<MbWorld> inWindow) {
        var key = new ActivationDirectiveKey(WORKER);

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admit(WORKER, 21L)));

        var assignment = w.h.held.values()
                                 .stream()
                                 .filter(DirectiveRemovalSupersedesPendingJoinTest::writesDirective)
                                 .findFirst()
                                 .orElseThrow();

        w.h.held.remove(assignment.correlationId());
        w.h.applyTransaction(assignment);
        w.h.settle(assignment, new ClusterInput.Settlement.Accepted());
        w.h.seedRemove(key);
        inWindow.accept(w);
        w.h.drain();

        return w;
    }

    private static void thenAssertNotCreatedAgain(MbWorld w) {
        assertThat(w.h.store.get(new ActivationDirectiveKey(WORKER))).as("the directive stays removed").isEqualTo(Option.none());
        assertThat(w.h.held.values()
                          .stream()
                          .filter(DirectiveRemovalSupersedesPendingJoinTest::writesDirective)).as("no second directive write is queued behind the removal")
                                                                                              .isEmpty();
        assertThat(w.h.mutations(ActivationDirectiveKey.class)).as("the join wrote the directive once").hasSize(1);
    }

    // Red when: the removed directive is written again by the first reconcile pass (Put, Remove, first pass).
    @Test
    void directiveRemovedBeforeTheFirstReconcilePass_isNotCreatedAgainByThePendingJoin() {
        thenAssertNotCreatedAgain(removedBeforeTheFirstPass(world(), _ -> {}));
    }

    // Red when: a departure record's Put between the removal and the first pass re-evaluates the pending join and writes the directive again.
    @Test
    void directiveRemoved_thenARecordPut_isNotCreatedAgainByThePendingJoin() {
        thenAssertNotCreatedAgain(removedBeforeTheFirstPass(world(), w -> w.h.seed(new NodeDepartureKey(WORKER), admitted(w))));
    }

    // Red when: a departure record's Remove between the removal and the first pass re-evaluates the pending join and writes the directive again.
    @Test
    void directiveRemoved_thenARecordRemove_isNotCreatedAgainByThePendingJoin() {
        var w = world();

        w.admit(WORKER, 21L);
        w.h.seedQuietly(new NodeDepartureKey(WORKER), admitted(w));
        thenAssertNotCreatedAgain(removedBeforeTheFirstPass(w, m -> m.h.seedRemove(new NodeDepartureKey(WORKER))));
    }
}
