// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.swim.SwimObservation;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/// #1050 (verify-1057-r2 S1) — the SWIM observation listener hands ONLY the FAULTY edge to
/// [ClusterTopologyManager#onSwimFaulty]. `DepartedObserved` is the second half of the same FAULTY-edge pair
/// and would otherwise re-arm twice; HEALTHY and SUSPECT are life, not death. Pins `AetherNode.routeSwimFaultyToCtm`;
/// the `addObservationListener` registration itself lives inside the node assembly and has no seam.
class SwimFaultyToCtmRoutingTest {
    private static final NodeId PEER = new NodeId("node-b");

    @Test
    void faultyObserved_reachesTheCtm_asOnSwimFaulty() {
        var ctm = mock(ClusterTopologyManager.class);

        AetherNode.routeSwimFaultyToCtm(new SwimObservation.FaultyObserved(PEER, 3L), ctm);

        verify(ctm).onSwimFaulty(PEER);
    }

    @Test
    void everyOtherEdge_isDropped() {
        var ctm = mock(ClusterTopologyManager.class);

        AetherNode.routeSwimFaultyToCtm(new SwimObservation.DepartedObserved(PEER, 3L), ctm);
        AetherNode.routeSwimFaultyToCtm(new SwimObservation.SuspectObserved(PEER, 3L), ctm);
        AetherNode.routeSwimFaultyToCtm(new SwimObservation.HealthyObserved(PEER, 3L), ctm);
        AetherNode.routeSwimFaultyToCtm(new SwimObservation.UnknownObserved(PEER, 3L), ctm);

        verifyNoInteractions(ctm);
    }
}
