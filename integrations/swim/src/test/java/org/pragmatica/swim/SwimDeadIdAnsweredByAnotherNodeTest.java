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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimMessage.PingReq;
import org.pragmatica.swim.SwimProtocolPhaseAwareSuppressionTest.RecordingListener;
import org.pragmatica.swim.SwimProtocolPhaseAwareSuppressionTest.RecordingObservationSink;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;
import static org.pragmatica.swim.SwimMessage.MembershipUpdate.membershipUpdate;

/// #1749 premise test: a dead id A whose address was recycled to ANOTHER node B must not stay alive in SWIM.
///
/// Every datagram from the address carries `Ack.from = B`. SWIM credits a probe answer to the probed id only when
/// `ack.from()` equals the probe's target (`acceptProbeAckIfFromTarget`, and `forwardAckIfFromTarget` on the relay
/// path), so A's probe stays pending, times out, and A walks SUSPECT, FAULTY, departed. B's own gossip about itself
/// at that address is hearsay about B and never about A.
///
/// The clock is the real one (the protocol reads `System.currentTimeMillis()` at its use sites and has no injected
/// clock); every wait is a condition wait bounded by a limit derived from the configuration, never a sleep.
class SwimDeadIdAnsweredByAnotherNodeTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId DEAD = new NodeId("node-dead");
    private static final NodeId IMPOSTOR = new NodeId("node-impostor");
    private static final NodeId ACCUSER = new NodeId("node-accuser");
    private static final NodeId REQUESTER = new NodeId("node-requester");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9100);
    private static final InetSocketAddress DEAD_ADDR = new InetSocketAddress("127.0.0.1", 9101);
    private static final InetSocketAddress ACCUSER_ADDR = new InetSocketAddress("127.0.0.1", 9102);
    private static final InetSocketAddress REQUESTER_ADDR = new InetSocketAddress("127.0.0.1", 9103);
    private static final int PERIOD_MS = 50;
    private static final int PROBE_TIMEOUT_MS = 20;
    private static final int SUSPECT_TIMEOUT_MS = 1_000;
    /// The configured bound for A to leave the live set once its probes stop being credited: one probe period to
    /// pick it, the direct and indirect probe timeouts, the suspicion window at its widest cluster-size
    /// multiplier, two ticks for the expiry sweep, and one second of scheduler slack.
    private static final Duration DEPARTURE_BOUND = Duration.ofMillis(PERIOD_MS + 2L * PROBE_TIMEOUT_MS
                                                                       + (long) (SUSPECT_TIMEOUT_MS * SwimConfig.MAX_CLUSTER_SIZE_WINDOW_MULTIPLIER)
                                                                       + 2L * PERIOD_MS + 1_000L);
    private static final Duration SETTLE_BOUND = Duration.ofSeconds(5);

    private static SwimConfig config() {
        return swimConfig(timeSpan(PERIOD_MS).millis(),
                          timeSpan(PROBE_TIMEOUT_MS).millis(),
                          3,
                          timeSpan(SUSPECT_TIMEOUT_MS).millis(),
                          8,
                          timeSpan(PERIOD_MS).millis()).withJoinGrace(timeSpan(0).millis())
                                                       .withLhmMaxScore(1);
    }

    private static SwimProtocol protocol(AnsweringTransport transport,
                                         RecordingObservationSink observations,
                                         BooleanSupplier booting) {
        var protocol = SwimTestProtocols.swimProtocol(config(),
                                                      transport,
                                                      new RecordingListener(),
                                                      SELF_ID,
                                                      SELF_ADDR,
                                                      booting)
                                        .unwrap();

        protocol.addObservationListener(observations);

        return protocol;
    }

    /// A was HEALTHY, its node is gone, B answers at A's address: A is SUSPECT, then (with a second accuser, the
    /// production gate for a once-HEALTHY id) FAULTY and departed within the bound, and is never HEALTHY again.
    @Test
    void healthyIdWhoseAddressIsAnsweredByAnotherNode_goesSuspectFaultyAndDeparts() {
        var transport = new AnsweringTransport(DEAD);
        var observations = new RecordingObservationSink();
        var protocol = protocol(transport, observations, () -> false);

        protocol.addSeedMember(DEAD, DEAD_ADDR);
        protocol.start();
        try {
            await().atMost(SETTLE_BOUND)
                   .until(() -> !healthy(observations).isEmpty());
            var healthyBefore = healthy(observations).size();

            transport.answerer.set(IMPOSTOR);
            awaitSuspect(protocol);
            confirmSuspicionFromSecondAccuser(protocol);

            await().atMost(DEPARTURE_BOUND)
                   .until(() -> !departures(observations).isEmpty());
            assertThat(protocol.members().get(DEAD).state()).isEqualTo(MemberState.FAULTY);
            assertThat(healthy(observations)).as("the impostor's answers never refresh the dead id")
                                             .hasSize(healthyBefore);
            assertThat(protocol.healthOf(DEAD)).isNotEqualTo(SwimHealth.HEALTHY);
        } finally {
            protocol.stop();
        }
    }

    /// The same node with no second accuser: the lone prober still moves A to SUSPECT and FAULTY and never credits
    /// the impostor to A; the terminal departure is held by the co-confirmation kill-gate (#336), which emits
    /// UNKNOWN instead, so the id leaves HEALTHY either way.
    @Test
    void healthyIdWithLoneProber_reachesFaultyAndLeavesHealthy() {
        var transport = new AnsweringTransport(DEAD);
        var observations = new RecordingObservationSink();
        var protocol = protocol(transport, observations, () -> false);

        protocol.addSeedMember(DEAD, DEAD_ADDR);
        protocol.start();
        try {
            await().atMost(SETTLE_BOUND)
                   .until(() -> !healthy(observations).isEmpty());
            var healthyBefore = healthy(observations).size();

            transport.answerer.set(IMPOSTOR);
            await().atMost(DEPARTURE_BOUND)
                   .until(() -> protocol.members().get(DEAD).state() == MemberState.FAULTY);
            await().atMost(SETTLE_BOUND)
                   .until(() -> protocol.healthOf(DEAD) != SwimHealth.HEALTHY);
            assertThat(healthy(observations)).hasSize(healthyBefore);
        } finally {
            protocol.stop();
        }
    }

    /// B gossips about itself at A's address, in the answering Ack's piggyback and in a Ping from that address:
    /// both are about B, so A is not refreshed and still departs.
    @Test
    void impostorGossipingAboutItselfAtTheAddress_doesNotRefreshTheDeadId() {
        var transport = new AnsweringTransport(DEAD);
        var observations = new RecordingObservationSink();
        var protocol = protocol(transport, observations, () -> false);

        protocol.addSeedMember(DEAD, DEAD_ADDR);
        protocol.start();
        try {
            await().atMost(SETTLE_BOUND)
                   .until(() -> !healthy(observations, DEAD).isEmpty());
            var healthyBefore = healthy(observations, DEAD).size();
            var impostorAboutItself = membershipUpdate(IMPOSTOR, MemberState.ALIVE, 5, DEAD_ADDR);

            transport.piggyback.set(List.of(impostorAboutItself));
            transport.answerer.set(IMPOSTOR);
            protocol.onMessage(DEAD_ADDR, Ping.ping(IMPOSTOR, 900_001L, List.of(impostorAboutItself)));
            awaitSuspect(protocol);
            protocol.onMessage(DEAD_ADDR, Ping.ping(IMPOSTOR, 900_002L, List.of(impostorAboutItself)));
            confirmSuspicionFromSecondAccuser(protocol);

            await().atMost(DEPARTURE_BOUND)
                   .until(() -> !departures(observations).isEmpty());
            assertThat(healthy(observations, DEAD)).as("B's self-gossip is not alive-evidence for A")
                                                   .hasSize(healthyBefore);
            assertThat(protocol.members().get(DEAD).state()).isEqualTo(MemberState.FAULTY);
        } finally {
            protocol.stop();
        }
    }

    /// A never HEALTHY (a seed on a cold boot), no refutation hint: the FAULTY verdict is held UNKNOWN while the
    /// cold-boot gate is open (nothing is credited to A meanwhile), and A departs once the gate closes.
    @Test
    void neverHealthySeedAnsweredByAnotherNode_isHeldWhileBootingThenDeparts() {
        var transport = new AnsweringTransport(IMPOSTOR);
        var observations = new RecordingObservationSink();
        var booting = new AtomicBoolean(true);
        var protocol = protocol(transport, observations, booting::get);

        protocol.addSeedMember(DEAD, DEAD_ADDR);
        protocol.start();
        try {
            await().atMost(DEPARTURE_BOUND)
                   .until(() -> !unknowns(observations).isEmpty());
            assertThat(departures(observations)).as("cold-boot suppression holds the verdict")
                                                .isEmpty();
            assertThat(healthy(observations)).as("the impostor never makes the seed HEALTHY")
                                             .isEmpty();

            booting.set(false);

            await().atMost(DEPARTURE_BOUND)
                   .until(() -> !departures(observations).isEmpty());
            assertThat(healthy(observations)).isEmpty();
        } finally {
            protocol.stop();
        }
    }

    /// The same cold boot with the transport's IdentityRefuted hint (the dial to A's address was answered by B):
    /// the suppression is lifted and A departs while the gate is still open.
    @Test
    void neverHealthySeedAnsweredByAnotherNode_withRefutationHint_departsWhileBooting() {
        var transport = new AnsweringTransport(IMPOSTOR);
        var observations = new RecordingObservationSink();
        var protocol = protocol(transport, observations, () -> true);

        protocol.addSeedMember(DEAD, DEAD_ADDR);
        protocol.recordTransportHint(DEAD, new TransportObservation.IdentityRefuted(DEAD, IMPOSTOR));
        protocol.start();
        try {
            await().atMost(DEPARTURE_BOUND)
                   .until(() -> !departures(observations).isEmpty());
            assertThat(unknowns(observations)).as("a refuted identity is never cold-boot suppressed")
                                              .isEmpty();
            assertThat(healthy(observations)).isEmpty();
        } finally {
            protocol.stop();
        }
    }

    /// Relay path: this node probes A on behalf of a requester and B answers from A's address. The answer is not
    /// forwarded to the requester and is alive-evidence for nobody (B is held SUSPECT here to show it is not
    /// promoted either).
    @Test
    void relayedProbeAnsweredByAnotherNode_isNotForwardedAndCreditsNobody() {
        var transport = new AnsweringTransport(IMPOSTOR);
        var observations = new RecordingObservationSink();
        var protocol = protocol(transport, observations, () -> false);

        transport.handler.set(protocol);
        protocol.putMemberForTest(DEAD, DEAD_ADDR, MemberState.ALIVE);
        protocol.putMemberForTest(IMPOSTOR, DEAD_ADDR, MemberState.SUSPECT);

        protocol.onMessage(REQUESTER_ADDR, PingReq.pingReq(REQUESTER, DEAD, 77L));

        assertThat(transport.sentTo(DEAD_ADDR, Ping.class)).as("control: the relay did probe the target's address")
                                                           .hasSize(1);
        assertThat(transport.sentTo(REQUESTER_ADDR, Ack.class)).as("a mismatched answer is not forwarded")
                                                               .isEmpty();
        assertThat(protocol.members().get(IMPOSTOR).state()).as("and credits the answering node nothing")
                                                            .isEqualTo(MemberState.SUSPECT);
        assertThat(healthy(observations)).isEmpty();
    }

    private static void awaitSuspect(SwimProtocol protocol) {
        await().atMost(DEPARTURE_BOUND)
               .until(() -> protocol.members().get(DEAD).state() == MemberState.SUSPECT);
    }

    /// A second, independent accuser: a node whose own probes of A are answered by B too gossips SUSPECT(A).
    private static void confirmSuspicionFromSecondAccuser(SwimProtocol protocol) {
        var incarnation = protocol.members().get(DEAD).incarnation();

        protocol.onMessage(ACCUSER_ADDR,
                           Ping.ping(ACCUSER,
                                     900_100L,
                                     List.of(membershipUpdate(DEAD, MemberState.SUSPECT, incarnation, DEAD_ADDR))));
    }

    private static List<SwimObservation.HealthyObserved> healthy(RecordingObservationSink observations) {
        return healthy(observations, DEAD);
    }

    private static List<SwimObservation.HealthyObserved> healthy(RecordingObservationSink observations, NodeId peer) {
        return observations.byType(SwimObservation.HealthyObserved.class)
                           .stream()
                           .filter(observation -> observation.peer().equals(peer))
                           .toList();
    }

    private static List<SwimObservation.UnknownObserved> unknowns(RecordingObservationSink observations) {
        return observations.byType(SwimObservation.UnknownObserved.class)
                           .stream()
                           .filter(observation -> observation.peer().equals(DEAD))
                           .toList();
    }

    private static List<SwimObservation.DepartedObserved> departures(RecordingObservationSink observations) {
        return observations.byType(SwimObservation.DepartedObserved.class)
                           .stream()
                           .filter(observation -> observation.peer().equals(DEAD))
                           .toList();
    }

    /// Answers every Ping sent to A's address with an Ack whose `from` is the current `answerer`: A itself while
    /// the node is alive, the impostor once the address is recycled. Everything sent is recorded.
    private static final class AnsweringTransport implements SwimTransport {
        private record Sent(InetSocketAddress target, SwimMessage message) {}

        final AtomicReference<SwimMessageHandler> handler = new AtomicReference<>();
        final AtomicReference<NodeId> answerer;
        final AtomicReference<List<MembershipUpdate>> piggyback = new AtomicReference<>(List.of());
        private final CopyOnWriteArrayList<Sent> sent = new CopyOnWriteArrayList<>();

        AnsweringTransport(NodeId initialAnswerer) {
            this.answerer = new AtomicReference<>(initialAnswerer);
        }

        <T extends SwimMessage> List<T> sentTo(InetSocketAddress target, Class<T> type) {
            return sent.stream()
                       .filter(entry -> entry.target().equals(target))
                       .map(Sent::message)
                       .filter(type::isInstance)
                       .map(type::cast)
                       .toList();
        }

        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            sent.add(new Sent(target, message));
            if (target.equals(DEAD_ADDR) && message instanceof Ping ping && handler.get() != null) {
                handler.get().onMessage(DEAD_ADDR, Ack.ack(answerer.get(), ping.sequence(), piggyback.get()));
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
}
