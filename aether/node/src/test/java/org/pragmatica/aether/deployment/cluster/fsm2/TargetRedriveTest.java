// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.assertj.core.api.Assertions.assertThat;


/// P3-G, G1: the intake re-drive (R-4.1.4) re-submits an open apply's target batch only when a target is neither committed nor in flight.
/// The first declaration's put is in flight until it commits, is refused (the awaited-target sweep re-sends it at its bound, 6.2), or is
/// dropped unsent: a suspension, a failed settlement or an expiry releases it, so those paths re-drive at their next reconcile as before.
/// NT-107 (`LifecycleTest`) is the suspension arm's pin.
class TargetRedriveTest {
    private static final String APP = "org.example:app:1.0.0";

    /// Red when: the reconcile that runs while the first declaration's put is still in flight submits the batch again (the E-01 duplicate,
    /// one refused round per deploy).
    @Test
    void aReconcileBeforeTheTargetCommits_submitsNoSecondTargetBatch() {
        var h = active();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        publish(h, blueprint(APP, false, ATTEMPT, slice(ORDERS, 3, 1, Set.of())));
        var first = h.submits(SubmitKind.TARGETS).getFirst();

        h.deliver(new ClusterInput.Reconcile());
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.applyHeld(first.correlationId());
        h.drain();

        assertThat(h.store.get(first.transaction().key()).isPresent()).as("control: the declaration's target committed").isTrue();
        assertThat(h.submits(SubmitKind.TARGETS)).as("one target batch per declaration").hasSize(1);
    }

    /// Red when: a committed target is re-driven (the committed conjunct of the re-drive's guard).
    @Test
    void aCommittedTarget_isNotReDriven() {
        var h = active();

        publish(h, blueprint(APP, false, ATTEMPT, slice(ORDERS, 3, 1, Set.of())));
        assertThat(h.model().targets.get(ORDERS.base()).isPresent()).as("control: the target committed").isTrue();
        h.deliver(new ClusterInput.Reconcile());

        assertThat(h.submits(SubmitKind.TARGETS)).hasSize(1);
    }

    /// Red when: an open apply is re-driven while a put of another version of the same base is in flight. The in-flight key is the base:
    /// both puts would expect the same committed target, so the second could only be refused. Two blueprint versions of one application
    /// may both be open; the later declaration holds the base's put.
    @Test
    void aPutInFlightForAnotherVersionOfTheBase_suppressesTheReDrive() {
        var h = active();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        publish(h, blueprint(APP, false, ATTEMPT, slice(ORDERS, 3, 1, Set.of())));
        publish(h, blueprint("org.example:app:2.0.0", false, "attempt-2", slice(ORDERS_V2, 3, 1, Set.of())));
        assertThat(h.model().pendingTargetPuts.get(ORDERS.base()).map(SliceTargetValue::currentVersion))
                  .as("control: the base's put in flight names the later version")
                  .isEqualTo(Option.some(ORDERS_V2.version()));
        h.deliver(new ClusterInput.Reconcile());

        assertThat(h.submits(SubmitKind.TARGETS)).as("one batch per declaration, no re-drive of the earlier version").hasSize(2);
    }

    /// Red when: a refused target batch is re-driven by the next reconcile (6.2: one re-send per bound), or is never re-sent at the bound.
    @Test
    void aRefusedTargetBatch_isResentAtTheBound_notByTheNextReconcile() {
        var h = active();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        publish(h, blueprint(APP, false, ATTEMPT, slice(ORDERS, 3, 1, Set.of())));
        var first = h.submits(SubmitKind.TARGETS).getFirst();

        h.held.clear();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.settleHeld(first, h.refused(first));
        h.deliver(new ClusterInput.Reconcile());
        h.advance(h.settings.timing().awaitedTarget() - 1_000L);
        assertThat(h.submits(SubmitKind.TARGETS)).as("not by a reconcile or a tick inside the bound").hasSize(1);
        h.advance(31_000L);

        assertThat(h.submits(SubmitKind.TARGETS)).as("re-sent once the bound has passed").hasSize(2);
    }

    /// Red when: a failed target batch keeps its put in flight, so the `retryBase` reconcile does not re-drive it (it would wait for the
    /// awaited-target bound).
    @Test
    void aFailedTargetBatch_isReDrivenByTheRetryReconcile() {
        var h = active();

        h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        publish(h, blueprint(APP, false, ATTEMPT, slice(ORDERS, 3, 1, Set.of())));
        h.policy = FsmHarness.SettlePolicy.APPLY;
        assertThat(h.metricTotal("aether_deployment_fsm_submissions_total",
                                 t -> "TARGETS".equals(t.get("kind")) && "failed".equals(t.get("outcome")))).as("control: the batch failed")
                  .isEqualTo(1L);
        h.advance(h.settings.timing().retryBase() + 500L);

        assertThat(h.submits(SubmitKind.TARGETS)).as("re-driven one retryBase later").hasSize(2);
    }

    /// Red when: a failed batch releases a put that a newer declaration has replaced, so the next reconcile re-drives a batch that is in
    /// flight.
    @Test
    void aFailedOlderBatch_keepsTheNewerDeclarationsPutInFlight() {
        var h = active();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        publish(h, blueprint(APP, false, ATTEMPT, slice(ORDERS, 3, 1, Set.of())));
        var older = h.submits(SubmitKind.TARGETS).getFirst();

        publish(h, blueprint(APP, false, "attempt-2", slice(ORDERS_V2, 3, 1, Set.of())));
        assertThat(h.submits(SubmitKind.TARGETS)).as("control: the newer declaration's batch").hasSize(2);
        h.held.remove(older.correlationId());
        h.settleHeld(older, new ClusterInput.Settlement.Failed("injected"));
        h.deliver(new ClusterInput.Reconcile());

        assertThat(h.submits(SubmitKind.TARGETS)).as("the newer put stays in flight: no re-drive").hasSize(2);
    }

    /// Red when: an expired target batch keeps its put in flight, so the reconcile that expires it does not re-drive it.
    @Test
    void anExpiredTargetBatch_isReDrivenByTheReconcileThatExpiresIt() {
        var h = active();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        publish(h, blueprint(APP, false, ATTEMPT, slice(ORDERS, 3, 1, Set.of())));
        h.held.clear();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.advance(h.settings.submissionExpiry() + 30_000L);
        assertThat(h.metricTotal("aether_deployment_fsm_submissions_total",
                                 t -> "TARGETS".equals(t.get("kind")) && "lost".equals(t.get("outcome")))).as("control: the batch expired")
                  .isEqualTo(1L);

        assertThat(h.submits(SubmitKind.TARGETS)).as("re-driven by the reconcile that expired it, inside the awaited-target bound")
                  .hasSize(2);
    }
}
