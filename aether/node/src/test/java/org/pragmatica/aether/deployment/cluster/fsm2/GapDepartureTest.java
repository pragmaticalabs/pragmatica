// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;
import java.util.List;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// The settlement of a departure write (R-4.3.0, 6.2) and the removal of a departed node's directive after an inferred
/// departure: the arms the NT rows' orderings do not reach.
class GapDepartureTest {
    private static final long TOKEN_2 = 0x2222L;

    private MbWorld world() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.admit(CORE_3, 0x3333L);

        return w.target(ORDERS, 1, "CORE_ONLY")
                .directive(CORE_2,
                           ActivationDirectiveValue.core())
                .instance(CORE_2,
                          ORDERS,
                          SliceGoal.ACTIVE,
                          SliceObservedState.ACTIVE,
                          gen(1),
                          TOKEN_2)
                .routes(CORE_2, ORDERS);
    }

    /// Red when: a second departure input for a node whose departure is in flight submits a second transaction.
    @Test
    void duplicateDepartureInput_whileInFlight_isIgnored() {
        var w = world().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.clearLog();
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.deliver(new ClusterInput.SelfShutdown(CORE_2));
        assertThat(w.h.submits(SubmitKind.DEPARTURE)).hasSize(1);
    }

    /// Red when: a departure refused because the node was re-admitted at an epoch at or above the departed one is resubmitted, or
    /// stays in `departing` (the late-commit sequence closes, R-4.3.0).
    @Test
    void refusedDeparture_afterAnAdmission_isObsolete() {
        var w = world().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        var held = w.h.submits(SubmitKind.DEPARTURE).getFirst();

        w.h.held.clear();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2)));
        assertThat(w.recordOf(CORE_2).map(NodeDepartureValue::state)).as("J0 wrote ADMITTED first")
                  .isEqualTo(Option.some(DepartureState.ADMITTED));
        w.h.settleHeld(held, w.h.refused(held));
        assertThat(w.h.model().isDeparting(CORE_2)).isFalse();
        assertThat(w.h.submits(SubmitKind.DEPARTURE)).as("no resubmission").hasSize(1);
        assertThat(w.counts(CORE_2)).isTrue();
    }

    /// Red when: a departure whose refusal re-read shows an ADMITTED record at an epoch at or above the departed one (the record not
    /// yet notified) is resubmitted instead of being dropped as obsolete (R-4.3.0).
    @Test
    void refusedDeparture_whoseReReadShowsALaterAdmission_isObsolete() {
        var w = world().record(CORE_2, DepartureState.ADMITTED, 1L, TOKEN_2, null).activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        var held = w.h.submits(SubmitKind.DEPARTURE).getFirst();
        var next = w.admissionOf(CORE_2);

        w.h.held.clear();
        w.h.seedQuietly(new NodeDepartureKey(CORE_2),
                        new NodeDepartureValue(DepartureState.ADMITTED,
                                               2L,
                                               next.process(),
                                               Option.none(),
                                               w.h.wallMs));
        w.h.settleHeld(held, w.h.refused(held));
        assertThat(w.h.model().isDeparting(CORE_2)).isFalse();
        assertThat(w.h.submits(SubmitKind.DEPARTURE)).as("no resubmission").hasSize(1);
    }

    /// Red when: a departure that was refused for a reason other than a re-admission is not resubmitted against the record the
    /// mirror holds, or is resubmitted more than five times.
    @Test
    void refusedDeparture_withNothingMoved_isResubmitted_atMostFiveTimes() {
        var w = world().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        for (var round = 0; round < 8; round++) {
            var open = List.copyOf(w.h.held.values());

            w.h.held.clear();
            open.forEach(submit -> w.h.settleHeld(submit, w.h.refused(submit)));
        }

        assertThat(w.h.submits(SubmitKind.DEPARTURE)).as("one original and five resubmissions").hasSize(6);
        assertThat(w.h.model().isDeparting(CORE_2)).as("it stays in departing until the re-read resolves it").isTrue();
    }

    /// Red when: a departure whose submission failed is dropped (n would be counted again) instead of staying in `departing`, or the
    /// re-read after one consensus timeout does not resubmit the identical write.
    @Test
    void failedDeparture_staysInDeparting_andTheRereadResubmitsIt() {
        var w = world().activate();

        w.h.advance(10_000L);
        w.h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        var first = w.h.submits(SubmitKind.DEPARTURE).getFirst();

        assertThat(w.h.model().isDeparting(CORE_2)).isTrue();
        assertThat(w.counts(CORE_2)).as("not counted while departing").isFalse();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.advance(30_000L);
        var again = w.h.submits(SubmitKind.DEPARTURE);

        assertThat(again).hasSize(2);
        assertThat(again.get(1).transaction().mutations()).as("the identical write (only the decision time differs)")
                  .extracting(mutation -> mutation.key(),
                              mutation -> mutation.expected())
                  .isEqualTo(first.transaction()
                                  .mutations()
                                  .stream()
                                  .map(mutation -> Tuple.tuple(mutation.key(),
                                                               mutation.expected()))
                                  .toList());
        assertThat(w.h.model().isDeparted(CORE_2)).isTrue();
        assertThat(w.h.model().isDeparting(CORE_2)).isFalse();
    }

    /// Red when: a failed inferred departure whose node reappears in its class's signal is resubmitted at the re-read (R-4.3.0:
    /// a C17s is re-derived only while step 7 still warrants it).
    @Test
    void failedInferredDeparture_isNotResubmitted_onceTheCoreIsPresentAgain() {
        var w = world().seams(seams -> seams.withCoreView(Option.some(Set.of(CORE_1, CORE_3)))).activate();
        var bound = ms(Fixtures.bounds().coreRemoval());

        w.h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        w.h.advanceBySeconds(bound / 1000 + 61);
        var before = w.h.submits(SubmitKind.INFERRED_DEPARTURE).size();

        assertThat(before).as("recorded after B_rm; every failure is retried at the re-read while it is still warranted")
                  .isGreaterThanOrEqualTo(1);
        assertThat(w.h.model().isDeparting(CORE_2)).isTrue();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.refreshSeams(seams -> seams.withCoreView(Option.some(Set.of(CORE_1, CORE_2, CORE_3))));
        w.h.advance(31_000L);
        assertThat(w.h.submits(SubmitKind.INFERRED_DEPARTURE)).as("present again: not resubmitted").hasSize(before);
        assertThat(w.h.model().isDeparting(CORE_2)).as("and no longer departing").isFalse();
    }

    /// Red when: R-4.3.3's removal after an inferred departure (the record committed, the node's rows, desired and route keys
    /// still there) leaves any of them out of the one transaction, or does not fence the record removal on the DEPARTED value.
    @Test
    void directiveRemoval_afterAnInferredDeparture_takesEverythingInOneTransaction() {
        var w = world().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.clearLog();
        w.h.seed(new NodeDepartureKey(CORE_2),
                 new NodeDepartureValue(DepartureState.DEPARTED,
                                        1L,
                                        process(TOKEN_2),
                                        Option.some(DepartureCause.STALE),
                                        w.h.wallMs));
        w.h.seedRemove(new ActivationDirectiveKey(CORE_2));
        w.h.drain();
        var removal = w.h.submits(SubmitKind.DIRECTIVE_REMOVAL);

        assertThat(removal).hasSize(1);
        var mutations = removal.getFirst().transaction().mutations();

        assertThat(mutations).extracting(m -> m.key())
                  .containsExactlyInAnyOrder(new NodeDepartureKey(CORE_2),
                                             new SliceObservedKey(CORE_2, ORDERS),
                                             new SliceDesiredKey(CORE_2, ORDERS),
                                             new NodeRoutesKey(CORE_2, ORDERS));
        assertThat(mutations.stream().allMatch(m -> m.replacement()
                                                     .isEmpty())).as("only removals").isTrue();
        assertThat(mutations.stream()
                            .filter(m -> m.key() instanceof NodeDepartureKey)
                            .findFirst()
                            .orElseThrow()
                            .expected()
                            .map(v -> ((NodeDepartureValue) v).state())).as("fenced on the DEPARTED value")
                  .isEqualTo(Option.some(DepartureState.DEPARTED));
    }
}
