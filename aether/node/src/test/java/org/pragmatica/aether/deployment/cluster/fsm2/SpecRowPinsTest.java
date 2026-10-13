// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Spec rows the first review found without a test (v-2174 F19 d): C2, C3, C8, C9's 5 s reconcile, the ACTIVATING timeout of R-6.1.1
/// and the SUCCEEDED resubmission bound.
class SpecRowPinsTest {
    private static final String APP = "org.example:app:1.0.0";

    /// C2. Red when: a blueprint of another base that declares a slice already owned is accepted: it writes targets instead of being
    /// rejected with `blueprint-rejected`.
    @Test
    void c2_blueprintDeclaringAnOwnedSlice_isRejected() {
        var h = deployed(1);

        h.clearLog();
        publish(h, blueprint("org.example:other:1.0.0", false, "attempt-9", slice(ORDERS, 1, 1, Set.of())));
        assertThat(h.warns("blueprint-rejected")).hasSize(1);
        assertThat(h.submits(SubmitKind.TARGETS)).as("no target is written for the rejected blueprint").isEmpty();
    }

    /// C3. Red when: a register-only blueprint writes targets.
    @Test
    void c3_registerOnlyBlueprint_writesNoTargets() {
        var h = active();

        publish(h, blueprint(APP, true, ATTEMPT, slice(ORDERS, 1, 1, Set.of())));
        assertThat(h.submits(SubmitKind.TARGETS)).isEmpty();
    }

    /// C8. Red when: a blueprint is deleted during a rolling update of one of its slices: nothing is removed and the operator is told.
    @Test
    void c8_blueprintRemoval_isRefusedDuringARollingUpdate() {
        var h = deployed(1);

        h.seed(new VersionRoutingKey(ORDERS.base()),
               new VersionRoutingValue(ORDERS.version(), ORDERS_V2.version(), 1, 1, h.wallMs));
        h.drain();
        h.clearLog();
        h.seedRemove(new AppBlueprintKey(blueprintId(APP)));
        h.drain();
        assertThat(h.warns("blueprint-delete-refused")).hasSize(1);
        assertThat(h.submits(SubmitKind.BLUEPRINT_REMOVAL)).isEmpty();
    }

    /// C9. Red when: a removed blueprint schedules its follow-up reconcile at another delay than 5 s.
    @Test
    void c9_blueprintRemoval_schedulesTheReconcileIn5Seconds() {
        var h = deployed(1);

        h.clearLog();
        h.seedRemove(new AppBlueprintKey(blueprintId(APP)));
        h.drain();
        assertThat(h.effects(ClusterEffect.Schedule.class)).anyMatch(schedule -> schedule.timer()
                                                                                         .subject()
                                                                                         .startsWith("blueprint-removed:") && schedule.delayMs() == 5_000L);
    }

    /// R-6.1.1 / R-6.1.4. Red when: the ACTIVATING row of an ACTIVE goal is timed with the loading timeout instead of 90 s, or a
    /// LOADED row with the deactivation timeout.
    @Test
    void timeouts_followTheExpectedNextStep() {
        var activating = Option.some(row(SliceObservedState.ACTIVATING, 1L, gen(1), process(7)));
        var loading = Option.some(row(SliceObservedState.LOADING, 1L, gen(1), process(7)));

        assertThat(Divergence.timeoutOf(SliceGoal.ACTIVE, activating)).isEqualTo(90_000L);
        assertThat(Divergence.timeoutOf(SliceGoal.ACTIVE, loading)).isEqualTo(120_000L);
        assertThat(Divergence.timeoutOf(SliceGoal.LOADED, activating)).isEqualTo(120_000L);
    }

    /// 6.2. Red when: a SUCCEEDED that is refused with nothing moved is submitted more than 1 + 5 times.
    @Test
    void succeeded_refusedWithNothingMoved_isSubmittedAtMostSixTimes() {
        var h = deployed(1);
        var node = node(h, CORE_1, 11);

        node.follows(ORDERS);
        h.drain();
        h.policy = FsmHarness.SettlePolicy.HOLD;
        node.follows(ORDERS);
        h.drain();
        for (var round = 0; round < 9; round++) {
            var open = List.copyOf(h.held.values());

            h.held.clear();
            open.forEach(submit -> h.settleHeld(submit, h.refused(submit)));
        }

        assertThat(h.submits(SubmitKind.SUCCEEDED)).as("resubmitted, and bounded").hasSizeBetween(2, 6);
    }

    private static MbWorld departedWithDirective() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 1L, 0x1111L);
        w.admit(CORE_2, 1L, 0x2222L);
        w.admit(CORE_3, 1L, 0x3333L);

        return w.directive(CORE_2,
                           ActivationDirectiveValue.core())
                .target(ORDERS, 1, "CORE_ONLY")
                .instance(CORE_2,
                          ORDERS,
                          SliceGoal.ACTIVE,
                          SliceObservedState.ACTIVE,
                          gen(1),
                          0x2222L)
                .record(CORE_2, DepartureState.ADMITTED, 1L, 0x2222L, null);
    }

    /// R-4.3.3. Red when: the removal of a departed node's directive, refused with nothing moved, is resubmitted more than the bound (1 + 5)
    /// or not at all.
    @Test
    void r433_directiveRemoval_refusedWithNothingMoved_isResubmittedWithinTheBound() {
        var w = departedWithDirective().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.applyHeld(w.h.submits(SubmitKind.DEPARTURE).getFirst().correlationId());
        w.h.drain();
        w.h.seedRemove(new ActivationDirectiveKey(CORE_2));
        w.h.drain();
        for (var round = 0; round < 9; round++) {
            var open = List.copyOf(w.h.held.values());

            w.h.held.clear();
            open.stream()
                .filter(submit -> submit.kind() == SubmitKind.DIRECTIVE_REMOVAL)
                .forEach(submit -> w.h.settleHeld(submit,
                                                  w.h.refused(submit)));
        }

        assertThat(w.h.submits(SubmitKind.DIRECTIVE_REMOVAL)).hasSizeBetween(2, 6);
    }

    /// R-4.3.3. Red when: the removal of a departed node's directive is refused because a row moved, and the node's record and rows are
    /// then never collected (the departed node keeps residue for ever): the cleanup and the record GC derive them from the mirror.
    @Test
    void r433_directiveRemoval_refusedBecauseARowMoved_endsWithTheNodeCollected() {
        var w = departedWithDirective().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.applyHeld(w.h.submits(SubmitKind.DEPARTURE).getFirst().correlationId());
        w.h.drain();
        w.h.seedRemove(new ActivationDirectiveKey(CORE_2));
        w.h.drain();
        var removal = w.h.submits(SubmitKind.DIRECTIVE_REMOVAL).getFirst();

        w.h.held.clear();
        new NodeSim(w.h, CORE_2, process(0x2222L)).writes(ORDERS, SliceObservedState.DEACTIVATING, gen(1));
        w.h.settleHeld(removal, w.h.refused(removal));
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.advance(120_000L);
        assertThat(w.recordOf(CORE_2)).as("the record is collected").isEqualTo(Option.none());
        assertThat(w.present(new SliceObservedKey(CORE_2, ORDERS))).as("and so is the row").isFalse();
    }
}
