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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.connected;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.infoOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// #3033 — pins the other half of the socket-close contract: evicting a link this end DIALED releases the UDP
/// socket that link runs over. Without it every reconnect leaks one datagram channel (the leak the eviction's
/// `closeDatagramChannelOf` call exists to prevent); [QuicEvictedInboundKeepsPendingDialTest] pins the opposite half.
@Timeout(60)
class QuicEvictedOutboundClosesItsSocketTest {
    private static final TimeSpan PING = TimeSpan.timeSpan(30).seconds();

    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    @Test
    void evictingAConnectedOutboundLink_closesItsDatagramChannel() {
        var low = new NodeId("po-a");
        var high = new NodeId("po-b");
        var lowNet = startedNetwork(new TripwireTopology(low, false, PING), MessageRouter.mutable(), networks);
        var highNet = startedNetwork(new TripwireTopology(high, false, PING), MessageRouter.mutable(), networks);

        lowNet.dialForTests(infoOf(high, highNet), true);
        awaitTrue(() -> connected(lowNet, high) && connected(highNet, low), AWAIT, "both ends CONNECTED over the outbound link");
        assertThat(lowNet.clientForTests().datagramChannelCount())
            .as("the outbound link's socket is tracked while it is connected")
            .isEqualTo(1);

        lowNet.activeConnectionForTests(high).onPresent(QuicPeerConnection::close);

        awaitTrue(() -> lowNet.peerPhaseForTests(high).filter(phase -> phase == PeerState.Phase.EVICTED).isPresent(),
                  AWAIT,
                  () -> "the closed outbound link evicted at the low end, phase " + lowNet.peerPhaseForTests(high));
        awaitTrue(() -> lowNet.clientForTests().datagramChannelCount() == 0,
                  AWAIT,
                  () -> "the evicted outbound link's socket released, count " + lowNet.clientForTests().datagramChannelCount());
    }
}
