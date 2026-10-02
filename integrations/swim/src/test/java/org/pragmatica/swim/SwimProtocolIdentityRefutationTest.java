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

package org.pragmatica.swim;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimProtocolPhaseAwareSuppressionTest.RecordingListener;
import org.pragmatica.swim.SwimProtocolPhaseAwareSuppressionTest.RecordingObservationSink;
import org.pragmatica.swim.SwimProtocolPhaseAwareSuppressionTest.RecordingTransport;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// #1830: a QUIC Hello identity mismatch — the seed's address answered with another NodeId because the IP
/// was recycled — refutes the SEEDED identity, so its FAULTY is not shielded by the cold-boot suppression.
/// The gate here never closes (`isBooting` stays `true`, the "no quorum, no leader" case the issue calls
/// indefinite): a refuted phantom must depart anyway, and nothing else may.
class SwimProtocolIdentityRefutationTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId PHANTOM = new NodeId("node-phantom");
    private static final NodeId CLAIMANT = new NodeId("node-claimant");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9000);
    private static final InetSocketAddress PHANTOM_ADDR = new InetSocketAddress("127.0.0.1", 9001);
    private static final InetSocketAddress CLAIMANT_ADDR = new InetSocketAddress("127.0.0.1", 9002);
    private static final Duration PAST_RESIDENCY = Duration.ofMillis(700);

    private static SwimConfig tightConfig() {
        return swimConfig(timeSpan(50).millis(),
                          timeSpan(20).millis(),
                          3,
                          timeSpan(150).millis(),
                          8,
                          timeSpan(50).millis()).withJoinGrace(timeSpan(0).millis())
                                                .withLhmMaxScore(1);
    }

    private static SwimProtocol bootingProtocol(RecordingObservationSink observations, AtomicBoolean linkUp) {
        return bootingProtocol(observations, linkUp, new RecordingTransport());
    }

    private static SwimProtocol bootingProtocol(RecordingObservationSink observations,
                                                AtomicBoolean linkUp,
                                                SwimTransport transport) {
        var protocol = SwimProtocol.swimProtocol(tightConfig(),
                                                 transport,
                                                 new RecordingListener(),
                                                 SELF_ID,
                                                 SELF_ADDR,
                                                 () -> true,
                                                 peer -> peer.equals(PHANTOM) && linkUp.get())
                                   .unwrap();

        protocol.addObservationListener(observations);

        return protocol;
    }

    @Test
    void refutedSeed_departsDuringColdBoot_withoutWaitingForTheWindow() {
        var observations = new RecordingObservationSink();
        var protocol = bootingProtocol(observations, new AtomicBoolean(false));

        protocol.addSeedMember(PHANTOM, PHANTOM_ADDR);
        protocol.recordTransportHint(PHANTOM, new TransportObservation.IdentityRefuted(PHANTOM, CLAIMANT));
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(3))
                   .until(() -> !departures(observations, PHANTOM).isEmpty());
            assertThat(faulties(observations, PHANTOM)).hasSize(1);
            assertThat(unknowns(observations, PHANTOM)).as("a refuted identity is never cold-boot suppressed")
                                                       .isEmpty();
        } finally {
            protocol.stop();
        }
    }

    @Test
    void refutationAfterSuppressedEdge_replaysTheEdgeImmediately() {
        var observations = new RecordingObservationSink();
        var protocol = bootingProtocol(observations, new AtomicBoolean(false));

        protocol.addSeedMember(PHANTOM, PHANTOM_ADDR);
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(3))
                   .until(() -> !unknowns(observations, PHANTOM).isEmpty());
            assertThat(departures(observations, PHANTOM)).isEmpty();

            protocol.recordTransportHint(PHANTOM, new TransportObservation.IdentityRefuted(PHANTOM, CLAIMANT));

            assertThat(departures(observations, PHANTOM)).as("the suppressed edge is replayed on the refutation itself")
                                                         .hasSize(1);
            assertThat(faulties(observations, PHANTOM)).hasSize(1);
        } finally {
            protocol.stop();
        }
    }

    @Test
    void refutation_neverTouchesTheClaimantAnsweringAtThatAddress() {
        var observations = new RecordingObservationSink();
        var protocol = bootingProtocol(observations, new AtomicBoolean(false), new ClaimantAnsweringTransport());

        protocol.addSeedMember(PHANTOM, PHANTOM_ADDR);
        // The claimant is a live peer this node already knows ALIVE, and it answers every probe.
        protocol.onMessage(CLAIMANT_ADDR,
                           new Ping(CLAIMANT, 1L, List.of(MembershipUpdate.membershipUpdate(CLAIMANT, MemberState.ALIVE, 5, CLAIMANT_ADDR))));
        protocol.recordTransportHint(PHANTOM, new TransportObservation.IdentityRefuted(PHANTOM, CLAIMANT));
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(3))
                   .until(() -> !departures(observations, PHANTOM).isEmpty());

            assertThat(departures(observations, CLAIMANT)).isEmpty();
            assertThat(faulties(observations, CLAIMANT)).isEmpty();
            assertThat(unknowns(observations, CLAIMANT)).isEmpty();
            assertThat(protocol.tombstonedForTest(CLAIMANT)).isFalse();
            assertThat(protocol.members()
                               .get(CLAIMANT)
                               .state()).as("only the dialed identity is refuted")
                                        .isEqualTo(MemberState.ALIVE);
        } finally {
            protocol.stop();
        }
    }

    /// The case where misattribution would do harm: the claimant is itself a seed this booting node has not
    /// yet seen HEALTHY (it booted moments ago on the recycled IP). It must keep its cold-boot protection.
    @Test
    void refutation_neverLiftsTheClaimantsColdBootProtection() {
        var observations = new RecordingObservationSink();
        var protocol = bootingProtocol(observations, new AtomicBoolean(false));

        protocol.addSeedMember(PHANTOM, PHANTOM_ADDR);
        protocol.addSeedMember(CLAIMANT, CLAIMANT_ADDR);
        protocol.recordTransportHint(PHANTOM, new TransportObservation.IdentityRefuted(PHANTOM, CLAIMANT));
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(3))
                   .until(() -> !departures(observations, PHANTOM).isEmpty() && !unknowns(observations, CLAIMANT).isEmpty());
            await().pollDelay(PAST_RESIDENCY)
                   .atMost(PAST_RESIDENCY.plusSeconds(1))
                   .until(() -> true);

            assertThat(departures(observations, CLAIMANT)).as("the not-yet-HEALTHY claimant stays cold-boot suppressed")
                                                          .isEmpty();
            assertThat(faulties(observations, CLAIMANT)).isEmpty();
        } finally {
            protocol.stop();
        }
    }

    @Test
    void refutedSeedWithLiveLinkUnderItsIdentity_isHeldByTransportVeto() {
        var observations = new RecordingObservationSink();
        var protocol = bootingProtocol(observations, new AtomicBoolean(true));

        protocol.addSeedMember(PHANTOM, PHANTOM_ADDR);
        protocol.recordTransportHint(PHANTOM, new TransportObservation.IdentityRefuted(PHANTOM, CLAIMANT));
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(3))
                   .until(() -> !unknowns(observations, PHANTOM).isEmpty());
            await().pollDelay(PAST_RESIDENCY)
                   .atMost(PAST_RESIDENCY.plusSeconds(1))
                   .until(() -> true);

            assertThat(departures(observations, PHANTOM)).as("a link verified under the identity outweighs the refutation")
                                                         .isEmpty();
        } finally {
            protocol.stop();
        }
    }

    @Test
    void linkUnderTheIdentity_retractsTheRefutation() {
        var observations = new RecordingObservationSink();
        var protocol = bootingProtocol(observations, new AtomicBoolean(false));

        protocol.addSeedMember(PHANTOM, PHANTOM_ADDR);
        protocol.recordTransportHint(PHANTOM, new TransportObservation.IdentityRefuted(PHANTOM, CLAIMANT));
        // The identity answered after all (link established, then lost again before the FAULTY edge).
        protocol.recordTransportHint(PHANTOM, new TransportObservation.PeerReachable(PHANTOM));
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(3))
                   .until(() -> !unknowns(observations, PHANTOM).isEmpty());
            await().pollDelay(PAST_RESIDENCY)
                   .atMost(PAST_RESIDENCY.plusSeconds(1))
                   .until(() -> true);

            assertThat(departures(observations, PHANTOM)).as("back under the ordinary cold-boot suppression")
                                                         .isEmpty();
        } finally {
            protocol.stop();
        }
    }

    /// Answers every probe sent to the claimant's address with the claimant's Ack, so the claimant stays
    /// ALIVE for the whole test on its own merits and any death-ward edge for it could only come from the
    /// refutation.
    private static final class ClaimantAnsweringTransport implements SwimTransport {
        private final AtomicReference<SwimMessageHandler> handler = new AtomicReference<>();

        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            if (target.equals(CLAIMANT_ADDR) && message instanceof Ping ping) {
                handler.get().onMessage(CLAIMANT_ADDR, Ack.ack(CLAIMANT, ping.sequence(), List.of()));
            }

            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> start(int port, SwimMessageHandler handler) {
            this.handler.set(handler);

            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }
    }

    private static List<SwimObservation.UnknownObserved> unknowns(RecordingObservationSink observations, NodeId peer) {
        return observations.byType(SwimObservation.UnknownObserved.class)
                           .stream()
                           .filter(observation -> observation.peer().equals(peer))
                           .toList();
    }

    private static List<SwimObservation.DepartedObserved> departures(RecordingObservationSink observations, NodeId peer) {
        return observations.byType(SwimObservation.DepartedObserved.class)
                           .stream()
                           .filter(observation -> observation.peer().equals(peer))
                           .toList();
    }

    private static List<SwimObservation.FaultyObserved> faulties(RecordingObservationSink observations, NodeId peer) {
        return observations.byType(SwimObservation.FaultyObserved.class)
                           .stream()
                           .filter(observation -> observation.peer().equals(peer))
                           .toList();
    }
}
