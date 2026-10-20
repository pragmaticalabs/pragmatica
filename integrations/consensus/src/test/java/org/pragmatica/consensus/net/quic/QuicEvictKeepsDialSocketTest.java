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

import java.util.ArrayList;
import java.util.List;

import io.netty.handler.codec.quic.QuicChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.connected;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.infoOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// #2138 — the dial client keeps one UDP socket per peer: the newest dial's. Evicting a connection the PEER dialed
/// (the closed loser of a dual dial) used to close that socket, which carried the SURVIVING link, evicting the winner
/// a second time. Only the eviction of a connection this node dialed releases the socket.
@Timeout(60)
class QuicEvictKeepsDialSocketTest {
    private static final NodeId LOW = new NodeId("evk-a");
    private static final NodeId HIGH = new NodeId("evk-b");
    private static final TimeSpan PING = TimeSpan.timeSpan(30).seconds();

    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    @Test
    void evict_aConnectionThePeerDialed_keepsTheSocketOfOurOwnDial() {
        var low = startedNetwork(new TripwireTopology(LOW, false, PING), MessageRouter.mutable(), networks);
        var high = startedNetwork(new TripwireTopology(HIGH, false, PING), MessageRouter.mutable(), networks);

        low.dialForTests(infoOf(HIGH, high), false);
        awaitTrue(() -> connected(low, HIGH) && connected(high, LOW) && low.datagramChannelCountForTests() == 1, AWAIT, "linked");

        var state = low.peerStateForTests(HIGH);
        var acceptedFromPeer = connectionDialedBy(HIGH);

        state.evict(System.nanoTime());
        state.attach(acceptedFromPeer, System.nanoTime());
        low.evictStaleConnectionForTests(HIGH, acceptedFromPeer);

        assertThat(low.peerPhaseForTests(HIGH).or((PeerState.Phase) null)).isEqualTo(PeerState.Phase.EVICTED);
        assertThat(low.datagramChannelCountForTests()).as("the socket of our own dial survives").isEqualTo(1);
    }

    @Test
    void evict_aConnectionWeDialed_releasesItsSocket() {
        var low = startedNetwork(new TripwireTopology(LOW, false, PING), MessageRouter.mutable(), networks);
        var high = startedNetwork(new TripwireTopology(HIGH, false, PING), MessageRouter.mutable(), networks);

        low.dialForTests(infoOf(HIGH, high), false);
        awaitTrue(() -> connected(low, HIGH) && low.datagramChannelCountForTests() == 1, AWAIT, "linked");

        var ours = low.activeConnectionForTests(HIGH).or((QuicPeerConnection) null);

        low.evictStaleConnectionForTests(HIGH, ours);

        awaitTrue(() -> low.datagramChannelCountForTests() == 0, AWAIT, "the dial socket released");
    }

    private static QuicPeerConnection connectionDialedBy(NodeId dialer) {
        var channel = mock(QuicChannel.class);

        when(channel.isActive()).thenReturn(true);

        return QuicPeerConnection.quicPeerConnection(dialer, dialer, channel);
    }
}
