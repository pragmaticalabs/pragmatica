/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.net.quic;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import io.netty.handler.codec.quic.QuicChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.addressOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.observeAttaches;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.ownDialDecided;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// The attach observer tells a test when this end's own dial of a simultaneous pair will never produce a connection: the
/// inbound link made the peer CONNECTED before the dial's address resolved, so the dial is skipped. Without that
/// report a test waiting for "own dial decided" would wait for ever (#2138).
@Timeout(30)
class QuicDialSkippedObservationTest {
    private static final NodeId SELF = new NodeId("aaa-self");
    private static final NodeId PEER = new NodeId("zzz-peer");

    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    @Test
    void dialResolved_peerAlreadyConnected_isReportedAsSkippedAndCountsAsDecided() {
        var network = startedNetwork(new TripwireTopology(SELF, false, TimeSpan.timeSpan(30).seconds()),
                                     MessageRouter.mutable(),
                                     networks);
        var seen = observeAttaches(network);

        network.seedPeerForTests(PEER, connectedPeer());

        assertThat(ownDialDecided(network, seen)).as("before any dial is resolved, nothing is decided").isFalse();

        network.dialResolvedForTests(NodeInfo.nodeInfo(PEER, addressOf("127.0.0.1", 1)), InetAddress.getLoopbackAddress(), 1);

        assertThat(seen).singleElement().satisfies(observation -> {
            assertThat(observation.peerId()).isEqualTo(PEER);
            assertThat(observation.origin()).isEqualTo("dial skipped");
            assertThat(observation.result().isEmpty()).isTrue();
        });
        assertThat(ownDialDecided(network, seen)).isTrue();
        assertThat(network.peerPhaseForTests(PEER).or((PeerState.Phase) null)).as("the skipped dial left the link alone")
                                                                              .isEqualTo(PeerState.Phase.CONNECTED);
    }

    private static PeerState connectedPeer() {
        var state = PeerState.peerState(PEER, 1L);
        var channel = mock(QuicChannel.class);

        when(channel.isActive()).thenReturn(true);
        state.beginConnecting(2L);
        state.attach(QuicPeerConnection.quicPeerConnection(PEER, PEER, 1L, channel), 3L);

        return state;
    }
}
