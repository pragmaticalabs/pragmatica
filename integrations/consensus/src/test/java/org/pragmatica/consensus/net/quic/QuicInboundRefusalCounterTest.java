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
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.StreamType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.connected;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.infoOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// #1535 item 2: each inbound refusal predicate (origin binding, inbound message policy, fault filter)
/// increments its own counter, and a refused message is not routed. Two real [QuicClusterNetwork]s on loopback.
@Timeout(120)
class QuicInboundRefusalCounterTest {
    private static final NodeId A = new NodeId("aaa-refuse");
    private static final NodeId B = new NodeId("zzz-refuse");
    private static final NodeId SPOOFED = new NodeId("mmm-refuse");
    private static final TimeSpan PING = TimeSpan.timeSpan(250).millis();

    private final List<QuicClusterNetwork> networks = new ArrayList<>();
    private final List<String> delivered = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    private QuicClusterNetwork[] connectedPair() {
        var routerOfB = MessageRouter.mutable();

        routerOfB.addRoute(LaneProbe.class, probe -> delivered.add(probe.marker()));
        var a = startedNetwork(new TripwireTopology(A, false, PING), MessageRouter.mutable(), networks);
        var b = startedNetwork(new TripwireTopology(B, false, PING), routerOfB, networks);

        a.dialForTests(infoOf(B, b), false);
        awaitTrue(() -> connected(a, B) && connected(b, A), AWAIT, "A and B CONNECTED");

        return new QuicClusterNetwork[] {a, b};
    }

    private static long drops(QuicClusterNetwork network, String key) {
        return network.quicMetrics().snapshot().get(key).longValue();
    }

    @Test
    void unboundOrigin_isCountedAndNotRouted() {
        var pair = connectedPair();

        pair[0].send(B, LaneProbe.laneProbe(SPOOFED, StreamType.CONSENSUS, "spoofed"));
        awaitTrue(() -> drops(pair[1], "quic_inbound_unbound_drops_total") == 1, AWAIT, "unbound drop counted");

        assertThat(delivered).doesNotContain("spoofed");
        assertThat(drops(pair[1], "quic_inbound_policy_drops_total")).isZero();
        assertThat(drops(pair[1], "quic_inbound_fault_filter_drops_total")).isZero();
    }

    @Test
    void policyRefusal_isCountedAndNotRouted() {
        var pair = connectedPair();

        pair[1].setInboundMessagePolicy((_, _) -> false);
        pair[0].send(B, LaneProbe.laneProbe(A, StreamType.CONSENSUS, "policy"));
        awaitTrue(() -> drops(pair[1], "quic_inbound_policy_drops_total") == 1, AWAIT, "policy drop counted");

        assertThat(delivered).doesNotContain("policy");
        assertThat(drops(pair[1], "quic_inbound_unbound_drops_total")).isZero();
        assertThat(drops(pair[1], "quic_inbound_fault_filter_drops_total")).isZero();
    }

    @Test
    void faultFilterDrop_isCountedAndNotRouted() {
        var pair = connectedPair();

        pair[1].setInboundFaultFilter((_, _) -> false);
        pair[0].send(B, LaneProbe.laneProbe(A, StreamType.CONSENSUS, "filtered"));
        awaitTrue(() -> drops(pair[1], "quic_inbound_fault_filter_drops_total") == 1, AWAIT, "fault-filter drop counted");

        assertThat(delivered).doesNotContain("filtered");
        assertThat(drops(pair[1], "quic_inbound_unbound_drops_total")).isZero();
        assertThat(drops(pair[1], "quic_inbound_policy_drops_total")).isZero();
    }

    @Test
    void acceptedMessage_isRoutedAndCountsNothing() {
        var pair = connectedPair();

        pair[0].send(B, LaneProbe.laneProbe(A, StreamType.CONSENSUS, "ok"));
        awaitTrue(() -> delivered.contains("ok"), AWAIT, "control: a bound, unfiltered message is delivered");

        assertThat(drops(pair[1], "quic_inbound_unbound_drops_total")).isZero();
        assertThat(drops(pair[1], "quic_inbound_policy_drops_total")).isZero();
        assertThat(drops(pair[1], "quic_inbound_fault_filter_drops_total")).isZero();
    }

    @Test
    void isLoggedRefusal_logsTheFirstAndEveryPowerOfTwo() {
        assertThat(java.util.stream.LongStream.rangeClosed(1, 9)
                       .filter(QuicClusterNetwork::isLoggedRefusal)
                       .boxed()
                       .toList()).containsExactly(1L, 2L, 4L, 8L);
    }
}
