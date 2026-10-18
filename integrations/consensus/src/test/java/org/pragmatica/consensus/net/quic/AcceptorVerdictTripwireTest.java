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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.StreamType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.infoOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// Tripwire pair for A-05 / inventory row U-005 (cluster T assessment, rc4 `7c786db0d3`): the acceptor sends its Hello response
/// BEFORE it decides REJECTED (tombstone) or DUPLICATE and closes. By then the dialer has completed its handshake, attached,
/// and drained its offline buffer onto the connection the acceptor then closes: the frames were removed from the buffer and
/// reported `Sent`, and are gone. The cycle repeats at the reconcile cadence for as long as the tombstone stands.
///
/// Setup: the dialer buffers N frames for the acceptor (listed member, never connected), then dials an acceptor that holds it
/// as a REMOVED tombstone with no readmit authority (not in its `coreNodes()`, no liveness gate, tombstone younger than 30 s).
/// Observed after the rejection: whether the frames left the dialer's buffer. The acceptor drops every probe it receives
/// (`setInboundFaultFilter`), so a frame that left the buffer is a frame that is gone. The ordering is a race the dialer usually wins
/// (see [#pairsThatLostTheirFrames]), so the pair is run on several fresh pairs of nodes.
///
/// The fix the lane suggests (the verdict in the Hello response, or drain only after the first inbound frame) belongs to the
/// arbitration/handshake overhaul; the real assertion states only that no frame is lost to a rejected attach.
@Timeout(240)
class AcceptorVerdictTripwireTest {
    private static final int FRAMES = 20;
    private static final int PAIRS = 8;

    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    /// Whether one fresh dialer/acceptor pair lost the dialer's buffered frames: the acceptor holds the dialer as a tombstone and
    /// drops every probe it receives, so a frame that left the dialer's buffer is a frame that is gone. Fresh pairs, not repeated
    /// dials of one pair: a second dial of a rejected dialer is not reliably attempted.
    private boolean onePairLostItsBufferedFrames(int pair) {
        var dialer = new NodeId("aaa-verdict-" + pair);
        var acceptor = new NodeId("zzz-verdict-" + pair);
        var dialerTopology = new TripwireTopology(dialer, false, TimeSpan.timeSpan(30).seconds());
        var high = startedNetwork(new TripwireTopology(acceptor, false, TimeSpan.timeSpan(30).seconds()), MessageRouter.mutable(), networks);

        dialerTopology.addPeer(infoOf(acceptor, high));
        var low = startedNetwork(dialerTopology, MessageRouter.mutable(), networks);
        var tombstone = PeerState.peerState(dialer, System.nanoTime());

        tombstone.authoritativeRemove(System.nanoTime());
        high.seedPeerForTests(dialer, tombstone);
        high.setInboundFaultFilter((_, message) -> !(message instanceof LaneProbe));
        IntStream.range(0, FRAMES).forEach(i -> low.send(acceptor, LaneProbe.laneProbe(dialer, StreamType.CONSENSUS, "buffered-" + i)));
        assertThat(low.offlineBufferSizeForTests(acceptor)).as("control: the dialer holds the frames before it dials").isEqualTo(FRAMES);
        low.dialForTests(infoOf(acceptor, high), false);
        awaitTrue(() -> high.tombstoneRejectionCountForTests(dialer) >= 1, AWAIT, "the acceptor rejected the tombstoned dialer (pair " + pair + ")");
        awaitTrue(() -> !TransportTripwireFixtures.connected(low, acceptor), AWAIT, "the dialer's link was closed by the rejection (pair " + pair + ")");
        var lost = low.offlineBufferSizeForTests(acceptor) < FRAMES;

        low.stop().await(AWAIT);
        high.stop().await(AWAIT);

        return lost;
    }

    /// Pairs (of PAIRS) whose dial consumed the buffer. Whether the dialer attaches and drains before the rejection reaches it is a
    /// few-millisecond race the dialer usually wins (8 of 8 pairs in each of three runs on the development host; a single-dial version
    /// of this test saw it lose now and then), so one pair proves nothing in
    /// either direction (a first version of this test flaked both ways), and the ordering cannot be forced by holding the acceptor's
    /// verdict: that blocks the loop the dialer's lane streams need, so the dialer never attaches. Eight fresh pairs make "never
    /// lost" implausible.
    private int pairsThatLostTheirFrames() {
        return (int) IntStream.rangeClosed(1, PAIRS).filter(this::onePairLostItsBufferedFrames).count();
    }

    @Test
    void dialer_drainsItsBufferOntoALinkTheAcceptorThenRejects_losingTheFrames() {
        var lost = pairsThatLostTheirFrames();

        assertThat(lost)
            .as("TRIPWIRE A-05: in %d of %d pairs the dialer drained its buffer onto a link the acceptor then rejected, and the "
                + "frames are gone; at least one is expected. The verdict now precedes the drain. Delete this tripwire and enable "
                + "dialer_keepsFramesBuffered_untilTheAcceptorHasAcceptedTheLink.", lost, PAIRS)
            .isPositive();
    }

    @Disabled("A-05/U-005: enable when the dialer drains only after the acceptor's verdict (verdict in the Hello response, or drain "
              + "after the first inbound frame). Today red: in most pairs the frames drained onto the rejected link are gone.")
    @Test
    void dialer_keepsFramesBuffered_untilTheAcceptorHasAcceptedTheLink() {
        assertThat(pairsThatLostTheirFrames()).as("pairs whose buffered frames were consumed by a rejected dial").isZero();
    }
}
