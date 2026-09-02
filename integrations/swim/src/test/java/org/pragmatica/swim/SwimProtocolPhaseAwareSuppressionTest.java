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
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// Phase-aware SWIM cold-boot suppression — D.3 three-phase model (2026-05-11).
///
/// Verifies that the per-peer `everSeenHealthy` cold-boot gate is preserved while
/// the cluster is in `COLD_BOOT` and bypassed in `NORMAL` and `RECOVERING`.
///
/// Phase contract (controlled via `BooleanSupplier isBooting`):
///   - `true`  → COLD_BOOT semantics: never-Healthy peer → `UnknownObserved` on
///               SUSPECT/FAULTY edges.
///   - `false` → NORMAL or RECOVERING semantics: FAULTY edge ALWAYS emits
///               `FaultyObserved` regardless of `everSeenHealthy`. RECOVERING is
///               the critical compose-restart fix: peers were Healthy in the
///               prior NORMAL period, the post-restart kill must produce a
///               real cluster-visible observation.
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
        // joinGrace 0 — these phase tests assert the immediate (post-grace) NORMAL-phase
        // FAULTY behavior; the JOIN-GRACE window is exercised by graceConfig() below.
        return swimConfig(timeSpan(50).millis(),
                          timeSpan(20).millis(),
                          3,
                          timeSpan(150).millis(),
                          8,
                          timeSpan(50).millis()).withJoinGrace(timeSpan(0).millis());
    }

    /// Like [#tightConfig] but with a NORMAL-phase JOIN-GRACE window (600ms) LONGER than the
    /// suspect-window (150ms): a freshly-seeded OBSERVED joiner stays OBSERVED (no death timer)
    /// within grace and only escalates OBSERVED->SUSPECT->FAULTY after the join deadline, when the
    /// FAULTY edge emits FaultyObserved + tombstones (#336/#241). `lhmMaxScore=1` keeps the
    /// post-escalation suspect window at base so the FAULTY-edge timing is deterministic.
    private static SwimConfig graceConfig() {
        return swimConfig(timeSpan(50).millis(),
                          timeSpan(20).millis(),
                          3,
                          timeSpan(150).millis(),
                          8,
                          timeSpan(50).millis()).withJoinGrace(timeSpan(600).millis())
                                                .withLhmMaxScore(1);
    }

    /// Like [#graceConfig] but with a join-grace (80ms) SHORTER than the suspect-window
    /// (150ms), so the natural SUSPECT→FAULTY transition lands AFTER grace expiry. Used to
    /// observe the post-grace FaultyObserved emission on the natural failure-detection edge.
    private static SwimConfig shortGraceConfig() {
        return swimConfig(timeSpan(50).millis(),
                          timeSpan(20).millis(),
                          3,
                          timeSpan(150).millis(),
                          8,
                          timeSpan(50).millis()).withJoinGrace(timeSpan(80).millis());
    }

    @Nested
    class ColdBootPhase {
        @Test
        void coldBoot_neverHealthyPeer_suspectThenFaulty_emitsUnknownObserved() {
            // COLD_BOOT phase: legacy per-peer `everSeenHealthy` cold-boot suppression
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
                    .as("COLD_BOOT phase: never-HEALTHY peer must NOT emit FaultyObserved")
                    .isEmpty();
                assertThat(observations.byType(SwimObservation.UnknownObserved.class))
                    .as("COLD_BOOT phase: cold-boot suppression emits UnknownObserved instead")
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
    class RecoveringPhase {
        @Test
        void recovering_neverHealthyPeer_faulty_emitsFaultyObserved() {
            // RECOVERING phase has same wire behaviour as NORMAL — the phase supplier
            // returns `false`. SWIM does NOT consult the phase name; it consults only
            // the boolean. The supplier maps `phase != COLD_BOOT` → false. Verify that
            // an `isBooting=false` SwimProtocol emits FaultyObserved even for peers
            // never observed Healthy (the never-Healthy case is the strictest test;
            // peers in `everSeenHealthy` would always emit FaultyObserved regardless).
            var transport = new RecordingTransport();
            var listener = new RecordingListener();
            var observations = new RecordingObservationSink();
            var protocol = SwimProtocol.swimProtocol(tightConfig(),
                                                     transport,
                                                     listener,
                                                     SELF_ID,
                                                     SELF_ADDR,
                                                     () -> false) // RECOVERING (or NORMAL — both flip the gate off)
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
                    .as("RECOVERING phase: SWIM gate is OFF — FaultyObserved must emit even for never-Healthy peer")
                    .hasSize(1);
                assertThat(observations.byType(SwimObservation.UnknownObserved.class))
                    .as("RECOVERING phase: must NOT emit UnknownObserved")
                    .isEmpty();
                assertThat(observations.byType(SwimObservation.FaultyObserved.class)
                                       .getFirst()
                                       .peer()).isEqualTo(NODE_A);
            } finally {
                protocol.stop();
            }
        }

        @Test
        void recovering_previouslyHealthyPeer_faulty_emitsFaultyObserved() {
            // Compose-restart scenario: a peer was Healthy in the prior NORMAL period
            // (was in `everSeenHealthy`), now reappears as FAULTY after the cluster
            // re-establishes connectivity. Even if the gate were ON (e.g., transient
            // misconfiguration), `everSeenHealthy.contains(peer)` would still allow
            // the emission — but here the gate is also off because phase is RECOVERING.
            // Both conditions point to the same outcome: emit FaultyObserved.
            var transport = new RecordingTransport();
            var listener = new RecordingListener();
            var observations = new RecordingObservationSink();
            var protocol = SwimProtocol.swimProtocol(tightConfig(),
                                                     transport,
                                                     listener,
                                                     SELF_ID,
                                                     SELF_ADDR,
                                                     () -> false) // RECOVERING
                                       .unwrap();
            protocol.addObservationListener(observations);

            protocol.start();
            try {
                // Step 1: receive ALIVE gossip for NODE_A so it enters `everSeenHealthy`.
                var aliveA = new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A);
                protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(aliveA)));
                await().atMost(Duration.ofSeconds(2))
                       .until(() -> !observations.byType(SwimObservation.HealthyObserved.class).isEmpty());

                // Step 2: inject FAULTY for the now-known-Healthy peer. The second-hand
                // (gossip) FAULTY drives the death path only when locally corroborated
                // (P1 death-path co-confirmation) — record a transport-down hint first.
                protocol.recordTransportHint(NODE_A, new TransportObservation.PeerUnreachable(NODE_A, Causes.cause("test peer down")));
                var faultyA = new MembershipUpdate(NODE_A, MemberState.FAULTY, 0, ADDR_A);
                protocol.onMessage(ADDR_B, new Ping(NODE_B, 2L, List.of(faultyA)));

                await().atMost(Duration.ofSeconds(2))
                       .until(() -> !observations.byType(SwimObservation.FaultyObserved.class).isEmpty());

                assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                    .as("RECOVERING phase: previously-Healthy peer must emit FaultyObserved")
                    .hasSize(1);
                assertThat(observations.byType(SwimObservation.FaultyObserved.class)
                                       .getFirst()
                                       .peer()).isEqualTo(NODE_A);
            } finally {
                protocol.stop();
            }
        }

        @Test
        void phaseSwitchesColdBootToRecovering_subsequentFaultyEmits() {
            // Production wiring (D.3): phase starts COLD_BOOT (gate=true), reaches
            // NORMAL on first quorum, then drops to RECOVERING on quorum loss
            // (gate=false). Verify the SAME protocol instance honors the transition —
            // a SUSPECT injected during COLD_BOOT produces Unknown, then a fresh
            // FAULTY after the phase flip produces FaultyObserved.
            var phase = new AtomicBoolean(true); // COLD_BOOT
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

            // Step 1: COLD_BOOT — never-HEALTHY NODE_A goes SUSPECT, emits Unknown.
            var suspectA = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectA)));

            protocol.start();
            try {
                await().atMost(Duration.ofSeconds(3))
                       .until(() -> !observations.byType(SwimObservation.UnknownObserved.class).isEmpty());
                assertThat(observations.byType(SwimObservation.UnknownObserved.class)).hasSize(1);

                // Step 2: phase flips to RECOVERING. New FAULTY for NODE_B must emit
                // FaultyObserved even though NODE_B was never observed Healthy by this
                // SwimProtocol instance. The second-hand (gossip) FAULTY needs local
                // transport-down corroboration to drive the death path (P1).
                phase.set(false);
                protocol.recordTransportHint(NODE_B, new TransportObservation.PeerUnreachable(NODE_B, Causes.cause("test peer down")));
                var faultyB = new MembershipUpdate(NODE_B, MemberState.FAULTY, 0, ADDR_B);
                protocol.onMessage(ADDR_A, new Ping(NODE_A, 2L, List.of(faultyB)));

                await().atMost(Duration.ofSeconds(2))
                       .until(() -> !observations.byType(SwimObservation.FaultyObserved.class).isEmpty());
                assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                    .as("RECOVERING phase: FAULTY direct gossip must emit FaultyObserved")
                    .hasSize(1);
                assertThat(observations.byType(SwimObservation.FaultyObserved.class)
                                       .getFirst()
                                       .peer()).isEqualTo(NODE_B);
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
            // its first successful Ping still produces a `FaultyObserved` so the
            // membership FSM ingests the FAULTY edge, restoring the
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
            // Simulate the production wiring: phase starts COLD_BOOT, flips NORMAL once
            // the cluster's phase view projects it as steady. Verify the SAME protocol
            // instance honors both phases on subsequent FAULTY edges.
            var phase = new AtomicBoolean(true); // COLD_BOOT
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

            // Step 1: COLD_BOOT — never-HEALTHY NODE_A goes SUSPECT.
            var suspectA = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectA)));

            protocol.start();
            try {
                await().atMost(Duration.ofSeconds(3))
                       .until(() -> !observations.byType(SwimObservation.UnknownObserved.class).isEmpty());
                assertThat(observations.byType(SwimObservation.UnknownObserved.class)).hasSize(1);
                assertThat(observations.byType(SwimObservation.FaultyObserved.class)).isEmpty();

                // Step 2: phase flips NORMAL. Inject a FAULTY for NODE_B (also never-HEALTHY)
                // via direct gossip — must emit FaultyObserved. The second-hand FAULTY needs
                // local transport-down corroboration to drive the death path (P1).
                phase.set(false);
                protocol.recordTransportHint(NODE_B, new TransportObservation.PeerUnreachable(NODE_B, Causes.cause("test peer down")));
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

    @Nested
    class JoinGracePhase {
        @Test
        void normalPhase_observedJoiner_withinGrace_staysObserved_thenFaultyAndTombstonedAfterDeadline() throws InterruptedException {
            // A freshly-seeded NORMAL-phase joiner enters the LOCAL-ONLY OBSERVED birth state
            // (#336/#241): within its join-grace window an un-acked OBSERVED member is never armed
            // for death — it stays OBSERVED and probe-eligible so the leader's probe cycle can
            // confirm it. No FAULTY edge fires within grace, so nothing is emitted and nothing is
            // tombstoned. Once the join deadline passes, a probe-timeout escalates
            // OBSERVED->SUSPECT->FAULTY: the FAULTY edge lands PAST grace and so emits
            // FaultyObserved and tombstones — grace only DEFERS, never skips.
            var transport = new RecordingTransport();
            var listener = new RecordingListener();
            var observations = new RecordingObservationSink();
            var protocol = SwimProtocol.swimProtocol(graceConfig(),
                                                     transport,
                                                     listener,
                                                     SELF_ID,
                                                     SELF_ADDR,
                                                     () -> false) // NORMAL
                                       .unwrap();
            protocol.addObservationListener(observations);

            // Seed introduces NODE_A as OBSERVED; stamps the first-sighting that drives the deadline.
            protocol.addSeedMember(NODE_A, ADDR_A);
            assertThat(protocol.members().get(NODE_A).state()).isEqualTo(MemberState.OBSERVED);

            protocol.start();
            try {
                // WITHIN grace (600ms): probe timeouts never arm death — still OBSERVED, no emission, no tombstone.
                Thread.sleep(300L);
                assertThat(protocol.members().get(NODE_A).state())
                    .as("Within join-grace: an un-acked OBSERVED joiner stays OBSERVED")
                    .isEqualTo(MemberState.OBSERVED);
                assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                    .as("Within join-grace: no FAULTY edge, no FaultyObserved")
                    .isEmpty();
                assertThat(observations.byType(SwimObservation.UnknownObserved.class))
                    .as("Within join-grace: nothing is emitted at all")
                    .isEmpty();
                assertThat(protocol.tombstonedForTest(NODE_A))
                    .as("Within join-grace: an OBSERVED joiner must NOT be tombstoned")
                    .isFalse();

                // AFTER the join deadline: OBSERVED -> SUSPECT -> FAULTY in NORMAL phase => FaultyObserved + tombstone.
                await().atMost(Duration.ofSeconds(2))
                       .until(() -> !observations.byType(SwimObservation.FaultyObserved.class).isEmpty());

                assertThat(protocol.tombstonedForTest(NODE_A))
                    .as("After the join deadline: the never-HEALTHY FAULTY member IS tombstoned (deferred, not skipped)")
                    .isTrue();
                assertThat(observations.byType(SwimObservation.UnknownObserved.class))
                    .as("NORMAL phase, deadline passed: no UNKNOWN suppression on the FAULTY edge")
                    .isEmpty();
            } finally {
                protocol.stop();
            }
        }

        @Test
        void normalPhase_neverHealthyMember_afterGraceExpiry_emitsFaulty_andTombstoned() {
            // Join-grace (80ms) SHORTER than the suspect-window (150ms): a gossip-introduced
            // never-HEALTHY SUSPECT is born OBSERVED (#336/#241) and, once the short grace expires,
            // the probe cycle escalates it OBSERVED->SUSPECT->FAULTY; the FAULTY edge lands well past
            // grace, so FaultyObserved is emitted (no UNKNOWN suppression) and it is tombstoned. The
            // 06-02 anti-oscillation contract is preserved.
            var transport = new RecordingTransport();
            var listener = new RecordingListener();
            var observations = new RecordingObservationSink();
            var protocol = SwimProtocol.swimProtocol(shortGraceConfig(),
                                                     transport,
                                                     listener,
                                                     SELF_ID,
                                                     SELF_ADDR,
                                                     () -> false) // NORMAL
                                       .unwrap();
            protocol.addObservationListener(observations);

            // First sighting (stamps firstSeen) — a gossip SUSPECT of an unknown id is born OBSERVED,
            // never observed HEALTHY. Past the 80ms grace the probe cycle escalates it OBSERVED->
            // SUSPECT->FAULTY, the FAULTY edge landing past grace.
            var suspectUpdate = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectUpdate)));

            protocol.start();
            try {
                await().atMost(Duration.ofSeconds(3))
                       .until(() -> !observations.byType(SwimObservation.FaultyObserved.class).isEmpty()
                                    && protocol.tombstonedForTest(NODE_A));

                assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                    .as("After join-grace expiry: never-HEALTHY member MUST emit FaultyObserved")
                    .isNotEmpty();
                assertThat(observations.byType(SwimObservation.UnknownObserved.class))
                    .as("Grace shorter than suspect-window: FAULTY edge lands post-grace, no UNKNOWN")
                    .isEmpty();
                assertThat(protocol.tombstonedForTest(NODE_A))
                    .as("After join-grace expiry: never-HEALTHY FAULTY member MUST be tombstoned (06-02)")
                    .isTrue();
            } finally {
                protocol.stop();
            }
        }

        @Test
        void normalPhase_memberBecomesHealthyDuringGrace_isNotSuppressed_emitsHealthy() {
            // A replacement confirmed HEALTHY during its grace window exits grace-relevance
            // (everSeenHealthy short-circuits the suppression term): a later FAULTY emits
            // FaultyObserved immediately, not Unknown.
            var transport = new RecordingTransport();
            var listener = new RecordingListener();
            var observations = new RecordingObservationSink();
            var protocol = SwimProtocol.swimProtocol(graceConfig(),
                                                     transport,
                                                     listener,
                                                     SELF_ID,
                                                     SELF_ADDR,
                                                     () -> false) // NORMAL
                                       .unwrap();
            protocol.addObservationListener(observations);

            protocol.start();
            try {
                // ALIVE gossip during grace → everSeenHealthy + HealthyObserved.
                var aliveA = new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A);
                protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(aliveA)));
                await().atMost(Duration.ofSeconds(2))
                       .until(() -> !observations.byType(SwimObservation.HealthyObserved.class).isEmpty());
                assertThat(protocol.everSeenHealthyForTest(NODE_A)).isTrue();

                // FAULTY while STILL within grace: everSeenHealthy short-circuits suppression
                // → FaultyObserved, not Unknown. The second-hand (gossip) FAULTY needs local
                // transport-down corroboration to drive the death path (P1).
                protocol.recordTransportHint(NODE_A, new TransportObservation.PeerUnreachable(NODE_A, Causes.cause("test peer down")));
                var faultyA = new MembershipUpdate(NODE_A, MemberState.FAULTY, 1, ADDR_A);
                protocol.onMessage(ADDR_B, new Ping(NODE_B, 2L, List.of(faultyA)));
                await().atMost(Duration.ofSeconds(2))
                       .until(() -> !observations.byType(SwimObservation.FaultyObserved.class).isEmpty());

                assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                    .as("Member proven HEALTHY during grace is NOT suppressed on a later FAULTY")
                    .isNotEmpty();
            } finally {
                protocol.stop();
            }
        }

        @Test
        void zeroGrace_neverHealthyMember_emitsFaultyImmediately() {
            // Grace boundary — just OUTSIDE: joinGrace=0 disables the window entirely, so a
            // never-HEALTHY NORMAL-phase member emits FaultyObserved at once (identical to
            // the pre-grace behavior; proves grace=0 is a clean no-op).
            var transport = new RecordingTransport();
            var listener = new RecordingListener();
            var observations = new RecordingObservationSink();
            var protocol = SwimProtocol.swimProtocol(tightConfig(), // joinGrace 0
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
                    .as("joinGrace=0: never-HEALTHY NORMAL-phase member emits FaultyObserved immediately")
                    .hasSize(1);
                assertThat(observations.byType(SwimObservation.UnknownObserved.class))
                    .as("joinGrace=0: no UnknownObserved suppression")
                    .isEmpty();
            } finally {
                protocol.stop();
            }
        }

        @Test
        void coldBootPhase_withGraceConfigured_stillSuppresses_behaviorUnchanged() {
            // COLD_BOOT formation path is UNCHANGED by the join-grace addition: the booting
            // branch fires identically (it ORs with the grace term, so a configured grace
            // cannot make a booting never-HEALTHY peer emit FAULTY).
            var transport = new RecordingTransport();
            var listener = new RecordingListener();
            var observations = new RecordingObservationSink();
            var protocol = SwimProtocol.swimProtocol(graceConfig(),
                                                     transport,
                                                     listener,
                                                     SELF_ID,
                                                     SELF_ADDR,
                                                     () -> true) // COLD_BOOT
                                       .unwrap();
            protocol.addObservationListener(observations);

            var suspectUpdate = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectUpdate)));

            protocol.start();
            try {
                // Even well past the 250ms grace, COLD_BOOT keeps suppressing (booting=true).
                await().atMost(Duration.ofSeconds(2))
                       .until(() -> !observations.byType(SwimObservation.UnknownObserved.class).isEmpty());
                await().pollDelay(Duration.ofMillis(400))
                       .atMost(Duration.ofSeconds(1))
                       .until(() -> observations.byType(SwimObservation.FaultyObserved.class).isEmpty());

                assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                    .as("COLD_BOOT: never-HEALTHY peer must never emit FaultyObserved even past grace")
                    .isEmpty();
                assertThat(protocol.tombstonedForTest(NODE_A))
                    .as("COLD_BOOT: never-HEALTHY peer must NOT be tombstoned (formation safety)")
                    .isFalse();
            } finally {
                protocol.stop();
            }
        }
    }

    /// Transport-connectivity veto for the never-HEALTHY join-grace bypass (gate RCA fix,
    /// 2026-06-11). FAULTY for a never-healthy NORMAL-phase peer past its join grace requires
    /// co-confirmation: grace expired AND no live transport connection. While the
    /// `transportConnected` predicate reports a live link, the verdict is deferred
    /// (`UnknownObserved`) and RE-EVALUATED on every subsequent FAULTY edge — never latched.
    /// The shallower factories default the predicate to `id -> false` (never veto), keeping
    /// every pre-existing test green byte-identically.
    @Nested
    class TransportVeto {
        @Test
        void normalPhase_neverHealthyPastGrace_transportConnected_emitsUnknownNotFaulty() {
            var transport = new RecordingTransport();
            var listener = new RecordingListener();
            var observations = new RecordingObservationSink();
            var protocol = SwimProtocol.swimProtocol(tightConfig(), // joinGrace 0 — bypass reachable at once
                                                     transport,
                                                     listener,
                                                     SELF_ID,
                                                     SELF_ADDR,
                                                     () -> false, // NORMAL
                                                     peer -> true) // live transport — veto
                                       .unwrap();
            protocol.addObservationListener(observations);

            var suspectUpdate = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectUpdate)));

            protocol.start();
            try {
                await().atMost(Duration.ofSeconds(3))
                       .until(() -> !observations.byType(SwimObservation.UnknownObserved.class).isEmpty());

                assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                    .as("live transport vetoes the never-healthy FAULTY — UNKNOWN is emitted instead")
                    .isEmpty();
                assertThat(observations.byType(SwimObservation.DepartedObserved.class))
                    .as("a vetoed verdict must not emit the departure pair")
                    .isEmpty();
            } finally {
                protocol.stop();
            }
        }

        @Test
        void normalPhase_neverHealthyPastGrace_transportDisconnected_emitsFaultyAndDepartedPair() {
            var transport = new RecordingTransport();
            var listener = new RecordingListener();
            var observations = new RecordingObservationSink();
            var protocol = SwimProtocol.swimProtocol(tightConfig(), // joinGrace 0
                                                     transport,
                                                     listener,
                                                     SELF_ID,
                                                     SELF_ADDR,
                                                     () -> false, // NORMAL
                                                     peer -> false) // no live transport — co-confirmed ghost
                                       .unwrap();
            protocol.addObservationListener(observations);

            var suspectUpdate = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectUpdate)));

            protocol.start();
            try {
                await().atMost(Duration.ofSeconds(3))
                       .until(() -> !observations.byType(SwimObservation.FaultyObserved.class).isEmpty());

                assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                    .as("no live transport: the never-healthy ghost emits the FAULTY pair as today")
                    .hasSize(1);
                assertThat(observations.byType(SwimObservation.DepartedObserved.class)).hasSize(1);
                assertThat(observations.byType(SwimObservation.UnknownObserved.class))
                    .as("no veto, no suppression — no UNKNOWN")
                    .isEmpty();
            } finally {
                protocol.stop();
            }
        }

        @Test
        void verdictFollowsPredicateBetweenEdges_noLatch() {
            var connected = new AtomicBoolean(true);
            var transport = new RecordingTransport();
            var listener = new RecordingListener();
            var observations = new RecordingObservationSink();
            var protocol = SwimProtocol.swimProtocol(tightConfig(), // joinGrace 0
                                                     transport,
                                                     listener,
                                                     SELF_ID,
                                                     SELF_ADDR,
                                                     () -> false, // NORMAL
                                                     peer -> connected.get())
                                       .unwrap();
            protocol.addObservationListener(observations);

            // Edge 1 (connected): vetoed — UNKNOWN, no FAULTY.
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A))));
            protocol.start();
            try {
                await().atMost(Duration.ofSeconds(3))
                       .until(() -> !observations.byType(SwimObservation.UnknownObserved.class).isEmpty());
                assertThat(observations.byType(SwimObservation.FaultyObserved.class)).isEmpty();

                // Transport drops between edges; a higher-incarnation SUSPECT re-arms the
                // suspicion, whose expiry re-fires the FAULTY edge — the verdict is
                // re-evaluated against the CURRENT predicate value, not latched.
                connected.set(false);
                protocol.onMessage(ADDR_B, new Ping(NODE_B, 2L, List.of(new MembershipUpdate(NODE_A, MemberState.SUSPECT, 1, ADDR_A))));

                await().atMost(Duration.ofSeconds(3))
                       .until(() -> !observations.byType(SwimObservation.FaultyObserved.class).isEmpty());
                assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                    .as("predicate flip between edges flips the verdict — no latch")
                    .hasSize(1);
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
        @Override public void onMemberFaulty(SwimMember member, boolean firstHand) {}
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
