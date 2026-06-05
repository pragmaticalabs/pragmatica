/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.net.quic;

import io.netty.handler.codec.quic.QuicChannel;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.PeerState.AttachResult;
import org.pragmatica.consensus.net.quic.PeerState.OfferOutcome;
import org.pragmatica.consensus.net.quic.PeerState.Phase;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.StreamType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// Covers the phase transitions and offer/attach/evict/remove outcomes of [PeerState].
/// Pure unit tests — no QUIC, no networking.
class PeerStateTest {
    private static final NodeId PEER = new NodeId("peer-1");
    private static final long T0 = 1_000_000_000L;

    private PeerState state() {
        return PeerState.peerState(PEER, T0);
    }

    private static Message.Wired out(byte tag) {
        return new TestMsg(tag);
    }

    private record TestMsg(byte tag) implements Message.Wired {
        @Override
        public StreamType streamType() {
            return StreamType.CONSENSUS;
        }
    }

    private QuicPeerConnection liveConnection() {
        var chan = mock(QuicChannel.class);
        when(chan.isActive()).thenReturn(true);
        return QuicPeerConnection.quicPeerConnection(PEER, chan);
    }

    @Test
    void initial_phase_is_INIT() {
        assertThat(state().phase()).isEqualTo(Phase.INIT);
    }

    @Test
    void beginConnecting_fromINIT_transitions_to_CONNECTING() {
        var s = state();
        assertThat(s.beginConnecting(T0 + 1)).isTrue();
        assertThat(s.phase()).isEqualTo(Phase.CONNECTING);
    }

    @Test
    void beginConnecting_idempotent_when_already_CONNECTING() {
        var s = state();
        s.beginConnecting(T0 + 1);
        assertThat(s.beginConnecting(T0 + 2)).isFalse();
    }

    @Test
    void attach_fromCONNECTING_transitions_to_CONNECTED_and_returns_ACCEPTED() {
        var s = state();
        s.beginConnecting(T0 + 1);
        assertThat(s.attach(liveConnection(), T0 + 2)).isEqualTo(AttachResult.ACCEPTED);
        assertThat(s.phase()).isEqualTo(Phase.CONNECTED);
        assertThat(s.activeConnection().isPresent()).isTrue();
    }

    @Test
    void attach_duplicate_on_active_CONNECTED_returns_DUPLICATE() {
        var s = state();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        assertThat(s.attach(liveConnection(), T0 + 3)).isEqualTo(AttachResult.DUPLICATE);
        assertThat(s.phase()).isEqualTo(Phase.CONNECTED);
    }

    @Test
    void attach_on_REMOVED_returns_REJECTED() {
        var s = state();
        s.authoritativeRemove(T0 + 1);
        assertThat(s.attach(liveConnection(), T0 + 2)).isEqualTo(AttachResult.REJECTED);
        assertThat(s.phase()).isEqualTo(Phase.REMOVED);
    }

    @Test
    void attach_fromEVICTED_returns_RECONNECTED_and_transitions_to_CONNECTED() {
        // Issue 1 regression: eviction-then-handshake must NOT signal a fresh ADD upstream.
        // The new RECONNECTED outcome lets QuicClusterNetwork.onPeerConnected suppress the
        // duplicate processViewChange(ADD) emission — peer never left the topology.
        var s = state();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        s.evict(T0 + 3);
        assertThat(s.phase()).isEqualTo(Phase.EVICTED);

        var result = s.attach(liveConnection(), T0 + 4);
        assertThat(result).as("attach from EVICTED is a reconnect, not a fresh accept")
                          .isEqualTo(AttachResult.RECONNECTED);
        assertThat(s.phase()).isEqualTo(Phase.CONNECTED);
    }

    @Test
    void attach_replacingStaleConnectedLink_returns_RECONNECTED() {
        // The other reconnect path: a CONNECTED peer whose live connection became inactive
        // (without an explicit evict). The replacement is also a transparent reconnect from
        // upstream's perspective — peer was already known.
        var s = state();
        s.beginConnecting(T0 + 1);
        var stale = mock(QuicChannel.class);
        when(stale.isActive()).thenReturn(false);
        var staleConn = QuicPeerConnection.quicPeerConnection(PEER, stale);
        s.attach(staleConn, T0 + 2);
        // Now the peer is CONNECTED but the held connection reports !isActive — replacing
        // it must return RECONNECTED, not a duplicate-rejection or fresh ACCEPTED.
        var result = s.attach(liveConnection(), T0 + 3);
        assertThat(result).isEqualTo(AttachResult.RECONNECTED);
        assertThat(s.phase()).isEqualTo(Phase.CONNECTED);
    }

    @Test
    void evict_from_CONNECTED_moves_to_EVICTED_and_returns_the_connection() {
        var s = state();
        s.beginConnecting(T0 + 1);
        var conn = liveConnection();
        s.attach(conn, T0 + 2);
        var evicted = s.evict(T0 + 3);
        assertThat(evicted.isPresent()).isTrue();
        assertThat(evicted.or((QuicPeerConnection) null)).isSameAs(conn);
        assertThat(s.phase()).isEqualTo(Phase.EVICTED);
        assertThat(s.activeConnection().isEmpty()).isTrue();
    }

    @Test
    void evict_from_EVICTED_is_noop() {
        var s = state();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        s.evict(T0 + 3);
        assertThat(s.evict(T0 + 4).isEmpty()).isTrue();
    }

    @Test
    void authoritativeRemove_drops_connection_and_clears_buffer() {
        var s = state();
        s.beginConnecting(T0 + 1);
        s.offerOutbound(out((byte) 1));
        s.offerOutbound(out((byte) 2));
        assertThat(s.offlineBufferSize()).isEqualTo(2);
        var conn = liveConnection();
        s.attach(conn, T0 + 2);
        var removed = s.authoritativeRemove(T0 + 3);
        assertThat(removed.isPresent()).isTrue();
        assertThat(removed.or((QuicPeerConnection) null)).isSameAs(conn);
        assertThat(s.phase()).isEqualTo(Phase.REMOVED);
        assertThat(s.offlineBufferSize()).isZero();
    }

    @Test
    void offerOutbound_INIT_queues() {
        var s = state();
        var outcome = s.offerOutbound(out((byte) 42));
        assertThat(outcome).isInstanceOf(OfferOutcome.Queued.class);
        assertThat(((OfferOutcome.Queued) outcome).oldestEvicted()).isFalse();
        assertThat(s.offlineBufferSize()).isEqualTo(1);
    }

    @Test
    void offerOutbound_CONNECTING_queues() {
        var s = state();
        s.beginConnecting(T0 + 1);
        assertThat(s.offerOutbound(out((byte) 1))).isInstanceOf(OfferOutcome.Queued.class);
        assertThat(s.offlineBufferSize()).isEqualTo(1);
    }

    @Test
    void offerOutbound_EVICTED_queues_preserving_buffer() {
        var s = state();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        s.offerOutbound(out((byte) 1)); // SEND_NOW (no queue)
        s.evict(T0 + 3);
        s.offerOutbound(out((byte) 2)); // queued
        assertThat(s.offlineBufferSize()).isEqualTo(1);
    }

    @Test
    void offerOutbound_CONNECTED_returns_SendNow_with_captured_connection() {
        var s = state();
        s.beginConnecting(T0 + 1);
        var conn = liveConnection();
        s.attach(conn, T0 + 2);
        var outcome = s.offerOutbound(out((byte) 1));
        assertThat(outcome).isInstanceOf(OfferOutcome.SendNow.class);
        assertThat(((OfferOutcome.SendNow) outcome).connection()).isSameAs(conn);
        assertThat(s.offlineBufferSize()).isZero();
    }

    @Test
    void offerOutbound_REMOVED_returns_Dropped() {
        var s = state();
        s.authoritativeRemove(T0 + 1);
        assertThat(s.offerOutbound(out((byte) 1))).isInstanceOf(OfferOutcome.Dropped.class);
        assertThat(s.offlineBufferSize()).isZero();
    }

    @Test
    void offerOutbound_offline_buffer_overflow_drops_oldest() {
        var s = state();
        s.beginConnecting(T0 + 1);
        for (var i = 0; i < PeerState.OFFLINE_BUFFER_MAX; i++) {
            s.offerOutbound(out((byte) (i & 0xff)));
        }
        assertThat(s.offlineBufferSize()).isEqualTo(PeerState.OFFLINE_BUFFER_MAX);
        var overflow = s.offerOutbound(out((byte) 0xAA));
        assertThat(overflow).isInstanceOf(OfferOutcome.Queued.class);
        assertThat(((OfferOutcome.Queued) overflow).oldestEvicted()).isTrue();
        assertThat(s.offlineBufferSize()).isEqualTo(PeerState.OFFLINE_BUFFER_MAX);
    }

    @Test
    void drainOfflineBuffer_returns_queued_messages_in_fifo_order_and_empties() {
        var s = state();
        s.beginConnecting(T0 + 1);
        s.offerOutbound(out((byte) 1));
        s.offerOutbound(out((byte) 2));
        s.offerOutbound(out((byte) 3));
        var drained = s.drainOfflineBuffer();
        assertThat(drained).hasSize(3);
        assertThat(((TestMsg) drained.get(0)).tag()).isEqualTo((byte) 1);
        assertThat(((TestMsg) drained.get(1)).tag()).isEqualTo((byte) 2);
        assertThat(((TestMsg) drained.get(2)).tag()).isEqualTo((byte) 3);
        assertThat(s.offlineBufferSize()).isZero();
    }

    @Test
    void reconnect_flow_preserves_offline_buffer_across_evict_reattach() {
        var s = state();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        s.evict(T0 + 3);               // CONNECTED → EVICTED
        s.offerOutbound(out((byte) 7)); // queued during EVICTED
        s.beginConnecting(T0 + 4);      // EVICTED → CONNECTING
        assertThat(s.offlineBufferSize()).isEqualTo(1);
        s.attach(liveConnection(), T0 + 5); // CONNECTING → CONNECTED
        assertThat(s.drainOfflineBuffer()).hasSize(1);
    }

    @Test
    void passive_flag_is_independent_of_phase() {
        var s = state();
        assertThat(s.isPassive()).isFalse();
        s.markPassive();
        assertThat(s.isPassive()).isTrue();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        assertThat(s.isPassive()).isTrue();
    }

    @Test
    void readmit_fromREMOVED_returnsTrue_andTransitions_to_INIT() {
        // Incarnation-gated resurrection: SWIM re-admitted the NodeId (strictly-higher
        // incarnation superseded the tombstone). REMOVED -> INIT makes the peer dial-eligible.
        var s = state();
        s.authoritativeRemove(T0 + 1);
        assertThat(s.phase()).isEqualTo(Phase.REMOVED);

        assertThat(s.readmit(T0 + 2)).as("readmit from REMOVED resets the peer").isTrue();
        assertThat(s.phase()).isEqualTo(Phase.INIT);
    }

    @Test
    void readmit_afterReset_allows_beginConnecting_to_succeed() {
        // The whole point of readmit: a transient-partition survivor must be able to re-dial.
        var s = state();
        s.authoritativeRemove(T0 + 1);
        s.readmit(T0 + 2);

        assertThat(s.beginConnecting(T0 + 3))
            .as("after readmit, INIT peer is dial-eligible again")
            .isTrue();
        assertThat(s.phase()).isEqualTo(Phase.CONNECTING);
    }

    @Test
    void readmit_onINIT_isNoop_returnsFalse() {
        var s = state();
        assertThat(s.phase()).isEqualTo(Phase.INIT);
        assertThat(s.readmit(T0 + 1)).isFalse();
        assertThat(s.phase()).isEqualTo(Phase.INIT);
    }

    @Test
    void readmit_onCONNECTED_isNoop_returnsFalse() {
        var s = state();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        assertThat(s.phase()).isEqualTo(Phase.CONNECTED);
        assertThat(s.readmit(T0 + 3)).isFalse();
        assertThat(s.phase()).isEqualTo(Phase.CONNECTED);
    }

    @Test
    void readmit_onEVICTED_isNoop_returnsFalse() {
        var s = state();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        s.evict(T0 + 3);
        assertThat(s.phase()).isEqualTo(Phase.EVICTED);
        assertThat(s.readmit(T0 + 4)).isFalse();
        assertThat(s.phase()).isEqualTo(Phase.EVICTED);
    }

    @Test
    void phaseAgeNanos_tracks_time_since_last_transition() {
        var s = state();
        assertThat(s.phaseAgeNanos(T0 + 500)).isEqualTo(500L);
        s.beginConnecting(T0 + 1_000);
        assertThat(s.phaseAgeNanos(T0 + 1_200)).isEqualTo(200L);
    }
}
