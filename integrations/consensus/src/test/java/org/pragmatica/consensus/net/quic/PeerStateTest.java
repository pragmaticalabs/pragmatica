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

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

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
        assertThat(s.attach(liveConnection(), T0 + 2).result()).isEqualTo(AttachResult.ACCEPTED);
        assertThat(s.phase()).isEqualTo(Phase.CONNECTED);
        assertThat(s.activeConnection().isPresent()).isTrue();
    }

    @Test
    void attach_duplicate_on_young_active_CONNECTED_returns_DUPLICATE() {
        var s = state();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        // Incumbent younger than SUPERSEDE_MIN_AGE (T0+3 is ~1ns after T0+2) — stays a duplicate.
        assertThat(s.attach(liveConnection(), T0 + 3).result()).isEqualTo(AttachResult.DUPLICATE);
        assertThat(s.phase()).isEqualTo(Phase.CONNECTED);
    }

    @Test
    void attach_on_REMOVED_returns_REJECTED() {
        var s = state();
        s.authoritativeRemove(T0 + 1);
        assertThat(s.attach(liveConnection(), T0 + 2).result()).isEqualTo(AttachResult.REJECTED);
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
        assertThat(result.result()).as("attach from EVICTED is a reconnect, not a fresh accept")
                          .isEqualTo(AttachResult.RECONNECTED);
        assertThat(result.superseded().isEmpty()).isTrue();
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
        assertThat(result.result()).isEqualTo(AttachResult.RECONNECTED);
        assertThat(result.superseded().isEmpty()).as("a dead incumbent is not handed back to close").isTrue();
        assertThat(s.phase()).isEqualTo(Phase.CONNECTED);
    }

    @Test
    void attach_freshHandshakeOverOldActiveIncumbent_supersedesAndReturnsOldForClose() {
        // Post-partition reconnect: incumbent still reports isActive() (idle timeout disabled,
        // never learned its peer died), but it is older than SUPERSEDE_MIN_AGE (3s). The fresh
        // inbound handshake is a current liveness proof and must adopt-newer, handing back the
        // stale OLD connection for the caller to close.
        var s = state();
        s.beginConnecting(T0 + 1);
        var oldActive = liveConnection();
        s.attach(oldActive, T0 + 2);
        var fresh = liveConnection();
        var threeSecondsOneNano = TimeUnit.SECONDS.toNanos(3) + 1L;
        var result = s.attach(fresh, T0 + 2 + threeSecondsOneNano);
        assertThat(result.result()).isEqualTo(AttachResult.RECONNECTED);
        assertThat(result.superseded().isPresent()).as("old active incumbent is handed back to close").isTrue();
        assertThat(result.superseded().or((QuicPeerConnection) null)).isSameAs(oldActive);
        assertThat(s.activeConnection().or((QuicPeerConnection) null))
            .as("bound connection is now the fresh one").isSameAs(fresh);
        assertThat(s.phase()).isEqualTo(Phase.CONNECTED);
    }

    @Test
    void attach_freshHandshakeOverYoungActiveIncumbent_isDuplicate() {
        // Formation dual-dial race: incumbent is active AND younger than SUPERSEDE_MIN_AGE.
        // The fresh handshake must NOT supersede — it stays a duplicate and the incumbent is kept.
        var s = state();
        s.beginConnecting(T0 + 1);
        var young = liveConnection();
        s.attach(young, T0 + 2);
        var oneSecond = TimeUnit.SECONDS.toNanos(1);
        var result = s.attach(liveConnection(), T0 + 2 + oneSecond);
        assertThat(result.result()).isEqualTo(AttachResult.DUPLICATE);
        assertThat(result.superseded().isEmpty()).as("young incumbent is not displaced").isTrue();
        assertThat(s.activeConnection().or((QuicPeerConnection) null))
            .as("incumbent connection unchanged").isSameAs(young);
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

    @Test
    void attach_ACCEPTED_doesNotRefreshInboundClock() {
        // Wave 5 receipt-evidence TTL: our OWN dial success is not evidence the peer is sending
        // to us. attach must leave the inbound clock untouched — only markInbound refreshes it.
        var s = state();
        s.beginConnecting(T0 + 1);
        s.markInbound(T0 + 1);
        s.attach(liveConnection(), T0 + 500);
        assertThat(s.inboundAgeNanos(T0 + 600))
            .as("inbound age is still measured from markInbound, not from attach")
            .isEqualTo(599L);
    }

    @Test
    void attach_RECONNECTED_fromEVICTED_doesNotRefreshInboundClock() {
        // A late/re-dial attach (H10 interplay) must NOT refresh the receipt clock either:
        // the stale inbound age accumulated before eviction survives the re-attach. The sweep's
        // attach-grace comes from the restarted phase age, never from the receipt clock.
        var s = state();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        s.markInbound(T0 + 10);
        s.evict(T0 + 20);
        var reattachAt = T0 + 1_000_000L;
        s.attach(liveConnection(), reattachAt);
        assertThat(s.inboundAgeNanos(reattachAt))
            .as("re-attach must not reset the receipt-evidence clock")
            .isEqualTo(reattachAt - (T0 + 10));
        assertThat(s.phaseAgeNanos(reattachAt))
            .as("the attach-grace anchor (phase age) restarts at the re-attach")
            .isEqualTo(0L);
    }

    @Test
    void markInbound_refreshes_inboundAgeNanos() {
        var s = state();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        // A fresh inbound frame resets the age to zero at that instant.
        s.markInbound(T0 + 2 + 5_000);
        assertThat(s.inboundAgeNanos(T0 + 2 + 5_000)).isEqualTo(0L);
        assertThat(s.inboundAgeNanos(T0 + 2 + 5_000 + 250)).isEqualTo(250L);
    }

    @Test
    void inboundAgeNanos_usesSuppliedClock() {
        var s = state();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        s.markInbound(T0 + 100);
        assertThat(s.inboundAgeNanos(T0 + 100)).isEqualTo(0L);
        assertThat(s.inboundAgeNanos(T0 + 100 + 42)).isEqualTo(42L);
    }

    @Test
    void evictStaleConnecting_fromCONNECTING_transitions_to_EVICTED_returnsTrue() {
        // A hung dial: beginConnecting moved the peer to CONNECTING but neither attach nor a
        // connect-failure ever resolved it. evictStaleConnecting must break the wedge so the
        // reconciler can re-dial (EVICTED is dial-eligible via beginConnecting).
        var s = state();
        s.beginConnecting(T0 + 1);
        assertThat(s.phase()).isEqualTo(Phase.CONNECTING);

        assertThat(s.evictStaleConnecting(T0 + 2))
            .as("a CONNECTING peer must be force-evictable to clear a hung dial")
            .isTrue();
        assertThat(s.phase()).isEqualTo(Phase.EVICTED);
        assertThat(s.activeConnection().isEmpty()).isTrue();
    }

    @Test
    void evictStaleConnecting_afterEviction_peerIsDialEligibleAgain() {
        var s = state();
        s.beginConnecting(T0 + 1);
        s.evictStaleConnecting(T0 + 2);

        assertThat(s.beginConnecting(T0 + 3))
            .as("after force-evicting a hung dial, the peer can re-enter CONNECTING")
            .isTrue();
        assertThat(s.phase()).isEqualTo(Phase.CONNECTING);
    }

    @Test
    void evictStaleConnecting_fromINIT_isNoop_returnsFalse() {
        var s = state();
        assertThat(s.evictStaleConnecting(T0 + 1)).isFalse();
        assertThat(s.phase()).isEqualTo(Phase.INIT);
    }

    @Test
    void evictStaleConnecting_fromCONNECTED_isNoop_returnsFalse() {
        // Distinct from evict(): the CONNECTED → EVICTED stale-link path is evict()'s job, not this.
        var s = state();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        assertThat(s.evictStaleConnecting(T0 + 3)).isFalse();
        assertThat(s.phase()).isEqualTo(Phase.CONNECTED);
        assertThat(s.activeConnection().isPresent()).isTrue();
    }

    @Test
    void evictStaleConnecting_fromREMOVED_isNoop_returnsFalse() {
        var s = state();
        s.authoritativeRemove(T0 + 1);
        assertThat(s.evictStaleConnecting(T0 + 2)).isFalse();
        assertThat(s.phase()).isEqualTo(Phase.REMOVED);
    }

    // --- Wave 5: reconnect provenance (C3 — no duplicate ADD on the re-dial path) ---

    @Test
    void attach_fromCONNECTING_afterEvictedRedial_returns_RECONNECTED() {
        // THE #245 in-code loop: CONNECTED → evict → beginConnecting (EVICTED → CONNECTING) →
        // re-dial completes → attach. Pre-Wave-5 this returned ACCEPTED and re-emitted a
        // duplicate PeerJoined every ~10s. Provenance (the peer was already announced upstream)
        // must yield RECONNECTED.
        var s = state();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        s.evict(T0 + 3);
        s.beginConnecting(T0 + 4);
        var result = s.attach(liveConnection(), T0 + 5);
        assertThat(result.result())
            .as("re-dial attach of an announced peer is a reconnect, not a fresh accept")
            .isEqualTo(AttachResult.RECONNECTED);
        assertThat(s.phase()).isEqualTo(Phase.CONNECTED);
    }

    @Test
    void attach_fromEVICTED_neverConnected_returns_ACCEPTED() {
        // A dial that failed/timed out (CONNECTING → EVICTED) on a peer that NEVER reached
        // CONNECTED: upstream never received an ADD, so the eventual (possibly late, H10)
        // attach must be ACCEPTED — RECONNECTED would suppress the ADD forever.
        var s = state();
        s.beginConnecting(T0 + 1);
        s.evictStaleConnecting(T0 + 2);
        assertThat(s.phase()).isEqualTo(Phase.EVICTED);
        var result = s.attach(liveConnection(), T0 + 3);
        assertThat(result.result())
            .as("late attach of a never-announced peer is a fresh accept")
            .isEqualTo(AttachResult.ACCEPTED);
        assertThat(s.phase()).isEqualTo(Phase.CONNECTED);
    }

    @Test
    void attach_afterReadmit_returns_ACCEPTED() {
        // readmit resets provenance: upstream saw the REMOVE, so the next attach is a fresh ADD.
        var s = state();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        s.authoritativeRemove(T0 + 3);
        s.readmit(T0 + 4);
        var result = s.attach(liveConnection(), T0 + 5);
        assertThat(result.result())
            .as("attach after readmit is a fresh accept — upstream was told about the REMOVE")
            .isEqualTo(AttachResult.ACCEPTED);
    }

    // --- #491: hasEverConnected() — the grace-scope discriminator ---
    // previouslyConnectedNowEvicted in QuicClusterNetwork keys the missing-peer reconciler's
    // grace bypass off this flag: an EVICTED peer that EVER reached CONNECTED is a lost link to a
    // known-live member (force-dial now), whereas a hung CONNECTING → EVICTED cold-boot dial that
    // NEVER connected must still wait the full higher-id grace. These tests pin both arms.

    @Test
    void hasEverConnected_isFalse_beforeAnyCONNECTED() {
        var s = state();
        assertThat(s.hasEverConnected())
            .as("a fresh INIT peer has never connected")
            .isFalse();
        s.beginConnecting(T0 + 1);
        assertThat(s.hasEverConnected())
            .as("a peer still dialing (CONNECTING) has not yet completed a Hello handshake")
            .isFalse();
    }

    @Test
    void hasEverConnected_isTrue_afterAttachReachesCONNECTED() {
        var s = state();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        assertThat(s.phase()).isEqualTo(Phase.CONNECTED);
        assertThat(s.hasEverConnected())
            .as("a completed Hello handshake (CONNECTED) marks the peer as ever-connected")
            .isTrue();
    }

    @Test
    void hasEverConnected_staysTrue_afterEvictFromCONNECTED() {
        // The #491 positive arm: a member that CONNECTED then lost its link (CONNECTED → EVICTED)
        // stays ever-connected, so previouslyConnectedNowEvicted force-dials it within grace.
        var s = state();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        s.evict(T0 + 3);
        assertThat(s.phase()).isEqualTo(Phase.EVICTED);
        assertThat(s.hasEverConnected())
            .as("eviction of a previously-CONNECTED link preserves ever-connected provenance")
            .isTrue();
    }

    @Test
    void hasEverConnected_isFalse_afterEvictStaleConnectingNeverConnected() {
        // The #491 negative arm: a hung cold-boot dial (CONNECTING → EVICTED) that NEVER reached
        // CONNECTED must NOT be treated as a lost live link — it stays under the full higher-id
        // grace, so hasEverConnected must remain false.
        var s = state();
        s.beginConnecting(T0 + 1);
        s.evictStaleConnecting(T0 + 2);
        assertThat(s.phase()).isEqualTo(Phase.EVICTED);
        assertThat(s.hasEverConnected())
            .as("a never-connected hung dial evicted from CONNECTING is not ever-connected")
            .isFalse();
    }

    @Test
    void hasEverConnected_resetsToFalse_afterReadmit() {
        // readmit clears upstream provenance (REMOVED → INIT): a re-admitted peer starts fresh and
        // must wait the cold-boot grace again, so ever-connected resets to false.
        var s = state();
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        s.authoritativeRemove(T0 + 3);
        s.readmit(T0 + 4);
        assertThat(s.phase()).isEqualTo(Phase.INIT);
        assertThat(s.hasEverConnected())
            .as("readmit resets provenance — a re-admitted peer is treated as never-connected")
            .isFalse();
    }

    // --- Wave 5: single emission source — exactly one transition record per phase mutation ---

    @Test
    void everyPhaseTransition_emitsExactlyOneRecord_inMutationOrder() {
        var records = new CopyOnWriteArrayList<PeerTransitionRecord>();
        var s = PeerState.peerState(PEER, T0, records::add);

        s.beginConnecting(T0 + 1);                  // INIT -> CONNECTING
        s.attach(liveConnection(), T0 + 2);         // CONNECTING -> CONNECTED (accepted)
        s.evict(T0 + 3);                            // CONNECTED -> EVICTED
        s.beginConnecting(T0 + 4);                  // EVICTED -> CONNECTING
        s.attach(liveConnection(), T0 + 5);         // CONNECTING -> CONNECTED (reconnected)
        s.authoritativeRemove(T0 + 6);              // CONNECTED -> REMOVED
        s.readmit(T0 + 7);                          // REMOVED -> INIT

        assertThat(records).hasSize(7);
        assertThat(records.stream().map(PeerTransitionRecord::cause).toList())
            .containsExactly("begin-connecting",
                             "attach-accepted",
                             "evict",
                             "begin-connecting",
                             "attach-reconnected",
                             "authoritative-remove",
                             "readmit");
    }

    @Test
    void noopMutations_emitNoRecords() {
        var records = new CopyOnWriteArrayList<PeerTransitionRecord>();
        var s = PeerState.peerState(PEER, T0, records::add);

        s.beginConnecting(T0 + 1);
        records.clear();

        // All of these are no-ops from CONNECTING and must record nothing.
        s.beginConnecting(T0 + 2);
        s.evict(T0 + 3);
        s.readmit(T0 + 4);

        assertThat(records).as("no-op mutations must not produce transition records").isEmpty();
    }

    @Test
    void repeatedAuthoritativeRemove_recordsSingleTransition_noRemovedToRemovedDuplicate() {
        // The Wave-1 journal caught duplicate `authoritative-remove` (REMOVED -> REMOVED)
        // entries — the live specimen of the scattered-emission disease. The chokepoint must
        // dedup: a repeated remove is a pure no-op with NO record.
        var records = new CopyOnWriteArrayList<PeerTransitionRecord>();
        var s = PeerState.peerState(PEER, T0, records::add);
        s.beginConnecting(T0 + 1);
        s.attach(liveConnection(), T0 + 2);
        records.clear();

        assertThat(s.authoritativeRemove(T0 + 3).isPresent()).isTrue();
        assertThat(s.authoritativeRemove(T0 + 4).isEmpty()).isTrue();
        assertThat(s.authoritativeRemove(T0 + 5).isEmpty()).isTrue();

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().from()).isEqualTo(Phase.CONNECTED);
        assertThat(records.getFirst().to()).isEqualTo(Phase.REMOVED);
        assertThat(records.getFirst().cause()).isEqualTo("authoritative-remove");
    }
}
