// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TransportObservation;

import static org.assertj.core.api.Assertions.assertThat;

/// #3858 round 2, A4: the `PeerJoined` route's handler ends a node's announced-departure marks only on the FSM's proof that no DEAD edge
/// can still come for it. The boot test cannot make the booted FSM track a peer, so the handler is driven here with a real FSM behind
/// the same deferred holder the node uses.
class AetherNodeEndDepartureMarksOnHandshakeTest {
    private static final NodeId SELF = new NodeId("handshake-self");
    private static final NodeId PEER = new NodeId("handshake-peer");

    @Test
    void handshakeFromANodeTheFsmTracks_keepsTheMarkForTheDeadEdgeToCome() {
        var fsm = MembershipFsm.membershipFsm();
        var alerts = markedAlerts();

        fsm.onSwimHealthy(PEER, 1L);
        AetherNode.endDepartureMarksOnHandshake(alerts, new AtomicReference<>(fsm)).accept(peerJoined());

        assertThat(alerts.hasAnnouncedDeparture(PEER)).as("a handshake from a tracked node proves nothing about its DEAD edge").isTrue();
    }

    @Test
    void handshakeFromANodeTheFsmDoesNotTrack_endsTheMark() {
        var alerts = markedAlerts();

        AetherNode.endDepartureMarksOnHandshake(alerts, new AtomicReference<>(MembershipFsm.membershipFsm())).accept(peerJoined());

        assertThat(alerts.hasAnnouncedDeparture(PEER)).as("nothing tracks the node, so no DEAD edge can consume the mark").isFalse();
    }

    @Test
    void handshakeBeforeTheFsmIsInstalled_keepsTheMark() {
        var alerts = markedAlerts();

        AetherNode.endDepartureMarksOnHandshake(alerts, new AtomicReference<>()).accept(peerJoined());

        assertThat(alerts.hasAnnouncedDeparture(PEER)).as("before the FSM exists nothing is proved").isTrue();
    }

    private static TransportObservation.PeerJoined peerJoined() {
        return TransportObservation.peerJoined(PEER, List.of(SELF, PEER), TransportObservation.ObservationSource.QUIC);
    }

    @SuppressWarnings("unchecked")
    private static AlertManager markedAlerts() {
        var alerts = AlertManager.readOnly((KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class));

        alerts.noteMembershipTransition(PEER, "DrainRequested");

        return alerts;
    }
}
