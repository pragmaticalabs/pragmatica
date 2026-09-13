// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.swim.TransportObservation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1061 round 2 (review S1): pins what each production SWIM-hint call site reports, not only the
/// cause→origin mapping `QuicTransportCauseHintOriginTest` pins. `AetherNode.assembleNode` installs
/// exactly these factories: the QUIC peer-state listener (`attachQuicPeerStateListener`), the
/// ClusterSync missed-pong reporter and the ClusterSync pong listener. Swapping the cause at either
/// hint site — the review's N2/N3 mutations — reddens a test here.
class SwimHintWiringTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId PEER = new NodeId("node-peer");

    private final List<TransportObservation> hints = new CopyOnWriteArrayList<>();
    private final List<NodeId> linkEpochs = new CopyOnWriteArrayList<>();

    @Test
    void quicPeerStateListener_onPeerLeft_sendsLinkLostPeerLeftHint() {
        AetherNode.quicPeerStateListener(hints::add, linkEpochs::add).onPeerLeft(PEER);

        assertThat(hints).hasSize(1);
        var hint = (TransportObservation.PeerUnreachable) hints.getFirst();
        assertThat(hint.origin())
            .as("A QUIC departure describes one lost link — LINK_LOST, disregarded once the link is back")
            .isEqualTo(TransportObservation.HintOrigin.LINK_LOST);
        assertThat(hint.cause()).isEqualTo(AetherNode.QuicTransportCause.PEER_LEFT);
        assertThat(hint.peer()).isEqualTo(PEER);
        assertThat(linkEpochs).as("A departure starts no link epoch").isEmpty();
    }

    @Test
    void quicPeerStateListener_onPeerReconnected_startsLinkEpochAndSendsReachable() {
        AetherNode.quicPeerStateListener(hints::add, linkEpochs::add).onPeerReconnected(PEER);

        assertThat(linkEpochs)
            .as("A reconnect discards ClusterSync misses counted against the previous link (R-a)")
            .containsExactly(PEER);
        assertThat(hints).containsExactly(new TransportObservation.PeerReachable(PEER));
    }

    @Test
    void quicPeerStateListener_onPeerJoined_startsLinkEpochAndSendsReachable() {
        AetherNode.quicPeerStateListener(hints::add, linkEpochs::add).onPeerJoined(PEER);

        assertThat(linkEpochs).containsExactly(PEER);
        assertThat(hints).containsExactly(new TransportObservation.PeerReachable(PEER));
    }

    @Test
    void pingTimeoutReporter_sendsPeerUnresponsivePingTimeoutHint() {
        AetherNode.pingTimeoutReporter(hints::add).accept(PEER);

        assertThat(hints).hasSize(1);
        var hint = (TransportObservation.PeerUnreachable) hints.getFirst();
        assertThat(hint.origin())
            .as("Missed pongs describe a connected-but-silent peer — PEER_UNRESPONSIVE, floors with the link CONNECTED")
            .isEqualTo(TransportObservation.HintOrigin.PEER_UNRESPONSIVE);
        assertThat(hint.cause()).isEqualTo(AetherNode.QuicTransportCause.PING_TIMEOUT);
        assertThat(hint.peer()).isEqualTo(PEER);
    }

    @Test
    void pongResponsiveReporter_sendsPeerResponsiveForPongSender() {
        AetherNode.pongResponsiveReporter(SELF, hints::add).accept(ClusterSyncPong.clusterSyncPong(PEER, Map.of()));

        assertThat(hints)
            .as("A pong retracts that sender's PEER_UNRESPONSIVE hint (R-b)")
            .containsExactly(new TransportObservation.PeerResponsive(PEER));
    }

    /// Round 3 (review NIT 2): the leader pongs itself and the collector fans every pong out, so the
    /// reporter drops the self-pong at the source instead of relying on SWIM to discard it.
    @Test
    void pongResponsiveReporter_selfPong_sendsNothing() {
        AetherNode.pongResponsiveReporter(SELF, hints::add).accept(ClusterSyncPong.clusterSyncPong(SELF, Map.of()));

        assertThat(hints).as("There is no hint about self to retract").isEmpty();
    }
}
