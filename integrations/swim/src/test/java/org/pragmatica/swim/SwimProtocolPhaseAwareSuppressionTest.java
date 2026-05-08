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
import java.util.function.Consumer;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// Audit Step 6 (2026-05-07) — phase-aware SWIM cold-boot suppression.
///
/// Verifies that the per-peer `everSeenHealthy` cold-boot gate is preserved while
/// the cluster is in `BOOTING` and bypassed once `NORMAL` is reached, fixing the
/// "container killed before first Ping ack" silent-detection regression.
class SwimProtocolPhaseAwareSuppressionTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9000);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9001);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9002);

    private static SwimConfig tightConfig() {
        // startupDelay 50ms, period 20ms, suspectTimeout 150ms — keeps the suspect-window
        // expiry within the test budget while preserving the SWIM ordering invariants.
        return swimConfig(timeSpan(50).millis(),
                          timeSpan(20).millis(),
                          3,
                          timeSpan(150).millis(),
                          8,
                          timeSpan(50).millis(),
                          timeSpan(50).millis());
    }

    @Nested
    class BootingPhase {
        @Test
        void booting_neverHealthyPeer_suspectThenFaulty_emitsUnknownObserved() {
            // BOOTING phase: legacy per-peer `everSeenHealthy` cold-boot suppression
            // is preserved. A never-HEALTHY peer transitioning SUSPECT -> FAULTY
            // emits `UnknownObserved`, NOT `FaultyObserved`.
            var transport = new RecordingTransport();
            var listener = new RecordingListener();
            var observations = new RecordingObservationSink();
            var protocol = SwimProtocol.swimProtocol(tightConfig(),
                                                     transport,
                                                     listener,
                                                     SELF_ID,
                                                     SELF_ADDR,
                                                     () -> true) // BOOTING
                                       .unwrap();
            protocol.addObservationListener(observations);

            // Inject SUSPECT for never-HEALTHY peer via gossip.
            var suspectUpdate = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectUpdate)));

            protocol.start();
            try {
                await().atMost(Duration.ofSeconds(3))
                       .until(() -> !observations.byType(SwimObservation.UnknownObserved.class).isEmpty()
                                    || !observations.byType(SwimObservation.FaultyObserved.class).isEmpty());

                assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                    .as("BOOTING phase: never-HEALTHY peer must NOT emit FaultyObserved")
                    .isEmpty();
                assertThat(observations.byType(SwimObservation.UnknownObserved.class))
                    .as("BOOTING phase: cold-boot suppression emits UnknownObserved instead")
                    .hasSize(1);
                assertThat(observations.byType(SwimObservation.UnknownObserved.class)
                                       .getFirst()
                                       .peer()).isEqualTo(NODE_A);
            } finally {
                protocol.stop();
            }
        }
    }

    @Nested
    class NormalPhase {
        @Test
        void normal_neverHealthyPeer_suspectThenFaulty_emitsFaultyObserved() {
            // NORMAL phase: cold-boot suppression is bypassed. A peer killed before
            // its first successful Ping still produces a `FaultyObserved` so
            // HealthReconciler aggregates and writes DECOMMISSIONED, restoring the
            // NODE_LEFT / NODE_FAILED downstream event path.
            var transport = new RecordingTransport();
            var listener = new RecordingListener();
            var observations = new RecordingObservationSink();
            var protocol = SwimProtocol.swimProtocol(tightConfig(),
                                                     transport,
                                                     listener,
                                                     SELF_ID,
                                                     SELF_ADDR,
                                                     () -> false) // NORMAL
                                       .unwrap();
            protocol.addObservationListener(observations);

            var suspectUpdate = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectUpdate)));

            protocol.start();
            try {
                await().atMost(Duration.ofSeconds(3))
                       .until(() -> !observations.byType(SwimObservation.FaultyObserved.class).isEmpty()
                                    || !observations.byType(SwimObservation.UnknownObserved.class).isEmpty());

                assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                    .as("NORMAL phase: never-HEALTHY peer MUST emit FaultyObserved (cold-boot suppression bypassed)")
                    .hasSize(1);
                assertThat(observations.byType(SwimObservation.UnknownObserved.class))
                    .as("NORMAL phase: must NOT emit UnknownObserved")
                    .isEmpty();
                assertThat(observations.byType(SwimObservation.FaultyObserved.class)
                                       .getFirst()
                                       .peer()).isEqualTo(NODE_A);
            } finally {
                protocol.stop();
            }
        }

        @Test
        void normal_phaseSwitchesMidLife_subsequentFaultyEmits() {
            // Simulate the production wiring: phase starts BOOTING, flips NORMAL once
            // HealthReconciler projects the cluster as steady. Verify the SAME protocol
            // instance honors both phases on subsequent FAULTY edges.
            var phase = new AtomicBoolean(true); // BOOTING
            var transport = new RecordingTransport();
            var listener = new RecordingListener();
            var observations = new RecordingObservationSink();
            var protocol = SwimProtocol.swimProtocol(tightConfig(),
                                                     transport,
                                                     listener,
                                                     SELF_ID,
                                                     SELF_ADDR,
                                                     phase::get)
                                       .unwrap();
            protocol.addObservationListener(observations);

            // Step 1: BOOTING — never-HEALTHY NODE_A goes SUSPECT.
            var suspectA = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectA)));

            protocol.start();
            try {
                await().atMost(Duration.ofSeconds(3))
                       .until(() -> !observations.byType(SwimObservation.UnknownObserved.class).isEmpty());
                assertThat(observations.byType(SwimObservation.UnknownObserved.class)).hasSize(1);
                assertThat(observations.byType(SwimObservation.FaultyObserved.class)).isEmpty();

                // Step 2: phase flips NORMAL. Inject a FAULTY for NODE_B (also never-HEALTHY)
                // via direct gossip — must emit FaultyObserved.
                phase.set(false);
                var faultyB = new MembershipUpdate(NODE_B, MemberState.FAULTY, 0, ADDR_B);
                protocol.onMessage(ADDR_A, new Ping(NODE_A, 2L, List.of(faultyB)));

                await().atMost(Duration.ofSeconds(2))
                       .until(() -> !observations.byType(SwimObservation.FaultyObserved.class).isEmpty());
                assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                    .as("NORMAL phase: FAULTY direct gossip must emit FaultyObserved")
                    .hasSize(1);
                assertThat(observations.byType(SwimObservation.FaultyObserved.class)
                                       .getFirst()
                                       .peer()).isEqualTo(NODE_B);
            } finally {
                protocol.stop();
            }
        }
    }

    // -- Test infrastructure --

    static class RecordingTransport implements SwimTransport {
        final CopyOnWriteArrayList<Object> sentMessages = new CopyOnWriteArrayList<>();
        final AtomicReference<SwimMessageHandler> handler = new AtomicReference<>();

        @Override public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            sentMessages.add(message);
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
