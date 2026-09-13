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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
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
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// P1 (death-path co-confirmation for gossiped FAULTY) and P2 (isolation-era verdict expiry on
/// rejoin) — the SWIM-protocol halves of the "SWIM verdict → irreversible FSM death" fix.
///
/// Root (S06 partition-heal collapse): a rejoining isolated minority gossiped its isolation-era
/// FAULTY verdicts into the majority; `SwimProtocol` second-hand FAULTY drove the cluster death
/// path (FaultyObserved + DepartedObserved) terminalizing live peers in ~1ms.
class SwimDeathPathCoConfirmationTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final NodeId NODE_C = new NodeId("node-c");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9000);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9001);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9002);
    private static final InetSocketAddress ADDR_C = new InetSocketAddress("127.0.0.1", 9003);

    @Nested
    class SecondHandFaultyProvenance {
        private RecordingTransport transport;
        private RecordingListener listener;
        private RecordingObservationSink observations;
        private final java.util.Set<NodeId> liveTransport = new java.util.concurrent.CopyOnWriteArraySet<>();
        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            transport = new RecordingTransport();
            listener = new RecordingListener();
            observations = new RecordingObservationSink();
            liveTransport.clear();
            // NORMAL phase (isBooting=false) so cold-boot suppression never masks the FAULTY edge.
            // transportConnected reflects this node's LIVE QUIC links (the P1 contradiction predicate).
            protocol = SwimProtocol.swimProtocol(swimConfig(), transport, listener, SELF_ID, SELF_ADDR,
                                                 () -> false, liveTransport::contains)
                                   .unwrap();
            protocol.addObservationListener(observations);
        }

        @Test
        void gossipedFaulty_contradictedByLiveTransport_staysSuspect_noDeathPath() {
            // Establish NODE_A as a known, ever-HEALTHY member, and assert a LIVE local QUIC link to it.
            seenHealthy(NODE_A, ADDR_A);
            liveTransport.add(NODE_A); // gossip will say dead, but my own link is up — the S06 poison case

            var faulty = new MembershipUpdate(NODE_A, MemberState.FAULTY, 1, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 2L, List.of(faulty)));

            assertThat(protocol.members().get(NODE_A).state())
                .as("Live-transport-contradicted second-hand FAULTY must downgrade to SUSPECT (counted, refutable)")
                .isEqualTo(MemberState.SUSPECT);
            assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                .as("No death-driving FaultyObserved for a contradicted second-hand verdict")
                .isEmpty();
            assertThat(observations.byType(SwimObservation.DepartedObserved.class))
                .as("No death-driving DepartedObserved for a contradicted second-hand verdict")
                .isEmpty();
            assertThat(observations.byType(SwimObservation.SuspectObserved.class))
                .as("Verdict surfaces as SUSPECT (refutable suspicion)")
                .isNotEmpty();
            assertThat(listener.faultyCalls)
                .as("onMemberFaulty (the membership death path) must NOT be invoked")
                .isEmpty();
        }

        @Test
        void gossipedFaulty_contradictedByLiveTransport_triggersReProbe() {
            seenHealthy(NODE_A, ADDR_A);
            liveTransport.add(NODE_A);
            transport.sentMessages.clear();

            var faulty = new MembershipUpdate(NODE_A, MemberState.FAULTY, 1, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 2L, List.of(faulty)));

            assertThat(transport.sentMessages.stream()
                                             .anyMatch(sent -> sent.target().equals(ADDR_A)
                                                               && sent.message() instanceof Ping))
                .as("A contradicted second-hand FAULTY must trigger an immediate local re-probe of the peer")
                .isTrue();
        }

        @Test
        void gossipedFaulty_noLiveTransport_drivesDeathPath() {
            // Known ever-HEALTHY member, but NO live local link now (docker-killed victim, or hint
            // not yet landed): absent evidence is NOT a contradiction → accept the gossiped FAULTY.
            seenHealthy(NODE_A, ADDR_A);
            // liveTransport does NOT contain NODE_A.

            var faulty = new MembershipUpdate(NODE_A, MemberState.FAULTY, 1, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 2L, List.of(faulty)));

            assertThat(protocol.members().get(NODE_A).state())
                .as("Gossiped FAULTY with NO live local link drives the death path: member is FAULTY")
                .isEqualTo(MemberState.FAULTY);
            assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                .as("Accept-on-absent: FaultyObserved emitted (auto-heal regression case)")
                .hasSize(1);
            assertThat(observations.byType(SwimObservation.DepartedObserved.class))
                .as("Accept-on-absent: the death-driving DepartedObserved emitted")
                .hasSize(1);
            assertThat(listener.faultyCalls)
                .as("onMemberFaulty invoked, flagged second-hand")
                .containsExactly(new FaultyCall(NODE_A, false));
        }

        @Test
        void newMemberGossipedFaulty_noLiveTransport_drivesDeathPath() {
            // First sighting of NODE_C is a FAULTY gossip (applyNewFaultyMember path), NO live link
            // (unknown peer) → accepted, canonical SWIM dissemination preserved. joinGrace=0 so the
            // never-HEALTHY new member's FAULTY edge is not grace-suppressed to UnknownObserved.
            var graceTransport = new RecordingTransport();
            var graceListener = new RecordingListener();
            var graceObservations = new RecordingObservationSink();
            var config = swimConfig().withJoinGrace(timeSpan(0).millis());
            var graceProtocol = SwimProtocol.swimProtocol(config, graceTransport, graceListener, SELF_ID, SELF_ADDR,
                                                          () -> false, liveTransport::contains)
                                            .unwrap();
            graceProtocol.addObservationListener(graceObservations);

            var faulty = new MembershipUpdate(NODE_C, MemberState.FAULTY, 1, ADDR_C);
            graceProtocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(faulty)));

            assertThat(graceProtocol.members().get(NODE_C).state())
                .as("Unknown peer first-seen as FAULTY gossip with no live link is accepted (FAULTY)")
                .isEqualTo(MemberState.FAULTY);
            assertThat(graceObservations.byType(SwimObservation.FaultyObserved.class)).hasSize(1);
            assertThat(graceListener.faultyCalls).containsExactly(new FaultyCall(NODE_C, false));
        }

        @Test
        void newMemberGossipedFaulty_contradictedByLiveTransport_staysSuspect_noDeathPath() {
            // First sighting of NODE_C is a FAULTY gossip but this node holds a live link to it.
            liveTransport.add(NODE_C);
            var faulty = new MembershipUpdate(NODE_C, MemberState.FAULTY, 1, ADDR_C);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(faulty)));

            assertThat(protocol.members().get(NODE_C).state())
                .as("New member first-seen as contradicted FAULTY gossip must be held SUSPECT")
                .isEqualTo(MemberState.SUSPECT);
            assertThat(observations.byType(SwimObservation.FaultyObserved.class)).isEmpty();
            assertThat(observations.byType(SwimObservation.DepartedObserved.class)).isEmpty();
            assertThat(listener.faultyCalls).isEmpty();
        }

        @Test
        void firstHandFaulty_drivesDeathPath_unchanged() {
            // Tight config so the local suspect-window expiry runs inside the test budget.
            var tightTransport = new RecordingTransport();
            var tightListener = new RecordingListener();
            var tightObservations = new RecordingObservationSink();
            var config = swimConfig(timeSpan(40).millis(),
                                    timeSpan(20).millis(),
                                    3,
                                    timeSpan(60).millis(),
                                    8,
                                    timeSpan(40).millis()).withJoinGrace(timeSpan(0).millis());
            var tightProtocol = SwimProtocol.swimProtocol(config, tightTransport, tightListener, SELF_ID, SELF_ADDR, () -> false)
                                            .unwrap();
            tightProtocol.addObservationListener(tightObservations);

            // NODE_A ever-HEALTHY, then SUSPECT — THIS node's own suspect-window expiry → first-hand FAULTY.
            tightProtocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A))));
            tightProtocol.onMessage(ADDR_B, new Ping(NODE_B, 2L, List.of(new MembershipUpdate(NODE_A, MemberState.SUSPECT, 1, ADDR_A))));

            tightProtocol.start();
            try {
                await().atMost(Duration.ofSeconds(10))
                       .until(() -> !tightObservations.byType(SwimObservation.FaultyObserved.class).isEmpty());
            } finally {
                tightProtocol.stop();
            }

            assertThat(tightObservations.byType(SwimObservation.FaultyObserved.class))
                .as("First-hand FAULTY (own suspect-window expiry) emits FaultyObserved — unchanged")
                .isNotEmpty();
            assertThat(tightListener.faultyCalls)
                .as("First-hand FAULTY invokes onMemberFaulty flagged firstHand=true")
                .contains(new FaultyCall(NODE_A, true));
        }

        private void seenHealthy(NodeId nodeId, InetSocketAddress addr) {
            var alive = new MembershipUpdate(nodeId, MemberState.ALIVE, 0, addr);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(alive)));
        }
    }

    /// #1061 — origin-aware transport death hints. A QUIC eviction's `LINK_LOST` hint describes the
    /// link that was lost. Once the transport has reconnected, it must neither floor the suspect
    /// window to 3s nor act as the #336 kill-gate corroboration for a lone first-hand FAULTY. A
    /// `PEER_UNRESPONSIVE` hint (ClusterSync missed pongs: connected but silent) keeps both with the
    /// link CONNECTED. `PeerReachable` never moves a peer toward ALIVE. Every test drives the real
    /// entry point (`recordTransportHint`) and, where timing matters, the real tick loop; no hint
    /// state is injected.
    @Nested
    class OriginAwareTransportHints {
        private static final long SUSPECT_TIMEOUT_MS = 5_000L;
        private static final long FLOOR_MS = 3_000L;
        private static final String SWIM_LOGGER = "org.pragmatica.swim.SwimProtocol";

        private final Set<NodeId> liveTransport = new CopyOnWriteArraySet<>();
        // Suspect window (5s) clearly above the 3s floor so the two are distinguishable in time;
        // fast ticks so expiry lands within ~40ms of the window; joinGrace 0.
        private final SwimConfig config = swimConfig(timeSpan(40).millis(),
                                                     timeSpan(20).millis(),
                                                     3,
                                                     timeSpan(SUSPECT_TIMEOUT_MS).millis(),
                                                     8,
                                                     timeSpan(40).millis()).withJoinGrace(timeSpan(0).millis());
        private RecordingTransport transport;
        private RecordingListener listener;
        private RecordingObservationSink observations;
        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            transport = new RecordingTransport();
            listener = new RecordingListener();
            observations = new RecordingObservationSink();
            liveTransport.clear();
            // NORMAL phase; transportConnected mirrors this node's live QUIC links, as production wires it.
            protocol = SwimProtocol.swimProtocol(config, transport, listener, SELF_ID, SELF_ADDR,
                                                 () -> false, liveTransport::contains)
                                   .unwrap();
            protocol.addObservationListener(observations);
        }

        @Test
        void linkLostHint_thenLinkReconnected_loneFirstHandFaultyHeldAtDefaultWindow() {
            seenHealthy(protocol, NODE_A, ADDR_A);
            // Eviction: link down, QUIC onPeerLeft -> LINK_LOST hint initiates SUSPECT under the floor.
            protocol.recordTransportHint(NODE_A, linkLost(NODE_A));
            assertThat(stateOf(NODE_A)).isEqualTo(MemberState.SUSPECT);
            assertThat(windowOf(NODE_A))
                .as("While the evicted link is down the LINK_LOST hint floors the window")
                .isEqualTo(FLOOR_MS);

            // Re-dial completes: the link is CONNECTED again and QUIC onPeerReconnected -> PeerReachable.
            liveTransport.add(NODE_A);
            protocol.recordTransportHint(NODE_A, new TransportObservation.PeerReachable(NODE_A));
            assertThat(windowOf(NODE_A))
                .as("After the reconnect the suspicion runs on the default window, not the 3s floor")
                .isEqualTo(SUSPECT_TIMEOUT_MS);

            protocol.start();
            try {
                await().during(Duration.ofMillis(3_800))
                       .atMost(Duration.ofSeconds(5))
                       .until(() -> !listener.faultyCalls.contains(new FaultyCall(NODE_A, true)));
                await().atMost(Duration.ofSeconds(10))
                       .until(() -> listener.faultyCalls.contains(new FaultyCall(NODE_A, true)));
            } finally {
                protocol.stop();
            }

            assertThat(forPeer(SwimObservation.DepartedObserved.class, NODE_A))
                .as("A reconnected link's LINK_LOST hint must not corroborate a lone first-hand FAULTY — no DepartedObserved")
                .isEmpty();
            assertThat(forPeer(SwimObservation.FaultyObserved.class, NODE_A))
                .as("The under-confirmed first-hand verdict is held by the #336 kill-gate")
                .isEmpty();
            assertThat(forPeer(SwimObservation.UnknownObserved.class, NODE_A))
                .as("The held verdict surfaces as UNKNOWN")
                .isNotEmpty();
        }

        @Test
        void linkLostHint_linkConnectedBeforeReachableEvent_floorAndVetoWithheld() {
            seenHealthy(protocol, NODE_A, ADDR_A);
            protocol.recordTransportHint(NODE_A, linkLost(NODE_A));
            assertThat(windowOf(NODE_A)).isEqualTo(FLOOR_MS);

            // Out-of-order delivery: the new link is already CONNECTED but PeerReachable has not been
            // delivered, so the hint is still recorded. It must be judged against the live link.
            liveTransport.add(NODE_A);
            assertThat(windowOf(NODE_A))
                .as("A recorded LINK_LOST hint does not floor the window while the link is CONNECTED")
                .isEqualTo(SUSPECT_TIMEOUT_MS);

            protocol.start();
            try {
                await().during(Duration.ofMillis(3_800))
                       .atMost(Duration.ofSeconds(5))
                       .until(() -> !listener.faultyCalls.contains(new FaultyCall(NODE_A, true)));
                await().atMost(Duration.ofSeconds(10))
                       .until(() -> listener.faultyCalls.contains(new FaultyCall(NODE_A, true)));
            } finally {
                protocol.stop();
            }

            assertThat(forPeer(SwimObservation.DepartedObserved.class, NODE_A))
                .as("A recorded LINK_LOST hint does not veto the kill-gate while the link is CONNECTED")
                .isEmpty();
            assertThat(forPeer(SwimObservation.UnknownObserved.class, NODE_A)).isNotEmpty();
        }

        @Test
        void linkLostHint_linkStillDown_floorsAndVetoes() {
            seenHealthy(protocol, NODE_A, ADDR_A);
            protocol.recordTransportHint(NODE_A, linkLost(NODE_A));
            var hintedAt = System.currentTimeMillis();
            assertThat(windowOf(NODE_A)).isEqualTo(FLOOR_MS);

            protocol.start();
            try {
                await().atMost(Duration.ofSeconds(5))
                       .until(() -> !forPeer(SwimObservation.FaultyObserved.class, NODE_A).isEmpty());
            } finally {
                protocol.stop();
            }

            assertThat(System.currentTimeMillis() - hintedAt)
                .as("A link that is still down keeps the 3s floor — real transport-confirmed death is not slowed")
                .isLessThan(SUSPECT_TIMEOUT_MS);
            assertThat(forPeer(SwimObservation.DepartedObserved.class, NODE_A))
                .as("A current LINK_LOST hint still corroborates the lone first-hand FAULTY")
                .hasSize(1);
        }

        @Test
        void peerUnresponsiveHint_linkConnected_floorsAndVetoesThroughReachable() {
            seenHealthy(protocol, NODE_A, ADDR_A);
            // Hung peer: the QUIC link stays CONNECTED throughout, but the peer misses pongs.
            liveTransport.add(NODE_A);
            protocol.recordTransportHint(NODE_A, peerUnresponsive(NODE_A));
            var hintedAt = System.currentTimeMillis();
            assertThat(stateOf(NODE_A)).isEqualTo(MemberState.SUSPECT);
            // A reachable event (catch-up or reconnect) says nothing about a connected-but-silent peer.
            protocol.recordTransportHint(NODE_A, new TransportObservation.PeerReachable(NODE_A));
            assertThat(windowOf(NODE_A))
                .as("A PEER_UNRESPONSIVE hint floors the window with the link CONNECTED and survives PeerReachable")
                .isEqualTo(FLOOR_MS);

            protocol.start();
            try {
                await().atMost(Duration.ofSeconds(5))
                       .until(() -> !forPeer(SwimObservation.FaultyObserved.class, NODE_A).isEmpty());
            } finally {
                protocol.stop();
            }

            assertThat(System.currentTimeMillis() - hintedAt).isLessThan(SUSPECT_TIMEOUT_MS);
            assertThat(forPeer(SwimObservation.DepartedObserved.class, NODE_A))
                .as("A PEER_UNRESPONSIVE hint still corroborates the lone first-hand FAULTY with the link CONNECTED")
                .hasSize(1);
        }

        @Test
        void bothOriginsRecorded_thenLinkReconnected_retractsOnlyLinkLost() {
            seenHealthy(protocol, NODE_A, ADDR_A);
            // A hung peer is reported by missed pongs AND its stalled link is evicted: both origins land.
            protocol.recordTransportHint(NODE_A, peerUnresponsive(NODE_A));
            protocol.recordTransportHint(NODE_A, linkLost(NODE_A));
            assertThat(windowOf(NODE_A)).isEqualTo(FLOOR_MS);

            // The re-dial succeeds: that disproves the link loss, not the missed pongs.
            liveTransport.add(NODE_A);
            protocol.recordTransportHint(NODE_A, new TransportObservation.PeerReachable(NODE_A));

            assertThat(windowOf(NODE_A))
                .as("A later LINK_LOST hint must not overwrite a PEER_UNRESPONSIVE one, and its retraction must keep it")
                .isEqualTo(FLOOR_MS);
        }

        @Test
        void linkLostHint_reachableWithoutTransportView_retractsFloorAndReappliesOnNextLoss() {
            // No transport view (id -> false): only the PeerReachable retraction can lift the floor.
            var noViewProtocol = SwimProtocol.swimProtocol(config, transport, listener, SELF_ID, SELF_ADDR, () -> false)
                                             .unwrap();
            seenHealthy(noViewProtocol, NODE_A, ADDR_A);
            noViewProtocol.recordTransportHint(NODE_A, linkLost(NODE_A));
            assertThat(noViewProtocol.effectiveSuspicionWindowForTest(NODE_A).or(-1L)).isEqualTo(FLOOR_MS);

            noViewProtocol.recordTransportHint(NODE_A, new TransportObservation.PeerReachable(NODE_A));
            assertThat(noViewProtocol.effectiveSuspicionWindowForTest(NODE_A).or(-1L))
                .as("PeerReachable retracts the transport's own LINK_LOST hint")
                .isEqualTo(SUSPECT_TIMEOUT_MS);

            noViewProtocol.recordTransportHint(NODE_A, linkLost(NODE_A));
            assertThat(noViewProtocol.effectiveSuspicionWindowForTest(NODE_A).or(-1L))
                .as("The retraction is not a latch: the next link loss floors the window again")
                .isEqualTo(FLOOR_MS);
        }

        @Test
        void peerReachable_neverPromotesToAlive_onlyProbeAckDoes() {
            seenHealthy(protocol, NODE_A, ADDR_A);
            protocol.recordTransportHint(NODE_A, linkLost(NODE_A));
            var suspectedAt = protocol.suspectTimestampForTest(NODE_A).or(-1L);
            var healthyEdgesBefore = forPeer(SwimObservation.HealthyObserved.class, NODE_A).size();

            liveTransport.add(NODE_A);
            protocol.recordTransportHint(NODE_A, new TransportObservation.PeerReachable(NODE_A));
            protocol.recordTransportHint(NODE_A, new TransportObservation.PeerReachable(NODE_A));

            assertThat(stateOf(NODE_A))
                .as("PeerReachable retracts link evidence; it never moves the peer toward ALIVE")
                .isEqualTo(MemberState.SUSPECT);
            assertThat(protocol.suspectTimestampForTest(NODE_A).or(-2L))
                .as("The suspicion clock is untouched by PeerReachable")
                .isEqualTo(suspectedAt);
            assertThat(forPeer(SwimObservation.HealthyObserved.class, NODE_A))
                .as("No HEALTHY edge from a transport event")
                .hasSize(healthyEdgesBefore);

            protocol.start();
            try {
                await().atMost(Duration.ofSeconds(4))
                       .until(() -> isAliveAfterAckingLatestProbe(NODE_A, ADDR_A));
            } finally {
                protocol.stop();
            }

            assertThat(forPeer(SwimObservation.HealthyObserved.class, NODE_A))
                .as("A verified probe-ack is what returns the peer to ALIVE")
                .hasSize(healthyEdgesBefore + 1);
        }

        @Test
        void suspicionJournal_logsEnforcedWindow_atStartAndOnRetraction() {
            var context = (LoggerContext) LogManager.getContext(false);
            var configuration = context.getConfiguration();
            var loggerConfig = new LoggerConfig(SWIM_LOGGER, Level.INFO, true);
            var appender = new CapturingAppender("Swim1061WindowCapture");

            appender.start();
            loggerConfig.addAppender(appender, Level.INFO, null);
            configuration.addLogger(SWIM_LOGGER, loggerConfig);
            context.updateLoggers();
            try {
                seenHealthy(protocol, NODE_A, ADDR_A);
                protocol.recordTransportHint(NODE_A, linkLost(NODE_A));

                assertThat(appender.messages)
                    .as("The suspicion-start line prints the window the expiry check enforces (3s floor), not the dogpile window")
                    .anyMatch(line -> line.contains("suspect node-a accused by node-self; effective window " + FLOOR_MS + "ms"));

                liveTransport.add(NODE_A);
                protocol.recordTransportHint(NODE_A, new TransportObservation.PeerReachable(NODE_A));
                var enforced = windowOf(NODE_A);

                assertThat(enforced).isEqualTo(SUSPECT_TIMEOUT_MS);
                assertThat(appender.messages)
                    .as("The retraction line prints the window now enforced")
                    .anyMatch(line -> line.contains("LINK_LOST death hint retracted")
                                      && line.contains("effective suspect window now " + enforced + "ms"));
            } finally {
                configuration.removeLogger(SWIM_LOGGER);
                context.updateLoggers();
                appender.stop();
            }
        }

        private boolean isAliveAfterAckingLatestProbe(NodeId peer, InetSocketAddress addr) {
            transport.sentMessages.stream()
                                  .filter(sent -> sent.target().equals(addr) && sent.message() instanceof Ping)
                                  .map(sent -> ((Ping) sent.message()).sequence())
                                  .reduce((first, second) -> second)
                                  .ifPresent(seq -> protocol.onMessage(addr, new Ack(peer, seq, List.of())));
            return stateOf(peer) == MemberState.ALIVE;
        }

        private void seenHealthy(SwimProtocol target, NodeId nodeId, InetSocketAddress addr) {
            target.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(new MembershipUpdate(nodeId, MemberState.ALIVE, 0, addr))));
        }

        private TransportObservation.PeerUnreachable linkLost(NodeId peer) {
            return new TransportObservation.PeerUnreachable(peer,
                                                            Causes.cause("QUIC link evicted"),
                                                            TransportObservation.HintOrigin.LINK_LOST);
        }

        private TransportObservation.PeerUnreachable peerUnresponsive(NodeId peer) {
            return new TransportObservation.PeerUnreachable(peer,
                                                            Causes.cause("cluster-sync missed pongs"),
                                                            TransportObservation.HintOrigin.PEER_UNRESPONSIVE);
        }

        private MemberState stateOf(NodeId peer) {
            return protocol.members().get(peer).state();
        }

        private long windowOf(NodeId peer) {
            return protocol.effectiveSuspicionWindowForTest(peer).or(-1L);
        }

        private <T extends SwimObservation> List<T> forPeer(Class<T> type, NodeId peer) {
            return observations.byType(type)
                               .stream()
                               .filter(observation -> observation.peer().equals(peer))
                               .toList();
        }
    }

    @Nested
    class IsolationEraVerdictExpiry {
        @Test
        void allPeersFaulty_thenReconnect_expiresIsolationEraFaultyBacklog() {
            var transport = new RecordingTransport();
            var listener = new RecordingListener();
            // Tight config so the suspect window expires inside the test budget.
            var config = swimConfig(timeSpan(40).millis(),
                                    timeSpan(20).millis(),
                                    3,
                                    timeSpan(60).millis(),
                                    8,
                                    timeSpan(40).millis()).withJoinGrace(timeSpan(0).millis());
            var protocol = SwimProtocol.swimProtocol(config, transport, listener, SELF_ID, SELF_ADDR, () -> false)
                                       .unwrap();

            // Two peers, both ever-HEALTHY (so their FAULTY is not cold-boot-suppressed).
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L,
                                                List.of(new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A),
                                                        new MembershipUpdate(NODE_B, MemberState.ALIVE, 0, ADDR_B))));

            protocol.start();
            try {
                // Isolation: every peer's own probe cycle times out → first-hand FAULTY for ALL peers.
                // First-hand FAULTY (transitionToFaulty) buffers the verdict for dissemination.
                await().atMost(Duration.ofSeconds(10))
                       .until(() -> protocol.members().values().stream()
                                            .allMatch(m -> m.state() == MemberState.FAULTY));

                assertThat(protocol.selfIsolatedForTest())
                    .as("All-peers-FAULTY latches the self-isolation signature")
                    .isTrue();
                assertThat(protocol.piggybackFaultyCountForTest())
                    .as("Isolation-era FAULTY verdicts accumulate in the dissemination backlog")
                    .isGreaterThan(0);

                // Rejoin evidence: NODE_A returns and re-announces ALIVE at a strictly-higher
                // incarnation (supersedes the FAULTY tombstone) — a peer is reachable again, so
                // this node is no longer isolated. Routes through recordHealthyAndEmit.
                protocol.onMessage(ADDR_A, new Ping(NODE_A, 99L,
                                                    List.of(new MembershipUpdate(NODE_A, MemberState.ALIVE, 5, ADDR_A))));

                assertThat(protocol.selfIsolatedForTest())
                    .as("Reconnection evidence clears the isolation latch")
                    .isFalse();
                assertThat(protocol.piggybackFaultyCountForTest())
                    .as("On rejoin the isolation-era FAULTY backlog is expired — not gossiped into the cluster")
                    .isZero();
            } finally {
                protocol.stop();
            }
        }

        @Test
        void singlePeerFaulty_notIsolation_backlogNotExpiredOnReconnect() {
            var transport = new RecordingTransport();
            var listener = new RecordingListener();
            // Tight suspect window for the ONE peer we drive FAULTY; the other is kept alive by acks.
            var config = swimConfig(timeSpan(40).millis(),
                                    timeSpan(20).millis(),
                                    3,
                                    timeSpan(60).millis(),
                                    8,
                                    timeSpan(40).millis()).withJoinGrace(timeSpan(0).millis());
            var protocol = SwimProtocol.swimProtocol(config, transport, listener, SELF_ID, SELF_ADDR, () -> false)
                                       .unwrap();

            // Two known, ever-HEALTHY peers.
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L,
                                                List.of(new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A),
                                                        new MembershipUpdate(NODE_B, MemberState.ALIVE, 0, ADDR_B))));

            protocol.start();
            try {
                // Keep NODE_B alive across the window by answering its probes, while NODE_A goes FAULTY.
                await().atMost(Duration.ofSeconds(10))
                       .until(() -> {
                           keepAlive(transport, protocol, NODE_B);
                           return protocol.members().get(NODE_A).state() == MemberState.FAULTY;
                       });

                assertThat(protocol.members().get(NODE_B).state())
                    .as("NODE_B stays alive — not all peers faulty, so this is NOT isolation")
                    .isNotEqualTo(MemberState.FAULTY);
                assertThat(protocol.selfIsolatedForTest())
                    .as("A single-peer FAULTY (other peer alive) must NOT latch self-isolation")
                    .isFalse();
                assertThat(protocol.piggybackFaultyCountForTest())
                    .as("The first-hand FAULTY for NODE_A is buffered for normal dissemination")
                    .isGreaterThan(0);

                // Reconnection evidence from NODE_B: isolation was never latched, so the FAULTY
                // dissemination must be retained (normal, non-isolation dissemination is unchanged).
                deliverVerifiedAckFrom(transport, protocol, NODE_B);

                assertThat(protocol.piggybackFaultyCountForTest())
                    .as("Normal (non-isolation) FAULTY dissemination must NOT be expired")
                    .isGreaterThan(0);
            } finally {
                protocol.stop();
            }
        }

        @Test
        void expireFaultyUpdates_dropsOnlyFaulty_retainsAliveAndSuspect() {
            var buffer = PiggybackBuffer.piggybackBuffer(8);
            buffer.addUpdate(new MembershipUpdate(NODE_A, MemberState.FAULTY, 1, ADDR_A));
            buffer.addUpdate(new MembershipUpdate(NODE_B, MemberState.ALIVE, 2, ADDR_B));
            buffer.addUpdate(new MembershipUpdate(NODE_C, MemberState.SUSPECT, 1, ADDR_C));

            var dropped = buffer.expireFaultyUpdates();

            assertThat(dropped).as("Exactly one FAULTY entry dropped").isEqualTo(1);
            assertThat(buffer.faultyCount()).as("No FAULTY entries remain").isZero();
            assertThat(buffer.size()).as("ALIVE and SUSPECT entries retained").isEqualTo(2);
        }

        /// Answer NODE_B's outstanding probe so it stays ALIVE across the window.
        private void keepAlive(RecordingTransport transport, SwimProtocol protocol, NodeId peer) {
            var seq = pendingSeqFor(transport, peer);

            if (seq >= 0) {
                protocol.onMessage(addrOf(peer), new Ack(peer, seq, List.of()));
            }
        }

        /// Synthesize a verified probe-ack for `peer`: locate the pending probe SEQ the protocol sent
        /// to that peer and feed back a matching Ack so `acceptProbeAckIfFromTarget` accepts it as
        /// alive-evidence (the P2 reconnection seam).
        private void deliverVerifiedAckFrom(RecordingTransport transport, SwimProtocol protocol, NodeId peer) {
            await().atMost(Duration.ofSeconds(10))
                   .until(() -> pendingSeqFor(transport, peer) >= 0);
            var seq = pendingSeqFor(transport, peer);
            protocol.onMessage(addrOf(peer), new Ack(peer, seq, List.of()));
        }

        private long pendingSeqFor(RecordingTransport transport, NodeId peer) {
            return transport.sentMessages.stream()
                                         .filter(sent -> sent.target().equals(addrOf(peer))
                                                         && sent.message() instanceof Ping)
                                         .map(sent -> ((Ping) sent.message()).sequence())
                                         .reduce((first, second) -> second)
                                         .orElse(-1L);
        }

        private InetSocketAddress addrOf(NodeId peer) {
            if (peer.equals(NODE_A)) {
                return ADDR_A;
            }
            if (peer.equals(NODE_B)) {
                return ADDR_B;
            }
            return ADDR_C;
        }
    }

    // -- Test infrastructure --

    record SentMessage(InetSocketAddress target, SwimMessage message) {}

    record FaultyCall(NodeId nodeId, boolean firstHand) {}

    /// In-memory log4j2 appender capturing formatted messages for journal-line assertions.
    static final class CapturingAppender extends AbstractAppender {
        final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();

        CapturingAppender(String name) {
            super(name, (Filter) null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        }

        @Override public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }
    }

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
        final CopyOnWriteArrayList<FaultyCall> faultyCalls = new CopyOnWriteArrayList<>();

        @Override public void onMemberJoined(SwimMember member) {}
        @Override public void onMemberSuspect(SwimMember member) {}
        @Override public void onMemberFaulty(SwimMember member, boolean firstHand) {
            faultyCalls.add(new FaultyCall(member.nodeId(), firstHand));
        }
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
