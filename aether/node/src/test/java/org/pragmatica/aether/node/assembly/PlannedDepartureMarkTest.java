// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.api.NodeDepartureNotifier;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/// #2315 round 1, F4 and L5: a planned-departure mark is never dropped while its node's departure notification is still pending.
/// The real membership FSM, alert manager, departure notifier and the two predicates `DrainAssembly` composes are used; the
/// clock is the `nowMs` argument of `observeDrainSet`, so nothing sleeps and nothing waits.
class PlannedDepartureMarkTest {
    private static final NodeId SELF = new NodeId("observer-node");
    private static final NodeId NODE = new NodeId("drained");
    private static final long T0 = 1_000_000L;
    private static final long AFTER_THE_OMISSION_GRACE = T0 + AlertManager.DRAIN_OMISSION_GRACE_MS;

    private static Predicate<NodeId> drainEnded(MembershipFsm fsm) {
        return DrainAssembly.nodeReadyAgain(() -> Map.of()).or(DrainAssembly.nodeUntracked(fsm));
    }

    @SuppressWarnings("unchecked")
    private static AlertManager alertManager() {
        return AlertManager.alertManager(Mockito.mock(org.pragmatica.cluster.node.rabia.RabiaNode.class),
                                         (KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class));
    }

    /// F4. The FSM changes the state to Dead under its monitor and runs the DEAD hooks after it: for that window `memberStates()`
    /// reads Dead while the listener that reaches `NodeDepartureNotifier.onConfirmedDeparture` has not run. The listener below
    /// is that window: the age-out tick (the node absent from the drain record for the whole grace) runs first, the notifier after.
    /// Red when: the tick drops the mark, so the planned departure is announced to nobody and raises CRITICAL.
    @Test
    void ageOutTick_betweenTheDeadStateAndTheDepartureNotification_keepsThePlannedDepartureMark() {
        var fsm = MembershipFsm.membershipFsm();
        var alerts = alertManager();
        var aggregator = Mockito.mock(ClusterEventAggregator.class);
        var notifier = NodeDepartureNotifier.nodeDepartureNotifier(aggregator, alerts, SELF, OperatorWarningSink.handingOffTo(_ -> {}));
        var hookRuns = new AtomicInteger();

        fsm.onSwimHealthy(NODE, 1L);
        alerts.observeDrainSet(Set.of(NODE), SELF, T0, drainEnded(fsm), DrainAssembly.nodeHoldsDeadLife(fsm));
        assertThat(alerts.hasAnnouncedDeparture(NODE)).as("precondition: the drain record created the mark").isTrue();
        fsm.onConfirmedDeparture(departed -> {
            hookRuns.incrementAndGet();
            assertThat(fsm.memberStates()).as("instrument: the hook runs after the Dead state change").containsEntry(departed, "Dead");
            alerts.observeDrainSet(Set.of(), SELF, AFTER_THE_OMISSION_GRACE, drainEnded(fsm), DrainAssembly.nodeHoldsDeadLife(fsm));
            notifier.onConfirmedDeparture(departed);
        });

        fsm.onSwimDeparted(NODE, 1L);

        assertThat(hookRuns.get()).as("instrument: the departure hook ran once").isEqualTo(1);
        verify(aggregator).onAnnouncedDeparture(NODE);
        verify(aggregator, never()).onConfirmedDeparture(NODE);
        assertThat(alerts.getActiveNodeHealthAlerts()).as("a planned departure raised CRITICAL").isEmpty();
    }

    /// L5 (#2063 residual). A node the FSM no longer tracks (a directory scope change, `MembershipFsm.setTrackingEligibility`,
    /// which `WorkerPeerScope.installDirectory` calls) has no Dead edge to come, so no departure notification is pending for it;
    /// a mark a late drain ping re-creates for it is neither READY nor Dead, and nothing ends it.
    /// Red when: the mark and its tracking entry outlive the omission grace.
    @Test
    void markOfANodeTheFsmNoLongerTracks_endsAfterTheOmissionGrace() {
        var fsm = MembershipFsm.membershipFsm();
        var alerts = alertManager();

        fsm.onSwimHealthy(NODE, 1L);
        fsm.setTrackingEligibility(id -> !id.equals(NODE));
        assertThat(fsm.memberStates()).as("precondition: the scope change dropped the node").doesNotContainKey(NODE);

        alerts.observeDrainSet(Set.of(NODE), SELF, T0, drainEnded(fsm), DrainAssembly.nodeHoldsDeadLife(fsm));
        alerts.observeDrainSet(Set.of(), SELF, AFTER_THE_OMISSION_GRACE, drainEnded(fsm), DrainAssembly.nodeHoldsDeadLife(fsm));

        assertThat(alerts.trackedDrainNodes()).as("tracking entries after the omission grace").isZero();
    }
}
