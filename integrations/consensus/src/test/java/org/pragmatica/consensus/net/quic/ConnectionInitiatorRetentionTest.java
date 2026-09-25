package org.pragmatica.consensus.net.quic;

import io.netty.handler.codec.quic.QuicChannel;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectionInitiatorRetentionTest {
    private static final NodeId LOWER = new NodeId("aaa");
    private static final NodeId HIGHER = new NodeId("zzz");

    @Test
    void oppositeArrivalOrdersRetainSamePhysicalDirectionAtBothEndpoints() {
        for (boolean lowerArrivesFirst : new boolean[] {false, true}) {
            var lowerEndpoint = PeerState.peerState(HIGHER, 0);
            var higherEndpoint = PeerState.peerState(LOWER, 0);
            var lowerDialAtLower = connection(HIGHER, LOWER);
            var higherDialAtLower = connection(HIGHER, HIGHER);
            var lowerDialAtHigher = connection(LOWER, LOWER);
            var higherDialAtHigher = connection(LOWER, HIGHER);
            lowerEndpoint.attach(lowerArrivesFirst ? lowerDialAtLower : higherDialAtLower, 1);
            lowerEndpoint.attach(lowerArrivesFirst ? higherDialAtLower : lowerDialAtLower, 2);
            higherEndpoint.attach(lowerArrivesFirst ? higherDialAtHigher : lowerDialAtHigher, 1);
            higherEndpoint.attach(lowerArrivesFirst ? lowerDialAtHigher : higherDialAtHigher, 2);
            assertThat(lowerEndpoint.activeConnection().unwrap()).isSameAs(lowerDialAtLower);
            assertThat(higherEndpoint.activeConnection().unwrap()).isSameAs(lowerDialAtHigher);
        }
    }

    @Test
    void agedPreferredDirectionIsNotReplacedByOppositeDial() {
        var state = PeerState.peerState(HIGHER, 0);
        var preferred = connection(HIGHER, LOWER);
        state.attach(preferred, 1);
        assertThat(state.attach(connection(HIGHER, HIGHER), Long.MAX_VALUE / 2).result())
            .isEqualTo(PeerState.AttachResult.DUPLICATE);
        assertThat(state.activeConnection().unwrap()).isSameAs(preferred);
    }

    @Test
    void sameInitiatorRetainsExistingYoungDuplicateAndAgedReconnectPolicy() {
        var state = PeerState.peerState(HIGHER, 0);
        var first = connection(HIGHER, LOWER);
        state.attach(first, 1);
        assertThat(state.attach(connection(HIGHER, LOWER), 2).result()).isEqualTo(PeerState.AttachResult.DUPLICATE);
        var replacement = connection(HIGHER, LOWER);
        assertThat(state.attach(replacement, Long.MAX_VALUE / 2).superseded().unwrap()).isSameAs(first);
        assertThat(state.activeConnection().unwrap()).isSameAs(replacement);
    }

    private static QuicPeerConnection connection(NodeId peer, NodeId initiator) {
        var channel = mock(QuicChannel.class);
        when(channel.isActive()).thenReturn(true);
        return QuicPeerConnection.quicPeerConnection(peer, initiator, channel);
    }
}
