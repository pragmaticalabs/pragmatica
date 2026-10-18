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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.consensus.topology.TransportObservation.PeerReconnected;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.connected;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.infoOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// Producer-side control for the A-03 tripwire (`LeaderReconnectTripwireTest`): the consumer test feeds the leader FSM a
/// `PeerDisconnected` whose view omits the peer and a `PeerReconnected` whose view contains it. This test shows that the
/// real transport emits exactly that pair around a link loss and re-attach, so the consumer test's inputs are what the
/// transport produces and not a convenient invention. It is a permanent control (green before and after the A-03 fix),
/// not a tripwire.
@Timeout(60)
class QuicReconnectObservationControlTest {
    private static final NodeId LOW = new NodeId("aaa-recon");
    private static final NodeId HIGH = new NodeId("zzz-recon");

    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    @Test
    void reattachAfterALinkLoss_emitsDisconnectedWithoutThePeer_thenReconnectedWithThePeer() {
        var observed = new CopyOnWriteArrayList<TransportObservation>();
        var lowRouter = MessageRouter.mutable();

        lowRouter.addRoute(PeerJoined.class, observed::add);
        lowRouter.addRoute(PeerDisconnected.class, observed::add);
        lowRouter.addRoute(PeerReconnected.class, observed::add);
        var low = startedNetwork(new TripwireTopology(LOW, false, TimeSpan.timeSpan(30).seconds()), lowRouter, networks);
        var high = startedNetwork(new TripwireTopology(HIGH, false, TimeSpan.timeSpan(30).seconds()), MessageRouter.mutable(), networks);
        var highInfo = infoOf(HIGH, high);

        low.dialForTests(highInfo, false);
        awaitTrue(() -> connected(low, HIGH) && connected(high, LOW), AWAIT, "both ends CONNECTED");
        low.peerStateForTests(HIGH).evict(System.nanoTime()).onPresent(connection -> connection.close().await(AWAIT));
        awaitTrue(() -> observed.stream().anyMatch(PeerDisconnected.class::isInstance), AWAIT, "PeerDisconnected at the dialer");
        awaitTrue(() -> !connected(high, LOW), AWAIT, "the acceptor noticed the closed link");
        low.dialForTests(highInfo, false);
        awaitTrue(() -> observed.stream().anyMatch(PeerReconnected.class::isInstance), AWAIT, "PeerReconnected at the dialer");

        var disconnected = observed.stream().filter(PeerDisconnected.class::isInstance).map(PeerDisconnected.class::cast).findFirst().orElseThrow();
        var reconnected = observed.stream().filter(PeerReconnected.class::isInstance).map(PeerReconnected.class::cast).findFirst().orElseThrow();

        assertThat(disconnected.nodeId()).isEqualTo(HIGH);
        assertThat(disconnected.topology()).as("the view carried by the disconnect omits the peer").doesNotContain(HIGH);
        assertThat(reconnected.nodeId()).isEqualTo(HIGH);
        assertThat(reconnected.topology()).as("the view carried by the reconnect contains the peer").contains(HIGH);
        assertThat(observed.stream().filter(PeerJoined.class::isInstance).count()).as("the first attach was the only PeerJoined").isEqualTo(1L);
    }
}
