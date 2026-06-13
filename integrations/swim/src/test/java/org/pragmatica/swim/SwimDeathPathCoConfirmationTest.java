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
