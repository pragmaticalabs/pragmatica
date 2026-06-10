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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// R2 phase tests: SWIM as canonical health-observation source.
///
/// Covers:
/// - Edge-triggered `SwimObservation` emission (P5 idempotent)
/// - Cold-boot suppression of FAULTY for never-healthy peers
/// - Transport-hint biased suspect window
/// - SWIM authority over transport hints
class SwimObservationStreamTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9000);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9001);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9002);

    @Nested
    class ColdBootSuppression {
        private RecordingTransport transport;
        private RecordingListener listener;
        private RecordingObservationSink observations;
        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            // Tight timings so the suspect-window expiry runs within the test budget.
            // startupDelay reduced to ~50ms so the first tick runs immediately.
            var config = swimConfig(timeSpan(50).millis(),
                                    timeSpan(20).millis(),
                                    3,
                                    timeSpan(150).millis(),
                                    8,
                                    timeSpan(50).millis());
            transport = new RecordingTransport();
            listener = new RecordingListener();
            observations = new RecordingObservationSink();
            protocol = SwimProtocol.swimProtocol(config, transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(_ -> null, v -> v);
            protocol.addObservationListener(observations);
        }

        @Test
        void coldBoot_neverSeenHealthy_doesNotEmitFaulty() {
            // Inject a SUSPECT for NODE_A via gossip — peer has never been HEALTHY.
            var suspectUpdate = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectUpdate)));

            assertThat(observations.byType(SwimObservation.SuspectObserved.class))
                .as("Suspect must still be emitted on the SUSPECT edge")
                .hasSize(1);

            // Now drive the suspect-window expiry — SWIM would normally transition to FAULTY.
            protocol.start();
            try {
                await().atMost(Duration.ofSeconds(3))
                       .until(() -> !observations.byType(SwimObservation.UnknownObserved.class).isEmpty()
                                    || !observations.byType(SwimObservation.FaultyObserved.class).isEmpty());

                assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                    .as("never-HEALTHY peer must NOT emit FaultyObserved during suspect-window expiry")
                    .isEmpty();
                assertThat(observations.byType(SwimObservation.UnknownObserved.class))
                    .as("never-HEALTHY peer's FAULTY transition must be suppressed and emit UnknownObserved")
                    .hasSize(1);
                assertThat(observations.byType(SwimObservation.UnknownObserved.class).getFirst().peer()).isEqualTo(NODE_A);
                assertThat(protocol.everSeenHealthyForTest(NODE_A))
                    .as("Cold-boot peer must remain ever-seen-healthy=false")
                    .isFalse();
            } finally {
                protocol.stop();
            }
        }

        @Test
        void coldBoot_oncePeerHealthy_thenLost_emitsFaulty() {
            // Step 1: NODE_A becomes HEALTHY via gossip
            var aliveUpdate = new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(aliveUpdate)));

            assertThat(observations.byType(SwimObservation.HealthyObserved.class)).hasSize(1);
            assertThat(protocol.everSeenHealthyForTest(NODE_A)).isTrue();

            // Step 2: peer transitions to SUSPECT via gossip
            var suspectUpdate = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 1, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 2L, List.of(suspectUpdate)));

            assertThat(observations.byType(SwimObservation.SuspectObserved.class)).hasSize(1);

            // Step 3: drive suspect-window expiry — must emit FaultyObserved (cold-boot
            // suppression DOES NOT apply once a peer has been HEALTHY at least once).
            protocol.start();
            try {
                await().atMost(Duration.ofSeconds(3))
                       .until(() -> !observations.byType(SwimObservation.FaultyObserved.class).isEmpty());

                assertThat(observations.byType(SwimObservation.FaultyObserved.class)).hasSize(1);
                assertThat(observations.byType(SwimObservation.UnknownObserved.class))
                    .as("Once-HEALTHY peer must NOT have its FAULTY suppressed")
                    .isEmpty();
            } finally {
                protocol.stop();
            }
        }
    }

    @Nested
    class TransportHintBias {
        private RecordingTransport transport;
        private RecordingListener listener;
        private RecordingObservationSink observations;
        private SwimProtocol protocol;
        private SwimConfig config;

        @BeforeEach
        void setUp() {
            // Long default suspect window (10s) so we can demonstrate the hint shortens it
            // toward the 3s floor. Test timeout window remains bounded by the floor.
            // startupDelay reduced to ~50ms so the first tick runs immediately.
            config = swimConfig(timeSpan(50).millis(),
                                timeSpan(20).millis(),
                                3,
                                timeSpan(10).seconds(),
                                8,
                                timeSpan(50).millis());
            transport = new RecordingTransport();
            listener = new RecordingListener();
            observations = new RecordingObservationSink();
            protocol = SwimProtocol.swimProtocol(config, transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(_ -> null, v -> v);
            protocol.addObservationListener(observations);
        }

        @Test
        void transportHint_unreachable_acceleratesSuspicion() {
            // Bring NODE_A to HEALTHY first (so cold-boot suppression doesn't apply)
            var aliveUpdate = new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(aliveUpdate)));
            assertThat(protocol.everSeenHealthyForTest(NODE_A)).isTrue();

            // Drive NODE_A into SUSPECT via gossip
            var suspectUpdate = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 1, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 2L, List.of(suspectUpdate)));
            assertThat(observations.byType(SwimObservation.SuspectObserved.class)).hasSize(1);

            // Apply transport hint: peer unreachable → suspect window biased toward 3s floor.
            protocol.recordTransportHint(NODE_A, new TransportObservation.PeerUnreachable(NODE_A,
                                                                                          TestCause.NETWORK_LOST));

            var startedAt = System.currentTimeMillis();
            protocol.start();
            try {
                await().atMost(Duration.ofSeconds(5))
                       .until(() -> !observations.byType(SwimObservation.FaultyObserved.class).isEmpty());

                var elapsed = System.currentTimeMillis() - startedAt;
                assertThat(elapsed)
                    .as("Suspect-window must be shortened toward 3s floor (default would be 10s)")
                    .isLessThan(5_000L);
            } finally {
                protocol.stop();
            }
        }

        @Test
        void transportHint_reachable_isNoOp_swimRemainsAuthoritative() {
            // Bring NODE_A to HEALTHY then to SUSPECT
            var aliveUpdate = new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(aliveUpdate)));

            var suspectUpdate = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 1, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 2L, List.of(suspectUpdate)));

            var preReachableTs = protocol.suspectTimestampForTest(NODE_A);
            assertThat(preReachableTs.isPresent())
                .as("NODE_A should have a recorded suspect timestamp after SUSPECT transition")
                .isTrue();

            // PeerReachable is a NO-OP: transport may report death, never life. SWIM probe-ack
            // is the sole recovery path, so the suspect window must be untouched.
            protocol.recordTransportHint(NODE_A, new TransportObservation.PeerReachable(NODE_A));

            var postReachableTs = protocol.suspectTimestampForTest(NODE_A);
            assertThat(postReachableTs.isPresent()).isTrue();
            long preTs = preReachableTs.fold(() -> 0L, l -> l);
            long postTs = postReachableTs.fold(() -> Long.MAX_VALUE, l -> l);
            assertThat(postTs)
                .as("PeerReachable no longer touches the suspect window — SWIM probe-ack is the sole recovery path")
                .isEqualTo(preTs);
            assertThat(protocol.members().get(NODE_A).state())
                .as("PeerReachable must leave the member SUSPECT — SWIM gossip/probe is authoritative")
                .isEqualTo(MemberState.SUSPECT);
        }
    }

    @Nested
    class IdempotentEmission {
        private RecordingTransport transport;
        private RecordingListener listener;
        private RecordingObservationSink observations;
        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            transport = new RecordingTransport();
            listener = new RecordingListener();
            observations = new RecordingObservationSink();
            protocol = SwimProtocol.swimProtocol(swimConfig(), transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(_ -> null, v -> v);
            protocol.addObservationListener(observations);
        }

        @Test
        void observation_isIdempotent_onRepeatedSameState() {
            // Drive HEALTHY edge once
            var aliveUpdate = new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(aliveUpdate)));

            assertThat(observations.byType(SwimObservation.HealthyObserved.class))
                .as("First HEALTHY edge must emit one HealthyObserved")
                .hasSize(1);

            // Replay the same gossip — at same incarnation+state, must be a no-op.
            for (var i = 0; i < 5; i++) {
                protocol.onMessage(ADDR_B, new Ping(NODE_B, 100L + i, List.of(aliveUpdate)));
            }

            assertThat(observations.byType(SwimObservation.HealthyObserved.class))
                .as("Re-emission of same HEALTHY state must NOT produce new observations")
                .hasSize(1);
        }

        @Test
        void observations_areEdgeTriggered() {
            // HEALTHY edge → 1 observation
            var aliveUpdate = new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(aliveUpdate)));

            // SUSPECT edge (different incarnation) → 1 observation
            var suspectUpdate = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 1, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 2L, List.of(suspectUpdate)));

            // FAULTY edge → 1 observation
            var faultyUpdate = new MembershipUpdate(NODE_A, MemberState.FAULTY, 2, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 3L, List.of(faultyUpdate)));

            // MemberDiscovered is a join event (feeds the QUIC dial set), not a health
            // edge — exclude it and assert exactly one observation per health-state edge.
            assertThat(observations.all.stream()
                                       .filter(o -> !(o instanceof SwimObservation.MemberDiscovered))
                                       .toList())
                .as("Edge-triggered emission: exactly one observation per state edge")
                .hasSize(3);
            assertThat(observations.byType(SwimObservation.HealthyObserved.class)).hasSize(1);
            assertThat(observations.byType(SwimObservation.SuspectObserved.class)).hasSize(1);
            assertThat(observations.byType(SwimObservation.FaultyObserved.class)).hasSize(1);
        }
    }

    @Nested
    class SwimAuthority {
        private RecordingTransport transport;
        private RecordingListener listener;
        private RecordingObservationSink observations;
        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            transport = new RecordingTransport();
            listener = new RecordingListener();
            observations = new RecordingObservationSink();
            protocol = SwimProtocol.swimProtocol(swimConfig(), transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(_ -> null, v -> v);
            protocol.addObservationListener(observations);
        }

        @Test
        void swimAuthority_overridesTransportHint_whenSwimDisagrees() {
            // SWIM observes NODE_A as ALIVE/HEALTHY
            var aliveUpdate = new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(aliveUpdate)));

            assertThat(observations.byType(SwimObservation.HealthyObserved.class)).hasSize(1);
            SwimHealth healthBefore = protocol.currentHealth()
                                              .healthOf(NODE_A)
                                              .fold(() -> SwimHealth.UNKNOWN, h -> h);
            assertThat(healthBefore).isEqualTo(SwimHealth.HEALTHY);

            // Snapshot the observation count BEFORE the hint (the ALIVE gossip emits a
            // MemberDiscovered join event plus the HealthyObserved health edge); the
            // assertion below proves the hint adds NOTHING, independent of that count.
            var observationsBeforeHint = observations.all.size();

            // QUIC says peer is unreachable — but SWIM has not observed any failure.
            // SWIM remains authoritative: the hint biases timers but does not override state.
            protocol.recordTransportHint(NODE_A, new TransportObservation.PeerUnreachable(NODE_A,
                                                                                          TestCause.NETWORK_LOST));

            // No new observation produced — SWIM state unchanged.
            assertThat(observations.all)
                .as("Transport hint alone must NOT produce a SwimObservation — SWIM is authoritative")
                .hasSize(observationsBeforeHint);
            SwimHealth healthAfter = protocol.currentHealth()
                                             .healthOf(NODE_A)
                                             .fold(() -> SwimHealth.UNKNOWN, h -> h);
            assertThat(healthAfter)
                .as("SWIM remains authoritative — health stays HEALTHY")
                .isEqualTo(SwimHealth.HEALTHY);
        }
    }

    @Nested
    class HealthSnapshotPullChannel {
        private RecordingTransport transport;
        private RecordingListener listener;
        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            transport = new RecordingTransport();
            listener = new RecordingListener();
            protocol = SwimProtocol.swimProtocol(swimConfig(), transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(_ -> null, v -> v);
        }

        @Test
        void currentHealth_neverSeenPeer_classifiedAsUnknown() {
            // Inject SUSPECT update for never-healthy peer
            var suspectUpdate = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectUpdate)));

            SwimHealth health = protocol.currentHealth()
                                        .healthOf(NODE_A)
                                        .fold(() -> SwimHealth.UNKNOWN, h -> h);
            assertThat(health)
                .as("Never-HEALTHY non-ALIVE peer must classify as UNKNOWN")
                .isEqualTo(SwimHealth.UNKNOWN);
        }

        @Test
        void currentHealth_alivePeer_classifiedAsHealthy() {
            var aliveUpdate = new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(aliveUpdate)));

            SwimHealth health = protocol.currentHealth()
                                        .healthOf(NODE_A)
                                        .fold(() -> SwimHealth.UNKNOWN, h -> h);
            assertThat(health).isEqualTo(SwimHealth.HEALTHY);
        }
    }

    // -- Test infrastructure --

    enum TestCause implements Cause {
        NETWORK_LOST("Test cause: network lost");

        private final String message;

        TestCause(String message) {
            this.message = message;
        }

        @Override public String message() {
            return message;
        }
    }

    record SentMessage(InetSocketAddress target, SwimMessage message) {}

    static class RecordingTransport implements SwimTransport {
        final CopyOnWriteArrayList<SentMessage> sentMessages = new CopyOnWriteArrayList<>();
        final AtomicReference<SwimMessageHandler> handler = new AtomicReference<>();

        @Override public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            sentMessages.add(new SentMessage(target, message));
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> start(int port, SwimMessageHandler handler) {
            this.handler.set(handler);
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> stop() {
            handler.set(null);
            return Promise.success(Unit.unit());
        }
    }

    static class RecordingListener implements SwimMembershipListener {
        @Override public void onMemberJoined(SwimMember member) {}
        @Override public void onMemberSuspect(SwimMember member) {}
        @Override public void onMemberFaulty(SwimMember member) {}
        @Override public void onMemberLeft(NodeId nodeId) {}
    }

    static class RecordingObservationSink implements Consumer<SwimObservation> {
        final CopyOnWriteArrayList<SwimObservation> all = new CopyOnWriteArrayList<>();

        @Override public void accept(SwimObservation observation) {
            all.add(observation);
        }

        <T extends SwimObservation> List<T> byType(Class<T> type) {
            return all.stream()
                      .filter(type::isInstance)
                      .map(type::cast)
                      .toList();
        }
    }
}
