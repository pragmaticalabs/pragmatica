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
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// Wave-6 SWIM internals regression tests (cluster-topology-overhaul spec):
///
/// 1. C4 — `applyAliveFromAck` promotes at the OBSERVED incarnation; a third party never
///    fabricates/advances another node's incarnation, and the gossiped promotion carries
///    the observed value.
/// 2. H8 — FAULTY residency: a member reaching FAULTY stays resident (refutable) for the
///    full `suspectTimeout × 3` window measured from the FAULTY stamp, then is swept.
///    (Wave-1 measured 2–4 ms residency pre-fix — the same-tick-sweep defeat.)
/// 3. Ack.from() — an Ack only counts as a probe-ack when its `from` matches the probed
///    target; unsolicited/mismatched acks are not alive-evidence.
/// 5. Lifeguard — Local Health Multiplier (score rises on local trouble, decays on
///    verified acks, stretches self-armed suspicion windows, saturates at the cap) and
///    the dogpile dynamic suspicion timeout (lone accuser keeps the full window, K
///    independent confirmers converge to base, confirmers deduped by NodeId), plus the
///    detection-SLO regression guard for genuine kills.
class SwimProtocolWave6Test {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final NodeId NODE_C = new NodeId("node-c");
    private static final NodeId NODE_D = new NodeId("node-d");
    private static final NodeId NODE_E = new NodeId("node-e");
    private static final NodeId NODE_F = new NodeId("node-f");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9100);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9101);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9102);
    private static final InetSocketAddress ADDR_C = new InetSocketAddress("127.0.0.1", 9103);
    private static final InetSocketAddress ADDR_D = new InetSocketAddress("127.0.0.1", 9104);
    private static final InetSocketAddress ADDR_E = new InetSocketAddress("127.0.0.1", 9105);
    private static final InetSocketAddress ADDR_F = new InetSocketAddress("127.0.0.1", 9106);

    private static final long BASE_SUSPECT_TIMEOUT_MS = 10_000L;

    /// Fully deterministic config: protocol is never started, probe timeouts (5s) never
    /// fire within a test, suspect window is the 10s base used for window arithmetic.
    private static SwimConfig slowConfig() {
        return swimConfig(timeSpan(1).seconds(),
                          timeSpan(5).seconds(),
                          3,
                          timeSpan(10).seconds(),
                          8,
                          timeSpan(10).seconds());
    }

    private RecordingTransport transport;
    private RecordingListener listener;
    private SwimProtocol protocol;

    @BeforeEach
    void setUp() {
        transport = new RecordingTransport();
        listener = new RecordingListener();
        protocol = SwimProtocol.swimProtocol(slowConfig(), transport, listener, SELF_ID, SELF_ADDR)
                               .unwrap();
    }

    // -- Shared drives --

    private void gossipFrom(NodeId sender, InetSocketAddress senderAddr, long seq, MembershipUpdate update) {
        protocol.onMessage(senderAddr, Ping.ping(sender, seq, List.of(update)));
    }

    private static MembershipUpdate updateOf(NodeId node, MemberState state, long incarnation) {
        return MembershipUpdate.membershipUpdate(node, state, incarnation, ADDR_A);
    }

    /// Raise the local LHM score deterministically: each remote suspicion of SELF is a
    /// "missed refutation traffic" increment in handleSelfUpdate.
    private void raiseLhmScore(int by) {
        for (var i = 0; i < by; i++) {
            gossipFrom(NODE_B, ADDR_B, 1_000L + i,
                       MembershipUpdate.membershipUpdate(SELF_ID, MemberState.SUSPECT, i, SELF_ADDR));
        }
    }

    private Ping lastPingTo(InetSocketAddress addr) {
        return transport.sentMessages.stream()
                                     .filter(m -> m.target().equals(addr) && m.message() instanceof Ping)
                                     .map(m -> (Ping) m.message())
                                     .reduce((first, second) -> second)
                                     .orElseThrow();
    }

    private long windowOf(NodeId peer) {
        return protocol.suspicionWindowForTest(peer).or(-1L);
    }

    /// Drive NODE_A: ALIVE at incarnation 5 → SUSPECT at incarnation 5 (same-incarnation
    /// state progression) → deterministic probe → genuine ack from NODE_A.
    private void driveSuspectThenVerifiedAck() {
        gossipFrom(NODE_B, ADDR_B, 1L, updateOf(NODE_A, MemberState.ALIVE, 5L));
        gossipFrom(NODE_B, ADDR_B, 2L, updateOf(NODE_A, MemberState.SUSPECT, 5L));
        assertThat(protocol.members().get(NODE_A).state()).isEqualTo(MemberState.SUSPECT);

        protocol.probeOnceForTest();
        var seq = lastPingTo(ADDR_A).sequence();
        protocol.onMessage(ADDR_A, Ack.ack(NODE_A, seq, List.of()));
    }

    @Nested
    class ObservedIncarnationOnAck {

        @Test
        void applyAliveFromAck_suspectMember_promotedAtObservedIncarnation() {
            driveSuspectThenVerifiedAck();

            var member = protocol.members().get(NODE_A);
            assertThat(member.state()).isEqualTo(MemberState.ALIVE);
            assertThat(member.incarnation())
                .as("a probe-ack proves liveness at the OBSERVED incarnation — no third-party +1 fabrication")
                .isEqualTo(5L);
        }

        @Test
        void applyAliveFromAck_promotion_gossipsNoFabricatedIncarnation() {
            driveSuspectThenVerifiedAck();

            // Pull the current piggyback through the real dissemination path: an inbound
            // Ping returns an Ack carrying the buffered updates.
            transport.sentMessages.clear();
            protocol.onMessage(ADDR_B, Ping.ping(NODE_B, 77L, List.of()));
            var piggyback = ((Ack) transport.sentMessages.getFirst().message()).piggyback();

            assertThat(piggyback)
                .as("no fabricated Alive(X, k+1) may be disseminated for a remote member")
                .noneMatch(u -> u.nodeId().equals(NODE_A) && u.incarnation() > 5L);
            assertThat(piggyback)
                .as("the promotion is disseminated at the observed incarnation")
                .anyMatch(u -> u.nodeId().equals(NODE_A)
                               && u.state() == MemberState.ALIVE
                               && u.incarnation() == 5L);
        }
    }

    @Nested
    class FaultyResidency {

        @Test
        void cleanupFaultyMembers_faultyMember_survivesSweepsUntilResidencyWindowElapses() throws InterruptedException {
            // Tight timings: suspectTimeout 100ms → designed residency 300ms; period 20ms
            // keeps sweep ticks frequent (pre-fix the member was swept on the next tick).
            var config = swimConfig(timeSpan(20).millis(),
                                    timeSpan(20).millis(),
                                    3,
                                    timeSpan(100).millis(),
                                    8,
                                    timeSpan(20).millis());
            var residencyProtocol = SwimProtocol.swimProtocol(config, transport, listener, SELF_ID, SELF_ADDR)
                                                .unwrap();
            residencyProtocol.start();
            try {
                residencyProtocol.onMessage(ADDR_B, Ping.ping(NODE_B, 1L, List.of(updateOf(NODE_A, MemberState.ALIVE, 0L))));
                var faultyEdgeAt = System.currentTimeMillis();
                // Second-hand (gossip) FAULTY needs local transport-down corroboration to
                // drive the FAULTY edge (P1 death-path co-confirmation).
                residencyProtocol.recordTransportHint(NODE_A, new TransportObservation.PeerUnreachable(NODE_A, Causes.cause("test peer down")));
                residencyProtocol.onMessage(ADDR_B, Ping.ping(NODE_B, 2L, List.of(updateOf(NODE_A, MemberState.FAULTY, 1L))));
                assertThat(residencyProtocol.members().get(NODE_A).state()).isEqualTo(MemberState.FAULTY);

                Thread.sleep(100L);
                assertThat(residencyProtocol.members())
                    .as("FAULTY member must stay resident (refutable) mid-window — pre-fix it was swept within ms")
                    .containsKey(NODE_A);

                await().atMost(Duration.ofSeconds(2))
                       .until(() -> !residencyProtocol.members().containsKey(NODE_A));
                assertThat(System.currentTimeMillis() - faultyEdgeAt)
                    .as("swept only after the full suspectTimeout×3 residency from the FAULTY stamp")
                    .isGreaterThanOrEqualTo(300L);
            } finally {
                residencyProtocol.stop();
            }
        }
    }

    @Nested
    class AckFromCheck {

        @Test
        void processAckProbe_mismatchedFrom_ignoredAsAliveEvidence_probeStillPending() {
            protocol.addSeedMember(NODE_A, ADDR_A);
            protocol.probeOnceForTest();
            var seq = lastPingTo(ADDR_A).sequence();

            protocol.onMessage(ADDR_A, Ack.ack(NODE_B, seq, List.of()));
            assertThat(protocol.members().get(NODE_A).state())
                .as("an ack whose from does not match the probed target is not alive-evidence — stays OBSERVED")
                .isEqualTo(MemberState.OBSERVED);

            // The mismatched ack must not consume the pending probe: the genuine ack still verifies.
            protocol.onMessage(ADDR_A, Ack.ack(NODE_A, seq, List.of()));
            assertThat(protocol.members().get(NODE_A).state()).isEqualTo(MemberState.ALIVE);
        }

        @Test
        void handleAck_unsolicitedAck_noLongerPromotesSender() {
            protocol.addSeedMember(NODE_A, ADDR_A);

            protocol.onMessage(ADDR_A, Ack.ack(NODE_A, 9_999L, List.of()));

            assertThat(protocol.members().get(NODE_A).state())
                .as("an Ack matching no pending probe is not alive-evidence — stays OBSERVED (pre-fix it promoted unconditionally)")
                .isEqualTo(MemberState.OBSERVED);
        }
    }

    @Nested
    class LocalHealthMultiplier {

        @Test
        void lhmScore_risesOnProbeCycleTimeout() {
            // probeTimeout 20ms so the direct+indirect cycle fails fast; protocol not
            // started (no ticks) — only the scheduled probe timeouts fire.
            var config = swimConfig(timeSpan(1).seconds(),
                                    timeSpan(20).millis(),
                                    3,
                                    timeSpan(10).seconds(),
                                    8,
                                    timeSpan(10).seconds());
            var timingProtocol = SwimProtocol.swimProtocol(config, transport, listener, SELF_ID, SELF_ADDR)
                                             .unwrap();
            timingProtocol.addSeedMember(NODE_A, ADDR_A);
            timingProtocol.probeOnceForTest();

            await().atMost(Duration.ofSeconds(3))
                   .until(() -> timingProtocol.lhmScoreForTest() >= 1);
            assertThat(timingProtocol.lhmScoreForTest())
                .as("a full probe cycle with no ack is local trouble — the LHM score rises")
                .isGreaterThanOrEqualTo(1);
        }

        @Test
        void lhmScore_decaysOnVerifiedProbeAck() {
            raiseLhmScore(2);
            assertThat(protocol.lhmScoreForTest()).isEqualTo(2);

            gossipFrom(NODE_B, ADDR_B, 5L, updateOf(NODE_A, MemberState.ALIVE, 1L));
            protocol.probeOnceForTest();
            var seq = lastPingTo(ADDR_A).sequence();
            protocol.onMessage(ADDR_A, Ack.ack(NODE_A, seq, List.of()));

            assertThat(protocol.lhmScoreForTest())
                .as("each verified probe-ack is a healthy round — the LHM score decays by 1")
                .isEqualTo(1);
        }

        @Test
        void handleSelfUpdate_remoteSelfSuspicion_raisesLhmScore() {
            raiseLhmScore(1);

            assertThat(protocol.lhmScoreForTest())
                .as("a remote suspicion of self means refutation traffic was too slow — local trouble")
                .isEqualTo(1);
        }

        @Test
        void suspicionWindow_scalesWithLhmScore() {
            raiseLhmScore(3);
            gossipFrom(NODE_B, ADDR_B, 5L, updateOf(NODE_A, MemberState.ALIVE, 1L));
            gossipFrom(NODE_B, ADDR_B, 6L, updateOf(NODE_A, MemberState.SUSPECT, 1L));

            assertThat(windowOf(NODE_A))
                .as("suspicion armed at score 3 stretches the window by ×(score+1)")
                .isEqualTo(BASE_SUSPECT_TIMEOUT_MS * 4);
        }

        @Test
        void lhmScore_capHonoured_windowStretchSaturatesAtMaxMultiplier() {
            raiseLhmScore(20);
            assertThat(protocol.lhmScoreForTest())
                .as("score saturates at lhmMaxScore")
                .isEqualTo(SwimConfig.DEFAULT_LHM_MAX_SCORE);

            gossipFrom(NODE_B, ADDR_B, 50L, updateOf(NODE_A, MemberState.ALIVE, 1L));
            gossipFrom(NODE_B, ADDR_B, 51L, updateOf(NODE_A, MemberState.SUSPECT, 1L));

            assertThat(windowOf(NODE_A))
                .as("window stretch saturates at ×lhmMaxScore (×8): min(score+1, cap)")
                .isEqualTo(BASE_SUSPECT_TIMEOUT_MS * SwimConfig.DEFAULT_LHM_MAX_SCORE);
        }
    }

    /// Stale-suspicion gate (live-gate defect): the LHM "missed refutation traffic"
    /// increment and the incarnation bump fire ONCE per genuine suspicion event —
    /// replayed Suspect(self, k) gossip already superseded by our refutation
    /// (k < current self-incarnation) is ignored entirely. Pre-gate, a healthy idle
    /// node's LHM climbed to the cap at ~1 increment per replayed receipt.
    @Nested
    class StaleSelfSuspicionGate {

        private MembershipUpdate selfSuspectAt(long incarnation) {
            return MembershipUpdate.membershipUpdate(SELF_ID, MemberState.SUSPECT, incarnation, SELF_ADDR);
        }

        @Test
        void handleSelfUpdate_staleSuspicionReplays_moveNeitherLhmScoreNorIncarnation() {
            // Genuine suspicion at k=5: refuted once (incarnation > 5), LHM +1.
            gossipFrom(NODE_B, ADDR_B, 1L, selfSuspectAt(5L));
            var incarnationAfterRefutation = protocol.selfIncarnation();
            assertThat(incarnationAfterRefutation).isGreaterThan(5L);
            assertThat(protocol.lhmScoreForTest()).isEqualTo(1);

            // Stale replays at k=5 (< refuted incarnation): ignored entirely.
            for (var i = 0; i < 5; i++) {
                gossipFrom(NODE_B, ADDR_B, 10L + i, selfSuspectAt(5L));
            }

            assertThat(protocol.selfIncarnation())
                .as("stale Suspect(self, k) replays must NOT re-bump the incarnation (gossip-churn feed)")
                .isEqualTo(incarnationAfterRefutation);
            assertThat(protocol.lhmScoreForTest())
                .as("stale replays must NOT increment the LHM score (one increment per genuine event)")
                .isEqualTo(1);
        }

        @Test
        void handleSelfUpdate_newSuspicionAtCurrentIncarnation_incrementsLhmOnceAndBumpsOnce() {
            gossipFrom(NODE_B, ADDR_B, 1L, selfSuspectAt(5L));
            var current = protocol.selfIncarnation();
            var scoreBefore = protocol.lhmScoreForTest();

            // A genuinely NEW suspicion at k' == current self-incarnation challenges the
            // current liveness claim: exactly one refutation bump + one LHM increment.
            gossipFrom(NODE_B, ADDR_B, 2L, selfSuspectAt(current));

            assertThat(protocol.selfIncarnation())
                .as("a new suspicion at the current incarnation bumps once (max(cur, incoming) + 1)")
                .isEqualTo(current + 1);
            assertThat(protocol.lhmScoreForTest())
                .as("a new suspicion increments the LHM exactly once")
                .isEqualTo(scoreBefore + 1);
        }
    }

    @Nested
    class Dogpile {

        /// LHM score 3 → multiplier ×4 → max window 40s, min (base) 10s. Members A–D
        /// tracked at suspicion start → K = min(3, 4 - 1) = 3.
        @BeforeEach
        void armStretchedSuspicion() {
            raiseLhmScore(3);
            gossipFrom(NODE_B, ADDR_B, 5L, updateOf(NODE_A, MemberState.ALIVE, 1L));
            gossipFrom(NODE_B, ADDR_B, 6L, updateOf(NODE_B, MemberState.ALIVE, 1L));
            gossipFrom(NODE_B, ADDR_B, 7L, updateOf(NODE_C, MemberState.ALIVE, 1L));
            gossipFrom(NODE_B, ADDR_B, 8L, updateOf(NODE_D, MemberState.ALIVE, 1L));
            gossipFrom(NODE_B, ADDR_B, 9L, updateOf(NODE_A, MemberState.SUSPECT, 1L));
        }

        private void confirmFrom(NodeId accuser, InetSocketAddress accuserAddr, long seq) {
            gossipFrom(accuser, accuserAddr, seq, updateOf(NODE_A, MemberState.SUSPECT, 1L));
        }

        @Test
        void dogpile_loneAccuser_keepsFullLhmStretchedWindow() {
            assertThat(windowOf(NODE_A))
                .as("a lone accuser keeps the full LHM-stretched window — the suspect gets the whole period to refute")
                .isEqualTo(BASE_SUSPECT_TIMEOUT_MS * 4);
        }

        @Test
        void dogpile_independentConfirmers_shrinkWindowTowardBase() {
            var lone = windowOf(NODE_A);

            confirmFrom(NODE_C, ADDR_C, 11L);
            var afterOne = windowOf(NODE_A);
            confirmFrom(NODE_D, ADDR_D, 12L);
            var afterTwo = windowOf(NODE_A);
            confirmFrom(NODE_E, ADDR_E, 13L);
            var afterThree = windowOf(NODE_A);

            assertThat(afterOne).isLessThan(lone);
            assertThat(afterTwo).isLessThan(afterOne);
            assertThat(afterThree)
                .as("C = K independent confirmations converge the window exactly to the base timeout")
                .isEqualTo(BASE_SUSPECT_TIMEOUT_MS);

            confirmFrom(NODE_F, ADDR_F, 14L);
            assertThat(windowOf(NODE_A))
                .as("over-confirmation clamps at the base timeout — never below")
                .isEqualTo(BASE_SUSPECT_TIMEOUT_MS);
        }

        @Test
        void dogpile_duplicateConfirmer_dedupedByNodeId() {
            confirmFrom(NODE_C, ADDR_C, 21L);
            var afterFirst = windowOf(NODE_A);

            confirmFrom(NODE_C, ADDR_C, 22L);
            confirmFrom(NODE_C, ADDR_C, 23L);

            assertThat(windowOf(NODE_A))
                .as("repeat confirmations by the same accuser are deduped by NodeId")
                .isEqualTo(afterFirst);
        }
    }

    /// Death broadcast at the FAULTY edge (live-gate follow-up): FAULTY IS confirmed
    /// death — the (LHM/dogpile-scaled) suspicion window was the refutation time, so
    /// `DepartedObserved` follows `FaultyObserved` immediately at the edge. The H8
    /// residency sweep is map-hygiene only and emits nothing; the member stays
    /// resident (refutable) until residency expiry exactly as before.
    @Nested
    class FaultyEdgeDeparture {

        @Test
        void faultyEdge_emitsFaultyThenDeparted_wellBeforeResidencyExpiry() {
            // slowConfig: suspectTimeout 10s => residency 30s; protocol never started,
            // so the pair below is emitted synchronously at the edge — nowhere near
            // any sweep.
            var observations = new RecordingObservationSink();
            protocol.addObservationListener(observations);

            gossipFrom(NODE_B, ADDR_B, 1L, updateOf(NODE_A, MemberState.ALIVE, 0L));
            // Second-hand (gossip) FAULTY needs local transport-down corroboration to drive
            // the FAULTY edge (P1 death-path co-confirmation).
            protocol.recordTransportHint(NODE_A, new TransportObservation.PeerUnreachable(NODE_A, Causes.cause("test peer down")));
            gossipFrom(NODE_B, ADDR_B, 2L, updateOf(NODE_A, MemberState.FAULTY, 1L));

            assertThat(observations.byType(SwimObservation.FaultyObserved.class)).hasSize(1);
            assertThat(observations.byType(SwimObservation.DepartedObserved.class))
                .as("DepartedObserved fires at the FAULTY edge, not at the residency sweep")
                .hasSize(1);
            assertThat(indexOfFirst(observations, SwimObservation.FaultyObserved.class))
                .as("FaultyObserved precedes DepartedObserved")
                .isLessThan(indexOfFirst(observations, SwimObservation.DepartedObserved.class));
            assertThat(protocol.members())
                .as("only the broadcast moved to the edge — the member stays resident (refutable)")
                .containsKey(NODE_A);

            // Once-per-edge dedup: a FAULTY re-entry (higher-incarnation gossip) emits
            // neither observation again.
            gossipFrom(NODE_B, ADDR_B, 3L, updateOf(NODE_A, MemberState.FAULTY, 2L));
            assertThat(observations.byType(SwimObservation.FaultyObserved.class)).hasSize(1);
            assertThat(observations.byType(SwimObservation.DepartedObserved.class)).hasSize(1);
        }

        @Test
        void residencySweep_emitsNoObservation_andRetainsMemberUntilExpiry() throws InterruptedException {
            // suspectTimeout 100ms => residency 300ms; period 20ms keeps sweep ticks frequent.
            var config = swimConfig(timeSpan(20).millis(),
                                    timeSpan(20).millis(),
                                    3,
                                    timeSpan(100).millis(),
                                    8,
                                    timeSpan(20).millis());
            var sweepProtocol = SwimProtocol.swimProtocol(config, transport, listener, SELF_ID, SELF_ADDR)
                                            .unwrap();
            var observations = new RecordingObservationSink();
            sweepProtocol.addObservationListener(observations);
            sweepProtocol.start();
            try {
                sweepProtocol.onMessage(ADDR_B, Ping.ping(NODE_B, 1L, List.of(updateOf(NODE_A, MemberState.ALIVE, 0L))));
                // Second-hand (gossip) FAULTY needs local transport-down corroboration to
                // drive the FAULTY edge (P1 death-path co-confirmation).
                sweepProtocol.recordTransportHint(NODE_A, new TransportObservation.PeerUnreachable(NODE_A, Causes.cause("test peer down")));
                sweepProtocol.onMessage(ADDR_B, Ping.ping(NODE_B, 2L, List.of(updateOf(NODE_A, MemberState.FAULTY, 1L))));
                assertThat(observations.byType(SwimObservation.DepartedObserved.class))
                    .as("the death broadcast already fired at the FAULTY edge")
                    .hasSize(1);
                var countAfterEdgePair = observations.all.size();

                Thread.sleep(100L);
                assertThat(sweepProtocol.members())
                    .as("H8 retention unchanged: member resident mid-residency-window")
                    .containsKey(NODE_A);

                await().atMost(Duration.ofSeconds(2))
                       .until(() -> !sweepProtocol.members().containsKey(NODE_A));
                assertThat(observations.all)
                    .as("the residency sweep is map-hygiene only — it emits NO observation")
                    .hasSize(countAfterEdgePair);
            } finally {
                sweepProtocol.stop();
            }
        }

        @Test
        void observedMember_noProbeAckBeyondSuspectTimeout_notFaulty() throws InterruptedException {
            // OBSERVED birth (#336/#241): a freshly-seeded member that never acks is NOT armed for
            // death within join-grace. Even after far more than a base suspect-timeout of probe
            // timeouts, it stays OBSERVED (never SUSPECT, never FAULTY) — a slow joiner is never
            // prematurely killed.
            var config = swimConfig(timeSpan(20).millis(),
                                    timeSpan(20).millis(),
                                    3,
                                    timeSpan(100).millis(),
                                    8,
                                    timeSpan(20).millis()).withJoinGrace(timeSpan(2).seconds());
            var graced = SwimProtocol.swimProtocol(config, transport, listener, SELF_ID, SELF_ADDR, () -> false)
                                     .unwrap();
            var observations = new RecordingObservationSink();
            graced.addObservationListener(observations);

            graced.addSeedMember(NODE_A, ADDR_A);
            graced.start();
            try {
                // Sleep well past the suspect-timeout (100ms) but far inside join-grace (2s).
                Thread.sleep(500L);

                assertThat(graced.members().get(NODE_A).state())
                    .as("an un-acked OBSERVED member stays OBSERVED within join-grace — never SUSPECT/FAULTY")
                    .isEqualTo(MemberState.OBSERVED);
                assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                    .as("no FAULTY edge for an un-acked OBSERVED member within grace")
                    .isEmpty();
                assertThat(listener.faulty)
                    .as("no FAULTY listener callback within grace")
                    .isEmpty();
            } finally {
                graced.stop();
            }
        }

        @Test
        void observedMember_sustainedProbeTimeoutPastJoinGrace_escalatesToSuspectThenFaulty() throws InterruptedException {
            // NORMAL phase, join-grace 300ms > suspect-window 100ms: an OBSERVED member that never
            // acks stays OBSERVED within grace, then PAST the join deadline a probe-timeout escalates
            // it OBSERVED->SUSPECT, and the suspect-window expiry drives SUSPECT->FAULTY, firing the
            // Faulty+Departed pair (#336/#241). lhmMaxScore=1 keeps the suspect window at base so the
            // post-deadline timing is deterministic.
            var config = swimConfig(timeSpan(20).millis(),
                                    timeSpan(20).millis(),
                                    3,
                                    timeSpan(100).millis(),
                                    8,
                                    timeSpan(20).millis()).withJoinGrace(timeSpan(300).millis())
                                                          .withLhmMaxScore(1);
            var graced = SwimProtocol.swimProtocol(config, transport, listener, SELF_ID, SELF_ADDR, () -> false)
                                     .unwrap();
            var observations = new RecordingObservationSink();
            graced.addObservationListener(observations);

            graced.addSeedMember(NODE_A, ADDR_A);
            assertThat(graced.members().get(NODE_A).state()).isEqualTo(MemberState.OBSERVED);

            graced.start();
            try {
                // WITHIN grace (300ms): probe timeouts never arm death — still OBSERVED, nothing emitted.
                Thread.sleep(150L);
                assertThat(graced.members().get(NODE_A).state())
                    .as("within join-grace a probe-timeout leaves an OBSERVED member OBSERVED")
                    .isEqualTo(MemberState.OBSERVED);
                assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                    .as("no death broadcast within grace")
                    .isEmpty();

                // PAST the join deadline: OBSERVED -> SUSPECT -> FAULTY; the Faulty+Departed pair fires.
                await().atMost(Duration.ofSeconds(2))
                       .until(() -> !observations.byType(SwimObservation.DepartedObserved.class).isEmpty());
                assertThat(observations.byType(SwimObservation.FaultyObserved.class)).hasSize(1);
                assertThat(observations.byType(SwimObservation.DepartedObserved.class)).hasSize(1);
                assertThat(listener.suspected)
                    .as("escalation passed through SUSPECT before FAULTY")
                    .anyMatch(m -> m.nodeId().equals(NODE_A));
                assertThat(listener.faulty)
                    .as("the member reached FAULTY")
                    .anyMatch(m -> m.nodeId().equals(NODE_A));
            } finally {
                graced.stop();
            }
        }

        private int indexOfFirst(RecordingObservationSink observations, Class<? extends SwimObservation> type) {
            for (var i = 0; i < observations.all.size(); i++) {
                if (type.isInstance(observations.all.get(i))) {
                    return i;
                }
            }
            return -1;
        }
    }

    @Nested
    class DetectionSloRegression {

        @Test
        void deadNode_withConfirmers_declaredFaultyWithinBaseDetectionBudget() {
            // Healthy self (LHM 0) → suspicion armed at the BASE window: Lifeguard must
            // NOT delay detection of a genuine kill (no refutation will ever arrive).
            var config = swimConfig(timeSpan(20).millis(),
                                    timeSpan(20).millis(),
                                    3,
                                    timeSpan(300).millis(),
                                    8,
                                    timeSpan(20).millis());
            var sloProtocol = SwimProtocol.swimProtocol(config, transport, listener, SELF_ID, SELF_ADDR)
                                          .unwrap();

            sloProtocol.onMessage(ADDR_B, Ping.ping(NODE_B, 1L, List.of(updateOf(NODE_A, MemberState.ALIVE, 1L))));
            var suspicionStart = System.currentTimeMillis();
            sloProtocol.onMessage(ADDR_B, Ping.ping(NODE_B, 2L, List.of(updateOf(NODE_A, MemberState.SUSPECT, 1L))));
            sloProtocol.onMessage(ADDR_C, Ping.ping(NODE_C, 3L, List.of(updateOf(NODE_A, MemberState.SUSPECT, 1L))));
            sloProtocol.onMessage(ADDR_D, Ping.ping(NODE_D, 4L, List.of(updateOf(NODE_A, MemberState.SUSPECT, 1L))));

            sloProtocol.start();
            try {
                await().atMost(Duration.ofSeconds(2))
                       .until(() -> !listener.faulty.isEmpty());

                assertThat(listener.faulty.getFirst().nodeId()).isEqualTo(NODE_A);
                assertThat(System.currentTimeMillis() - suspicionStart)
                    .as("genuine kill detected within the base budget — Lifeguard must not regress the SLO")
                    .isLessThan(1_500L);
            } finally {
                sloProtocol.stop();
            }
        }
    }

    // -- Test infrastructure --

    record SentMessage(InetSocketAddress target, SwimMessage message) {}

    static class RecordingTransport implements SwimTransport {
        final CopyOnWriteArrayList<SentMessage> sentMessages = new CopyOnWriteArrayList<>();

        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            sentMessages.add(new SentMessage(target, message));
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> start(int port, SwimMessageHandler handler) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }
    }

    static class RecordingListener implements SwimMembershipListener {
        final CopyOnWriteArrayList<SwimMember> joined = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<SwimMember> suspected = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<SwimMember> faulty = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<NodeId> left = new CopyOnWriteArrayList<>();

        @Override
        public void onMemberJoined(SwimMember member) {
            joined.add(member);
        }

        @Override
        public void onMemberSuspect(SwimMember member) {
            suspected.add(member);
        }

        @Override
        public void onMemberFaulty(SwimMember member, boolean firstHand) {
            faulty.add(member);
        }

        @Override
        public void onMemberLeft(NodeId nodeId) {
            left.add(nodeId);
        }
    }

    static class RecordingObservationSink implements Consumer<SwimObservation> {
        final CopyOnWriteArrayList<SwimObservation> all = new CopyOnWriteArrayList<>();

        @Override
        public void accept(SwimObservation observation) {
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
