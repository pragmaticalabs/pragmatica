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
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.addressOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.evictedAfterConnected;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// Tripwire pair for M-02 (cluster T assessment, rc4 `7c786db0d3`): `ConnectionDirection` and the `ClusterNetwork`
/// contract say each pair has exactly one initiator, but a peer that was CONNECTED and is now EVICTED bypasses the
/// 60 s higher-id grace (`previouslyConnectedNowEvicted`, #491), so after a link loss BOTH ends dial on their next
/// reconcile tick. The dual dial is real but it is not the normal path: it needs both ticks inside one handshake time
/// (0.1 to 1 percent of link losses on a LAN, more on a lossy one).
///
/// Level: two real [QuicClusterNetwork]s, the real reconciler on a fake clock, each end seeded with the other as
/// EVICTED-after-CONNECTED. A topology lookup is the reconciler's proxy for "a dial was dispatched" (it happens only
/// after the backoff gate lets an attempt through; the stub returns no address so no socket is opened).
///
/// The real assertion states the single-dialer contract. If the overhaul instead keeps dual dial and makes the
/// arbitration safe, delete this pair and rely on the link-arbitration pair (`PeerStateArbitrationTripwireTest`);
/// that fork is a design decision (`[guess]` which way the overhaul goes).
@Timeout(60)
class DualDialTripwireTest {
    private static final NodeId LOW = new NodeId("aaa-low");
    private static final NodeId HIGH = new NodeId("zzz-high");

    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(TransportTripwireFixtures.AWAIT));
        networks.clear();
    }

    /// Dials dispatched by (low toward high, high toward low) on one reconcile tick after the link between them was lost.
    private long[] dialsOnTheTickAfterALinkLoss() {
        var lowTopology = new TripwireTopology(LOW, false, TimeSpan.timeSpan(1).seconds());
        var highTopology = new TripwireTopology(HIGH, false, TimeSpan.timeSpan(1).seconds());

        lowTopology.addPeer(NodeInfo.nodeInfo(HIGH, addressOf("127.0.0.1", 1)));
        highTopology.addPeer(NodeInfo.nodeInfo(LOW, addressOf("127.0.0.1", 1)));
        var low = startedNetwork(lowTopology, MessageRouter.mutable(), networks);
        var high = startedNetwork(highTopology, MessageRouter.mutable(), networks);
        var frozen = new AtomicLong(0);

        low.overrideWallClockForTests(frozen::get);
        high.overrideWallClockForTests(frozen::get);
        low.seedPeerForTests(HIGH, evictedAfterConnected(HIGH));
        high.seedPeerForTests(LOW, evictedAfterConnected(LOW));
        low.reconcileMissingPeersTick();
        high.reconcileMissingPeersTick();

        return new long[]{lowTopology.lookupsOf(HIGH), highTopology.lookupsOf(LOW)};
    }

    @Test
    void reconciler_bothEndsDialTheSamePair_afterALinkLoss() {
        var dials = dialsOnTheTickAfterALinkLoss();

        assertThat(dials).as("TRIPWIRE M-02: dials dispatched by [lower-id end, higher-id end] toward each other on the "
                             + "tick after a link loss; today both dial. Dial direction is now single per pair. Delete "
                             + "this tripwire and enable reconciler_atMostOneEndDials_afterALinkLoss.")
                         .containsExactly(1L, 1L);
    }

    @Disabled("M-02: enable when exactly one end of a pair dials after a link loss (or delete the pair if the overhaul "
              + "keeps dual dial and arbitrates it). Today red: both ends dial on the same tick.")
    @Test
    void reconciler_atMostOneEndDials_afterALinkLoss() {
        var dials = dialsOnTheTickAfterALinkLoss();

        assertThat(dials[0] + dials[1]).as("dials toward the same pair on the same tick").isLessThanOrEqualTo(1L);
    }
}
