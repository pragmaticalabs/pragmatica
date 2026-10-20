// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler.DurableAllocation;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationPhase;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #1404 (owner ruling D1): the committed capacity allocations reach the leader reconciler as durable capacity. The assembly
/// is not constructed by any unit test, so the wiring is pinned the way `NodeDepartureWiringTest` pins its own: by its presence in the
/// assembly source. The mapping from reservation phases is a pure function, tested directly.
class DurableAllocationWiringTest {
    private static final NodeId UNCERTAIN = new NodeId("dispatched");
    private static final NodeId CONFIRMED = new NodeId("observed");
    private static final NodeId INSTALLED = new NodeId("installed-voter");

    @Test
    void assembly_wiresTheCommittedCoreAllocationsMinusTheInstalledElectorate() {
        assertThat(NodeDepartureWiringTest.assemblyCode()).contains(
            "leaderReconciler.setDurableProvisioningSupplier(()->pendingCoreAllocations(lifecycleManager.allocatedNodes(\"core\"),installedVoterIds(clusterNode)));");
    }

    @Test
    void assembly_keepsTheGossipInheritanceBesideTheDurableSource() {
        assertThat(NodeDepartureWiringTest.assemblyCode()).contains(
            "leaderReconciler.setRetainedDispatchedSupplier(metricsCollector::retainedDispatchedNodes);");
    }

    @Test
    void pendingCoreAllocations_mapsADispatchedCreateToUncertainAndAnObservedOneToConfirmed() {
        var pending = AetherNode.pendingCoreAllocations(Map.of(UNCERTAIN, CapacityReservationPhase.DISPATCHED,
                                                               CONFIRMED, CapacityReservationPhase.OBSERVED),
                                                        Set.of());

        assertThat(pending).containsEntry(UNCERTAIN, DurableAllocation.UNCERTAIN)
                           .containsEntry(CONFIRMED, DurableAllocation.CONFIRMED)
                           .hasSize(2);
    }

    @Test
    void pendingCoreAllocations_leavesOutAnInstalledVoter() {
        var pending = AetherNode.pendingCoreAllocations(Map.of(UNCERTAIN, CapacityReservationPhase.DISPATCHED,
                                                               INSTALLED, CapacityReservationPhase.OBSERVED),
                                                        Set.of(INSTALLED));

        assertThat(pending).containsOnlyKeys(UNCERTAIN);
    }

    @Test
    void pendingCoreAllocations_readsAnUnrecognisedPhaseAsUncertain() {
        var pending = AetherNode.pendingCoreAllocations(Map.of(UNCERTAIN, CapacityReservationPhase.UNKNOWN), Set.of());

        assertThat(pending).containsEntry(UNCERTAIN, DurableAllocation.UNCERTAIN);
    }
}
