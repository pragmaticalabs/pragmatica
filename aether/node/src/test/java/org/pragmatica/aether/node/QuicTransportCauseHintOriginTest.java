// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.swim.TransportObservation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The #1061 wiring pin for the node side of origin-aware SWIM hints. `SwimProtocol` believes a
/// `LINK_LOST` hint only while the QUIC link is down and drops it on reconnect, but keeps a
/// `PEER_UNRESPONSIVE` hint with the link CONNECTED. Which origin a node-produced hint carries is
/// decided HERE, by `AetherNode.unreachableHint`: the QUIC `onPeerLeft` listener reports
/// `PEER_LEFT` and the leader's ClusterSync missed-pong reporter reports `PING_TIMEOUT`. Swapping
/// the two origins re-opens #1061 (a healed link's eviction keeps flooring and vetoing) or drops
/// fast detection of a hung-but-connected peer, and neither is visible to the swim module's tests.
class QuicTransportCauseHintOriginTest {
    private static final NodeId PEER = new NodeId("node-peer");

    @Test
    void unreachableHint_peerLeft_isLinkLostOrigin() {
        var hint = AetherNode.unreachableHint(AetherNode.QuicTransportCause.PEER_LEFT, PEER);

        assertThat(hint.origin())
            .as("A QUIC onPeerLeft describes one lost link: SWIM must disregard it once the link reconnects")
            .isEqualTo(TransportObservation.HintOrigin.LINK_LOST);
        assertThat(hint.peer()).isEqualTo(PEER);
        assertThat(hint.cause()).isEqualTo(AetherNode.QuicTransportCause.PEER_LEFT);
    }

    @Test
    void unreachableHint_pingTimeout_isPeerUnresponsiveOrigin() {
        var hint = AetherNode.unreachableHint(AetherNode.QuicTransportCause.PING_TIMEOUT, PEER);

        assertThat(hint.origin())
            .as("A missed-pong timeout describes a connected-but-silent peer: it must still floor and veto with the link CONNECTED")
            .isEqualTo(TransportObservation.HintOrigin.PEER_UNRESPONSIVE);
        assertThat(hint.peer()).isEqualTo(PEER);
        assertThat(hint.cause()).isEqualTo(AetherNode.QuicTransportCause.PING_TIMEOUT);
    }
}
