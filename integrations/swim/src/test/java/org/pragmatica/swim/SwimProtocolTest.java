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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.Announce;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimMessage.PingReq;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

class SwimProtocolTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final NodeId NODE_C = new NodeId("node-c");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9000);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9001);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9002);
    private static final InetSocketAddress ADDR_C = new InetSocketAddress("127.0.0.1", 9003);

    @Nested
    class MembershipTests {
        private RecordingTransport transport;
        private RecordingListener listener;
        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            transport = new RecordingTransport();
            listener = new RecordingListener();
            protocol = SwimProtocol.swimProtocol(swimConfig(), transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(cause -> null, v -> v);
        }

        @Test
        void addSeedMember_newMember_addedAsSuspect() {
            // Resurrection guard (#231): a channel re-seed is NOT proof of reachability,
            // so a seeded member is introduced as SUSPECT (probe-on-arrival), not ALIVE.
            // A real probe-ack or QUIC PeerConnected later promotes it to ALIVE.
            protocol.addSeedMember(NODE_A, ADDR_A);

            assertThat(protocol.members()).containsKey(NODE_A);
            assertThat(protocol.members().get(NODE_A).state()).isEqualTo(MemberState.SUSPECT);
            assertThat(listener.joined).hasSize(1);
            assertThat(listener.joined.getFirst().nodeId()).isEqualTo(NODE_A);
        }

        @Test
        void addSeedMember_selfNode_ignored() {
            protocol.addSeedMember(SELF_ID, SELF_ADDR);

            assertThat(protocol.members()).isEmpty();
            assertThat(listener.joined).isEmpty();
        }

        @Test
        void members_multipleSeeds_allPresent() {
            protocol.addSeedMember(NODE_A, ADDR_A);
            protocol.addSeedMember(NODE_B, ADDR_B);
            protocol.addSeedMember(NODE_C, ADDR_C);

            assertThat(protocol.members()).hasSize(3);
        }
    }

    @Nested
    class MessageHandlingTests {
        private RecordingTransport transport;
        private RecordingListener listener;
        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            transport = new RecordingTransport();
            listener = new RecordingListener();
            protocol = SwimProtocol.swimProtocol(swimConfig(), transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(cause -> null, v -> v);
        }

        @Test
        void onMessage_ping_sendsAck() {
            var ping = new Ping(NODE_A, 1L, List.of());

            protocol.onMessage(ADDR_A, ping);

            assertThat(transport.sentMessages).hasSize(1);
            assertThat(transport.sentMessages.getFirst().message()).isInstanceOf(Ack.class);

            var ack = (Ack) transport.sentMessages.getFirst().message();
            assertThat(ack.from()).isEqualTo(SELF_ID);
            assertThat(ack.sequence()).isEqualTo(1L);
        }

        @Test
        void onMessage_pingWithPiggyback_processesUpdates() {
            var update = new MembershipUpdate(NODE_B, MemberState.ALIVE, 0, ADDR_B);
            var ping = new Ping(NODE_A, 1L, List.of(update));

            protocol.onMessage(ADDR_A, ping);

            assertThat(protocol.members()).containsKey(NODE_B);
            assertThat(listener.joined).hasSize(1);
        }

        @Test
        void onMessage_pingReqForKnownTarget_forwardsPing() {
            protocol.addSeedMember(NODE_B, ADDR_B);
            transport.sentMessages.clear();

            var pingReq = new PingReq(NODE_A, NODE_B, 42L);

            protocol.onMessage(ADDR_A, pingReq);

            assertThat(transport.sentMessages).hasSize(1);

            var forwarded = transport.sentMessages.getFirst();
            assertThat(forwarded.target()).isEqualTo(ADDR_B);
            assertThat(forwarded.message()).isInstanceOf(Ping.class);
        }

        @Test
        void onMessage_pingReqForUnknownTarget_ignored() {
            var pingReq = new PingReq(NODE_A, NODE_B, 42L);

            protocol.onMessage(ADDR_A, pingReq);

            assertThat(transport.sentMessages).isEmpty();
        }

        @Test
        void onMessage_pingReqRelay_ackForwardedBackToRequester() {
            // Setup: self knows both NODE_A (requester) and NODE_B (target)
            protocol.addSeedMember(NODE_A, ADDR_A);
            protocol.addSeedMember(NODE_B, ADDR_B);
            transport.sentMessages.clear();

            // NODE_A asks self to probe NODE_B (indirect probe)
            var pingReq = new PingReq(NODE_A, NODE_B, 42L);
            protocol.onMessage(ADDR_A, pingReq);

            // Self should send Ping to NODE_B with its OWN sequence (not 42)
            assertThat(transport.sentMessages).hasSize(1);
            assertThat(transport.sentMessages.getFirst().target()).isEqualTo(ADDR_B);
            var relayPing = (Ping) transport.sentMessages.getFirst().message();
            var relaySeq = relayPing.sequence();
            assertThat(relaySeq).isNotEqualTo(42L); // relay uses its own sequence

            transport.sentMessages.clear();

            // NODE_B responds with Ack using the RELAY sequence
            var ack = Ack.ack(NODE_B, relaySeq, List.of());
            protocol.onMessage(ADDR_B, ack);

            // Self should forward Ack back to NODE_A with the ORIGINAL sequence (42)
            assertThat(transport.sentMessages).hasSize(1);
            var forwarded = transport.sentMessages.getFirst();
            assertThat(forwarded.target()).isEqualTo(ADDR_A);
            assertThat(forwarded.message()).isInstanceOf(Ack.class);
            assertThat(((Ack) forwarded.message()).from()).isEqualTo(NODE_B);
            assertThat(((Ack) forwarded.message()).sequence()).isEqualTo(42L); // original sequence restored
        }
    }

    /// Self-ANNOUNCE = direct liveness evidence (canonical SWIM). A node announcing
    /// ITSELF is positive proof it is alive, so an unknown member learned from a
    /// self-ANNOUNCE datagram is introduced as ALIVE (no suspect-timer armed at birth),
    /// reaching HEALTHY immediately. Third-party gossip is UNCHANGED (still SUSPECT), and
    /// a tombstoned (proven-dead) id is still refused (#231 anti-resurrection).
    @Nested
    class AnnounceDirectLiveness {
        private RecordingTransport transport;
        private RecordingListener listener;
        private SwimProtocol protocol;
        private RecordingObservationSink observations;

        @BeforeEach
        void setUp() {
            transport = new RecordingTransport();
            listener = new RecordingListener();
            protocol = SwimProtocol.swimProtocol(swimConfig(), transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(cause -> null, v -> v);
            observations = new RecordingObservationSink();
            protocol.addObservationListener(observations);
        }

        @Test
        void handleAnnounce_nonTombstonedSelfAnnounce_introducedAsAlive() {
            protocol.onMessage(ADDR_A, Announce.announce(nodeInfoFor(NODE_A, ADDR_A), "", 0L));

            assertThat(protocol.members()).containsKey(NODE_A);
            assertThat(protocol.members().get(NODE_A).state())
                .as("self-ANNOUNCE is direct liveness evidence — introduce as ALIVE, not SUSPECT")
                .isEqualTo(MemberState.ALIVE);
        }

        @Test
        void handleAnnounce_nonTombstonedSelfAnnounce_setsEverSeenHealthyAndEmitsHealthy() {
            protocol.onMessage(ADDR_A, Announce.announce(nodeInfoFor(NODE_A, ADDR_A), "", 0L));

            assertThat(protocol.everSeenHealthyForTest(NODE_A))
                .as("direct liveness evidence marks the peer ever-healthy")
                .isTrue();
            assertThat(observations.byType(SwimObservation.HealthyObserved.class))
                .as("ALIVE introduction must emit HealthyObserved")
                .isNotEmpty();
        }

        @Test
        void handleAnnounce_nonTombstonedSelfAnnounce_armsNoSuspectTimer() {
            protocol.onMessage(ADDR_A, Announce.announce(nodeInfoFor(NODE_A, ADDR_A), "", 0L));

            assertThat(protocol.suspectTimestampForTest(NODE_A).isEmpty())
                .as("ALIVE introduction must NOT arm a decay-to-FAULTY suspect timer at birth")
                .isTrue();
        }

        @Test
        void handleAnnounce_unknownMember_stillDeliversJoinAnnounced() {
            // The legitimate reachability probe (clusterNetwork.connect) is driven by
            // JoinAnnounced — formation must remain unaffected.
            protocol.onMessage(ADDR_A, Announce.announce(nodeInfoFor(NODE_A, ADDR_A), "", 0L));

            assertThat(observations.byType(SwimObservation.JoinAnnounced.class))
                .as("JoinAnnounced must still fire so the reachability probe proceeds")
                .hasSize(1);
        }

        @Test
        void handleAnnounce_thirdPartyGossipOfUnknownMember_stillIntroducedAsSuspect() {
            // ONLY the self-ANNOUNCE datagram carries direct evidence. Third-party gossip
            // (piggyback in a Ping) about an unknown member has NO direct evidence and is
            // honored AS GOSSIPED (unchanged path): a SUSPECT gossip stays SUSPECT and is
            // NOT promoted to ALIVE by the FIX A direct-evidence path.
            var gossip = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            protocol.onMessage(ADDR_B, Ping.ping(NODE_B, 1L, List.of(gossip)));

            assertThat(protocol.members().get(NODE_A).state())
                .as("third-party SUSPECT gossip of an unknown member must stay SUSPECT (no direct evidence)")
                .isEqualTo(MemberState.SUSPECT);
            assertThat(protocol.everSeenHealthyForTest(NODE_A))
                .as("third-party gossip is not reachability proof")
                .isFalse();
        }

        private static NodeInfo nodeInfoFor(NodeId id, InetSocketAddress addr) {
            return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress(addr.getHostString(), addr.getPort()).unwrap());
        }
    }

    /// Announce-carried descriptor labels (#241 Wave A): a node self-describes its
    /// role/source as `NodeInfo` labels; the ANNOUNCE carries them; the receiving node
    /// stores them on the `SwimMember` and re-exposes them via `dialInfoFor`'s reconstructed
    /// `NodeInfo`. An ANNOUNCE without labels (old node / piggyback-only) yields an
    /// address-only `NodeInfo` with no labels and never fails. No `MembershipUpdate` wire change.
    @Nested
    class AnnounceCarriedLabels {
        private RecordingTransport transport;
        private RecordingListener listener;
        private SwimProtocol protocol;
        private RecordingObservationSink observations;

        @BeforeEach
        void setUp() {
            transport = new RecordingTransport();
            listener = new RecordingListener();
            protocol = SwimProtocol.swimProtocol(swimConfig(), transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(cause -> null, v -> v);
            observations = new RecordingObservationSink();
            protocol.addObservationListener(observations);
        }

        @Test
        void dialInfoFor_announceWithRoleAndSource_exposesLabels() {
            protocol.onMessage(ADDR_A, Announce.announce(nodeInfoWithLabels(NODE_A, ADDR_A), "", 0L));

            var discovered = observations.byType(SwimObservation.MemberDiscovered.class);
            assertThat(discovered).hasSize(1);

            var labels = discovered.getFirst().nodeInfo().labels();
            assertThat(labels).containsEntry(NodeInfo.LABEL_ROLE, "active");
            assertThat(labels).containsEntry(NodeInfo.LABEL_SOURCE, "replacement");
        }

        @Test
        void dialInfoFor_announceWithoutLabels_exposesAddressOnlyNodeInfo() {
            protocol.onMessage(ADDR_A, Announce.announce(nodeInfoNoLabels(NODE_A, ADDR_A), "", 0L));

            var discovered = observations.byType(SwimObservation.MemberDiscovered.class);
            assertThat(discovered).hasSize(1);
            assertThat(discovered.getFirst().nodeInfo().labels())
                .as("an ANNOUNCE without descriptor labels must yield an address-only NodeInfo, no failure")
                .isEmpty();
        }

        @Test
        void member_announceWithLabels_retainsLabelsOnSwimMember() {
            protocol.onMessage(ADDR_A, Announce.announce(nodeInfoWithLabels(NODE_A, ADDR_A), "", 0L));

            assertThat(protocol.members().get(NODE_A).labels())
                .containsEntry(NodeInfo.LABEL_ROLE, "active")
                .containsEntry(NodeInfo.LABEL_SOURCE, "replacement");
        }

        private static NodeInfo nodeInfoWithLabels(NodeId id, InetSocketAddress addr) {
            return NodeInfo.nodeInfo(id,
                                     NodeAddress.nodeAddress(addr.getHostString(), addr.getPort()).unwrap(),
                                     NodeRole.ACTIVE,
                                     Map.of(NodeInfo.LABEL_ROLE, "active",
                                            NodeInfo.LABEL_SOURCE, "replacement"));
        }

        private static NodeInfo nodeInfoNoLabels(NodeId id, InetSocketAddress addr) {
            return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress(addr.getHostString(), addr.getPort()).unwrap());
        }
    }

    @Nested
    class PiggybackBufferTests {

        @Test
        void addUpdate_withinCapacity_allRetained() {
            var buffer = PiggybackBuffer.piggybackBuffer(5);
            var update = new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A);

            buffer.addUpdate(update);

            assertThat(buffer.size()).isEqualTo(1);
        }

        @Test
        void peekUpdates_returnsUpdatesWithoutRemoving() {
            var buffer = PiggybackBuffer.piggybackBuffer(10);
            buffer.addUpdate(new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A));
            buffer.addUpdate(new MembershipUpdate(NODE_B, MemberState.ALIVE, 0, ADDR_B));

            var peeked = buffer.peekUpdates(1);

            assertThat(peeked).hasSize(1);
            assertThat(peeked.getFirst().nodeId()).isEqualTo(NODE_A);
            // peekUpdates re-queues non-evicted entries, so size stays 2
            assertThat(buffer.size()).isEqualTo(2);
        }

        @Test
        void addUpdate_exceedsDoubleCapacity_evictsOldest() {
            var buffer = PiggybackBuffer.piggybackBuffer(2);
            // Buffer allows up to maxSize*2=4 entries for dissemination headroom
            buffer.addUpdate(new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A));
            buffer.addUpdate(new MembershipUpdate(NODE_B, MemberState.ALIVE, 0, ADDR_B));
            buffer.addUpdate(new MembershipUpdate(NODE_C, MemberState.ALIVE, 0, ADDR_C));

            assertThat(buffer.size()).isEqualTo(3); // under 2*2=4 threshold

            // Add 2 more to exceed threshold
            buffer.addUpdate(new MembershipUpdate(NODE_A, MemberState.SUSPECT, 1, ADDR_A));
            buffer.addUpdate(new MembershipUpdate(NODE_B, MemberState.SUSPECT, 1, ADDR_B));

            assertThat(buffer.size()).isEqualTo(4); // trimmed to 4 (maxSize*2)
        }

        @Test
        void peekUpdates_emptyBuffer_returnsEmpty() {
            var buffer = PiggybackBuffer.piggybackBuffer(5);

            var peeked = buffer.peekUpdates(3);

            assertThat(peeked).isEmpty();
        }
    }

    @Nested
    class SuspectDetectionTests {
        private RecordingTransport transport;
        private RecordingListener listener;
        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            var config = swimConfig(timeSpan(50).millis(), timeSpan(20).millis(), 3, timeSpan(100).millis(), 8);
            transport = new RecordingTransport();
            listener = new RecordingListener();
            protocol = SwimProtocol.swimProtocol(config, transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(cause -> null, v -> v);
        }

        @Test
        void piggybackDissemination_memberUpdate_propagatedViaPiggyback() {
            protocol.addSeedMember(NODE_A, ADDR_A);
            protocol.addSeedMember(NODE_B, ADDR_B);

            // Respond to any ping — the ack should contain piggybacked membership info
            var ping = new Ping(NODE_A, 1L, List.of());
            protocol.onMessage(ADDR_A, ping);

            assertThat(transport.sentMessages).isNotEmpty();

            var ack = (Ack) transport.sentMessages.getFirst().message();
            // The piggyback should contain updates about newly added members
            assertThat(ack.piggyback()).isNotEmpty();
        }

        /// Regression: peers continuously rebroadcast (N, SUSPECT, k) for a peer
        /// already viewed locally as (SUSPECT, k). Same-state same-incarnation gossip
        /// must be a no-op so the suspect timer can elapse and N transitions to FAULTY.
        @Test
        void suspect_faulty_within_timeout_when_peers_continue_gossiping_suspect() throws InterruptedException {
            // Tight config: suspectTimeout 500ms, period 50ms, fast startup.
            var config = swimConfig(
                timeSpan(50).millis(),
                timeSpan(20).millis(),
                3,
                timeSpan(500).millis(),
                8,
                timeSpan(50).millis()
            );
            var localTransport = new RecordingTransport();
            var localListener = new RecordingListener();
            var localProtocol = SwimProtocol.swimProtocol(config, localTransport, localListener, SELF_ID, SELF_ADDR)
                                            .fold(cause -> null, v -> v);

            localProtocol.addSeedMember(NODE_A, ADDR_A);

            // Seed introduces NODE_A as SUSPECT (probe-on-arrival). Drive it to ALIVE
            // via positive gossip first so the subsequent SUSPECT gossip is a genuine
            // ALIVE->SUSPECT edge (not a same-state no-op against the seeded SUSPECT).
            var aliveGossip = new MembershipUpdate(NODE_A, MemberState.ALIVE, 1L, ADDR_A);
            localProtocol.onMessage(ADDR_B, new Ping(NODE_B, 0L, List.of(aliveGossip)));
            assertThat(localProtocol.members().get(NODE_A).state()).isEqualTo(MemberState.ALIVE);

            // Drive ALIVE -> SUSPECT (incarnation=2) via a piggybacked update from a peer.
            var initialSuspect = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 2L, ADDR_A);
            localProtocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(initialSuspect)));

            assertThat(localListener.suspected).hasSize(1);
            assertThat(localListener.suspected.getFirst().nodeId()).isEqualTo(NODE_A);

            // Snapshot the SwimMember reference: the no-op guard must NOT replace
            // the entry on same-state same-incarnation rebroadcasts.
            var memberRefBefore = localProtocol.members().get(NODE_A);
            assertThat(memberRefBefore.state()).isEqualTo(MemberState.SUSPECT);

            // Flood 14 rebroadcasts of (NODE_A, SUSPECT, incarnation=2). With the bug,
            // each of these would replace members.get(NODE_A) and (in the original
            // diagnosis) reset the suspect timer. With the fix they are no-ops.
            for (var i = 0; i < 14; i++) {
                var rebroadcast = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 2L, ADDR_A);
                localProtocol.onMessage(ADDR_B, new Ping(NODE_B, 100L + i, List.of(rebroadcast)));
            }

            // Reference equality: same-state same-incarnation is observed as a no-op.
            assertThat(localProtocol.members().get(NODE_A)).isSameAs(memberRefBefore);
            // Listener saw exactly one onMemberSuspect — the initial transition only.
            assertThat(localListener.suspected).hasSize(1);

            // Start the protocol so tick() drives expireSuspectMembers().
            localProtocol.start();
            try {
                // Wait suspectTimeout (500ms) plus margin for tick scheduling.
                var deadline = System.currentTimeMillis() + 3_000L;
                while (localListener.faulty.isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50L);
                }

                assertThat(localListener.faulty)
                    .as("NODE_A must transition to FAULTY despite continuous SUSPECT gossip rebroadcasts")
                    .hasSize(1);
                assertThat(localListener.faulty.getFirst().nodeId()).isEqualTo(NODE_A);
                // Still exactly one suspect notification across the whole test.
                assertThat(localListener.suspected).hasSize(1);
            } finally {
                localProtocol.stop();
            }
        }
    }

    @Nested
    class GovernorElectionTests {

        @Test
        void governorElection_lowestNodeId_isDeterministic() {
            // Governor election = lowest NodeId among ALIVE members.
            // This is a pure deterministic property test.
            var members = List.of(
                SwimMember.swimMember(NODE_C, ADDR_C),
                SwimMember.swimMember(NODE_A, ADDR_A),
                SwimMember.swimMember(NODE_B, ADDR_B)
            );

            var governor = members.stream()
                                  .filter(m -> m.state() == MemberState.ALIVE)
                                  .map(SwimMember::nodeId)
                                  .min(NodeId::compareTo)
                                  .orElse(null);

            assertThat(governor).isEqualTo(NODE_A);
        }
    }

    /// Join-announce loop must not self-suppress on an already-quorate cluster (#34).
    /// A replacement joining a quorate cluster previously had its ANNOUNCE cancelled by a
    /// quorum-reached check before it ever sent one, so the leader never saw the replacement.
    /// The corrected design has NO quorum stop condition: a node keeps announcing until IT is
    /// acknowledged by a peer (proven by an inbound SWIM Ping) or the 60-attempt cap is hit.
    @Nested
    class AnnounceSelfSuppression {
        private RecordingTransport transport;
        private RecordingListener listener;
        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            transport = new RecordingTransport();
            listener = new RecordingListener();
            protocol = SwimProtocol.swimProtocol(swimConfig(), transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(cause -> null, v -> v);
        }

        @Test
        void announceJoin_notYetAcknowledged_sendsAnnounce() throws InterruptedException {
            // No quorum signal exists any more — the only stop conditions are inbound-probe and the cap.
            protocol.announceJoin(nodeInfoFor(SELF_ID, SELF_ADDR), "", 0L, List.of(ADDR_A));

            // First scheduled attempt fires at 500ms; wait until at least one ANNOUNCE is sent.
            waitUntil(() -> announceCount(transport) >= 1, 3_000L);

            assertThat(announceCount(transport))
                .as("ANNOUNCE must be sent until self is acknowledged by a peer — no quorum can suppress it")
                .isGreaterThanOrEqualTo(1);
        }

        @Test
        void announceJoin_inboundPingReceived_cancelsAnnounceLoop() throws InterruptedException {
            protocol.announceJoin(nodeInfoFor(SELF_ID, SELF_ADDR), "", 0L, List.of(ADDR_A));

            waitUntil(() -> announceCount(transport) >= 1, 3_000L);
            assertThat(announceCount(transport)).isGreaterThanOrEqualTo(1);

            // Deliver an inbound Ping through the real receive path — sets inboundProbeReceived.
            protocol.onMessage(ADDR_A, new Ping(NODE_A, 1L, List.of()));

            // The loop must stop announcing on the next tick: count freezes after the latch is set.
            var afterPing = announceCount(transport);
            Thread.sleep(1_200L); // span at least two 500ms announce ticks
            assertThat(announceCount(transport))
                .as("announce loop must cancel on the tick following an inbound Ping (self acknowledged)")
                .isEqualTo(afterPing);
        }

        private static int announceCount(RecordingTransport transport) {
            return (int) transport.sentMessages.stream()
                                               .filter(m -> m.message() instanceof Announce)
                                               .count();
        }

        private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
            var deadline = System.currentTimeMillis() + timeoutMs;
            while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
                Thread.sleep(25L);
            }
        }

        private static NodeInfo nodeInfoFor(NodeId id, InetSocketAddress addr) {
            return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress(addr.getHostString(), addr.getPort()).unwrap());
        }
    }

    @Nested
    class LifecycleTests {
        private RecordingTransport transport;
        private RecordingListener listener;

        @BeforeEach
        void setUp() {
            transport = new RecordingTransport();
            listener = new RecordingListener();
        }

        @Test
        void start_alreadyRunning_returnsError() {
            var protocol = SwimProtocol.swimProtocol(swimConfig(), transport, listener, SELF_ID, SELF_ADDR)
                                       .fold(cause -> null, v -> v);

            protocol.start();
            var second = protocol.start();

            assertThat(second.isSuccess()).isFalse();

            protocol.stop();
        }

        @Test
        void stop_notRunning_returnsError() {
            var protocol = SwimProtocol.swimProtocol(swimConfig(), transport, listener, SELF_ID, SELF_ADDR)
                                       .fold(cause -> null, v -> v);

            var result = protocol.stop();

            assertThat(result.isSuccess()).isFalse();
        }
    }

    /// Durable monotonic self-incarnation (cluster-churn root-cause fix). A live-but-
    /// suspected node must refute with a STRICTLY ADVANCING, durably-stored incarnation,
    /// and must proactively re-advertise its own ALIVE at that incarnation. The prior
    /// reactive path re-sent the same value forever (`incoming + 1`, never stored), which
    /// an equal-incarnation Suspect out-ordered (FAULTY>SUSPECT>ALIVE at equal incarnation),
    /// so a live node could be driven SUSPECT->FAULTY->evicted under loss/churn.
    @Nested
    class SelfRefutation {
        private RecordingTransport transport;
        private RecordingListener listener;
        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            transport = new RecordingTransport();
            listener = new RecordingListener();
            protocol = SwimProtocol.swimProtocol(swimConfig(), transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(cause -> null, v -> v);
        }

        @Test
        void handleSelfUpdate_repeatedSuspicion_refutesWithStrictlyIncreasingDurableIncarnation() {
            // Three suspicions of self at the same incarnation X=5. Each refutation must
            // strictly advance the durably-stored self-incarnation past 5 (not re-send 6).
            var selfSuspect = new MembershipUpdate(SELF_ID, MemberState.SUSPECT, 5L, SELF_ADDR);

            protocol.onMessage(ADDR_A, new Ping(NODE_A, 1L, List.of(selfSuspect)));
            var afterFirst = protocol.selfIncarnation();
            assertThat(afterFirst)
                .as("first refutation must strictly exceed the suspicion incarnation (5)")
                .isGreaterThan(5L);

            protocol.onMessage(ADDR_A, new Ping(NODE_A, 2L, List.of(selfSuspect)));
            var afterSecond = protocol.selfIncarnation();
            assertThat(afterSecond)
                .as("durable: each refutation strictly advances the stored incarnation")
                .isGreaterThan(afterFirst);

            protocol.onMessage(ADDR_A, new Ping(NODE_A, 3L, List.of(selfSuspect)));
            assertThat(protocol.selfIncarnation())
                .as("monotonic advance persists across repeated suspicions")
                .isGreaterThan(afterSecond);
        }

        @Test
        void handleSelfUpdate_higherSuspicionIncarnation_refutationOutpacesIt() {
            // A suspicion raised at a high incarnation must still be strictly out-paced.
            var highSuspect = new MembershipUpdate(SELF_ID, MemberState.SUSPECT, 42L, SELF_ADDR);

            protocol.onMessage(ADDR_A, new Ping(NODE_A, 1L, List.of(highSuspect)));

            assertThat(protocol.selfIncarnation())
                .as("refutation must strictly exceed the (higher) suspicion incarnation 42")
                .isGreaterThan(42L);
        }

        @Test
        void refreshSelfAlive_afterRefutation_gossipsSelfAliveAtAdvancedIncarnation() throws InterruptedException {
            // Establish a non-zero self-incarnation via announceJoin (boot incarnation 7),
            // then refute a suspicion to advance it. The proactive self-ALIVE that rides
            // each probe round must carry the ADVANCED incarnation.
            protocol.announceJoin(nodeInfoFor(SELF_ID, SELF_ADDR), "", 7L, List.of(ADDR_A));

            var selfSuspect = new MembershipUpdate(SELF_ID, MemberState.SUSPECT, 7L, SELF_ADDR);
            protocol.onMessage(ADDR_A, new Ping(NODE_A, 1L, List.of(selfSuspect)));
            var advanced = protocol.selfIncarnation();
            assertThat(advanced).isGreaterThan(7L);

            // Start the protocol so a tick fires refreshSelfAlive(), seeding the
            // piggyback buffer with self-ALIVE at the advanced incarnation.
            protocol.start();
            try {
                // Drive an inbound Ping each poll; the returned Ack piggyback carries the
                // buffered self-ALIVE once a probe tick has refreshed it.
                var deadline = System.currentTimeMillis() + 3_000L;
                Option<MembershipUpdate> selfAlive = Option.none();
                while (selfAlive.isEmpty() && System.currentTimeMillis() < deadline) {
                    protocol.onMessage(ADDR_A, new Ping(NODE_A, 99L, List.of()));
                    selfAlive = latestSelfAliveInLastAck();
                    if (selfAlive.isEmpty()) {
                        Thread.sleep(25L);
                    }
                }

                assertThat(selfAlive.isPresent())
                    .as("a proactive self-ALIVE must be disseminated on the probe round")
                    .isTrue();
                selfAlive.onPresent(update -> assertThat(update.incarnation())
                    .as("proactively-gossiped self-ALIVE must carry the advanced incarnation")
                    .isEqualTo(advanced));
            } finally {
                protocol.stop();
            }
        }

        private Option<MembershipUpdate> latestSelfAliveInLastAck() {
            return transport.sentMessages.stream()
                                         .map(SentMessage::message)
                                         .filter(m -> m instanceof Ack)
                                         .map(m -> (Ack) m)
                                         .flatMap(ack -> ack.piggyback().stream())
                                         .filter(u -> u.nodeId().equals(SELF_ID) && u.state() == MemberState.ALIVE)
                                         .reduce((a, b) -> b)
                                         .map(Option::option)
                                         .orElse(Option.none());
        }

        private static NodeInfo nodeInfoFor(NodeId id, InetSocketAddress addr) {
            return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress(addr.getHostString(), addr.getPort()).unwrap());
        }
    }

    // -- Test infrastructure --

    record SentMessage(InetSocketAddress target, SwimMessage message) {}

    static class RecordingTransport implements SwimTransport {
        final CopyOnWriteArrayList<SentMessage> sentMessages = new CopyOnWriteArrayList<>();
        final AtomicReference<SwimMessageHandler> handler = new AtomicReference<>();

        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            sentMessages.add(new SentMessage(target, message));
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> start(int port, SwimMessageHandler handler) {
            this.handler.set(handler);
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            handler.set(null);
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
        public void onMemberFaulty(SwimMember member) {
            faulty.add(member);
        }

        @Override
        public void onMemberLeft(NodeId nodeId) {
            left.add(nodeId);
        }
    }

    static class RecordingObservationSink implements java.util.function.Consumer<SwimObservation> {
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
