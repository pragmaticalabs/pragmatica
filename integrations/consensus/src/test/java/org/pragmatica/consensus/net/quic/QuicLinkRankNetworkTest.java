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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.connected;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.holdsFor;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.infoOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.observeAttaches;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.ownDialDecided;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// #2138 — two real [QuicClusterNetwork]s dial each other at the same instant. Whatever order the Hello exchanges
/// and attaches land in at each end, both ends must end bound to ONE link, and both must hold it by the same rank:
/// the dial took its generation from [PeerState#nextDialGeneration], the acceptor read it from the CONTROL preamble,
/// and the arbitration read nothing else. A link without a rank here means a generation never reached an end.
@Timeout(60)
class QuicLinkRankNetworkTest {
    private static final TimeSpan PING_INTERVAL = TimeSpan.timeSpan(30).seconds();
    private static final TimeSpan HOLD = TimeSpan.timeSpan(400).millis();

    private static final AtomicInteger EXERCISED = new AtomicInteger();

    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    @RepeatedTest(10)
    void simultaneousDial_bothEndsBindTheSameRankedLink(RepetitionInfo repetition) {
        var round = repetition.getCurrentRepetition();
        var low = new NodeId("lr-a-" + round);
        var high = new NodeId("lr-b-" + round);
        var lowNet = startedNetwork(new TripwireTopology(low, false, PING_INTERVAL), MessageRouter.mutable(), networks);
        var highNet = startedNetwork(new TripwireTopology(high, false, PING_INTERVAL), MessageRouter.mutable(), networks);
        var lowAttaches = observeAttaches(lowNet);
        var highAttaches = observeAttaches(highNet);

        CompletableFuture.allOf(CompletableFuture.runAsync(() -> lowNet.dialForTests(infoOf(high, highNet), false)),
                                CompletableFuture.runAsync(() -> highNet.dialForTests(infoOf(low, lowNet), true)))
                         .orTimeout(AWAIT.millis(), TimeUnit.MILLISECONDS)
                         .join();
        awaitTrue(() -> connected(lowNet, high) && connected(highNet, low), AWAIT, "both ends CONNECTED");
        awaitTrue(() -> ownDialDecided(lowNet, lowAttaches) && ownDialDecided(highNet, highAttaches),
                  AWAIT,
                  "both ends' own dials decided");
        awaitTrue(() -> agreeOnARankedLink(lowNet, high, highNet, low), AWAIT, "both ends bound to one ranked link");

        assertThat(holdsFor(() -> agreeOnARankedLink(lowNet, high, highNet, low), HOLD)).as("the agreement holds once reached")
                                                                                         .isTrue();
        assertThat(decided(lowAttaches)).as("every reported attach carries its decision").allMatch(Option::isPresent);
        assertThat(decided(highAttaches)).allMatch(Option::isPresent);
        if (bothLinksReachedBothEnds(lowAttaches, highAttaches)) {
            EXERCISED.incrementAndGet();
            assertThat(rankOf(lowNet, high)).as("equal generations: the link the lower id dialed").isEqualTo(new LinkRank(1L, low));
            assertThat(rankOf(highNet, low)).isEqualTo(new LinkRank(1L, low));
        }
    }

    /// Whether this repetition really was a simultaneous dial: the dials race, and on a loaded runner either end's dial
    /// may be skipped or abandoned for the inbound link, leaving ONE link. That repetition still has to agree (above)
    /// but says nothing about the tie-break, so it is not counted as exercising it.
    private static boolean bothLinksReachedBothEnds(List<QuicClusterNetwork.AttachObservation> low,
                                                    List<QuicClusterNetwork.AttachObservation> high) {
        return reachedBoth(low) && reachedBoth(high);
    }

    private static boolean reachedBoth(List<QuicClusterNetwork.AttachObservation> seen) {
        return !inboundAttaches(seen).isEmpty() && seen.stream().anyMatch(attach -> attach.origin().startsWith("dial attempt"));
    }

    /// Simultaneity cannot be forced, so require that some repetition exercised the tie-break at all.
    @AfterAll
    static void tieBreakWasExercised() {
        assertThat(EXERCISED.get()).as("repetitions in which both links reached both ends").isPositive();
    }

    private static List<Option<PeerState.AttachResult>> decided(List<QuicClusterNetwork.AttachObservation> seen) {
        return seen.stream()
                   .filter(attach -> !attach.origin().equals("dial skipped"))
                   .map(QuicClusterNetwork.AttachObservation::result)
                   .toList();
    }

    private static List<QuicClusterNetwork.AttachObservation> inboundAttaches(List<QuicClusterNetwork.AttachObservation> seen) {
        return seen.stream().filter(attach -> attach.origin().equals("inbound Hello")).toList();
    }

    private static boolean agreeOnARankedLink(QuicClusterNetwork lowNet,
                                              NodeId high,
                                              QuicClusterNetwork highNet,
                                              NodeId low) {
        var atLow = rankOf(lowNet, high);
        var atHigh = rankOf(highNet, low);

        return atLow != null && atLow.equals(atHigh);
    }

    private static LinkRank rankOf(QuicClusterNetwork network, NodeId peer) {
        return network.activeConnectionForTests(peer)
                      .flatMap(QuicPeerConnection::linkRank)
                      .or((LinkRank) null);
    }
}
