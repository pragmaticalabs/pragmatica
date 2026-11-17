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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.connected;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.holdsFor;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.infoOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// #3033 — the loser of a simultaneous dial is closed by the peer, and this end evicts it. That eviction must not
/// close the UDP socket of this end's OWN dial of the same peer, which is past its QUIC handshake and about to
/// attach: the socket's close kills the link the pair resolved to keep, the dial then attaches a dead connection,
/// and the peer stays unconnected here until the reconciler's backoff runs out (a 15 s wait timed out in CI).
@Timeout(60)
class QuicEvictedInboundKeepsPendingDialTest {
    private static final TimeSpan PING = TimeSpan.timeSpan(30).seconds();
    private static final long OUTRANKING_GENERATION = 99L;

    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    @Test
    void evictingTheInboundLoser_keepsTheOwnDialThatTheWinnerRidesOn() {
        var low = new NodeId("pd-a");
        var high = new NodeId("pd-b");
        var lowNet = startedNetwork(new TripwireTopology(low, false, PING), MessageRouter.mutable(), networks);
        var highNet = startedNetwork(new TripwireTopology(high, false, PING), MessageRouter.mutable(), networks);

        // The high end's dial arrives at the low end as its INBOUND link (the one the peer will close as the loser).
        highNet.dialForTests(infoOf(low, lowNet), true);
        awaitTrue(() -> connected(lowNet, high) && connected(highNet, low), AWAIT, "both ends CONNECTED over the inbound link");
        // The low end's own dial, run past its handshake without this network attaching it, with a generation that
        // outranks the inbound link: the peer keeps it and closes the inbound one.
        var ownDial = lowNet.clientForTests()
                            .connect(high, new InetSocketAddress("127.0.0.1", highNet.boundPort().fold(() -> fail("not bound"), p -> p)), OUTRANKING_GENERATION)
                            .await(AWAIT)
                            .fold(cause -> fail("own dial failed: " + cause.message()), link -> link);

        awaitTrue(() -> lowNet.peerPhaseForTests(high).filter(phase -> phase == PeerState.Phase.EVICTED).isPresent(),
                  AWAIT,
                  () -> "the inbound loser evicted at the low end, phase " + lowNet.peerPhaseForTests(high));
        assertThat(holdsFor(ownDial::isActive, TimeSpan.timeSpan(500).millis()))
            .as("the eviction of the inbound loser leaves the own dial's connection open")
            .isTrue();
        assertThat(lowNet.clientForTests().datagramChannelCount())
            .as("the own dial's socket is still tracked")
            .isEqualTo(1);
    }
}
