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

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import io.netty.handler.codec.quic.QuicSslContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(30)
class QuicClusterNetworkTest {

    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(10).seconds();
    private static final TimeSpan PING_INTERVAL = TimeSpan.timeSpan(1).seconds();
    private static final TimeSpan HELLO_TIMEOUT = TimeSpan.timeSpan(5).seconds();

    private SliceCodec codec;
    private QuicSslContext serverSsl;
    private QuicSslContext clientSsl;
    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        codec = SliceCodec.sliceCodec(
            FrameworkCodecs.frameworkCodecs(),
            combinedCodecs()
        );
        serverSsl = QuicTlsProvider.serverContext(TlsConfig.selfSignedServer())
                                    .fold(_ -> fail("Server SSL failed"), ssl -> ssl);
        clientSsl = QuicTlsProvider.clientContext(TlsConfig.insecureClient())
                                    .fold(_ -> fail("Client SSL failed"), ssl -> ssl);
    }

    @AfterEach
    void tearDown() {
        for (var network : networks) {
            network.stop().await(AWAIT_TIMEOUT);
        }
        networks.clear();
    }

    @Nested
    class Lifecycle {

        @Test
        void start_succeeds_serverBound() {
            var network = createAndStartNetwork(NodeId.randomNodeId(), List.of(), MessageRouter.mutable());

            assertThat(network.connectedNodeCount()).isZero();
            assertThat(network.connectedPeers()).isEmpty();
            assertThat(network.boundPort().isPresent())
                .as("Server should be bound to a port")
                .isTrue();
        }

        @Test
        void stop_afterStart_cleansUp() {
            var network = createAndStartNetwork(NodeId.randomNodeId(), List.of(), MessageRouter.mutable());

            network.stop().await(AWAIT_TIMEOUT)
                   .onFailure(cause -> fail("Stop failed: " + cause.message()));

            assertThat(network.connectedNodeCount()).isZero();
        }

        @Test
        void server_returnsEmpty_quicDoesNotUseTcpServer() {
            var network = createAndStartNetwork(NodeId.randomNodeId(), List.of(), MessageRouter.mutable());

            assertThat(network.server().isEmpty())
                .as("QUIC transport should not expose a TCP Server")
                .isTrue();
        }
    }

    @Nested
    class NodeIdOrdering {

        @Test
        void shouldInitiate_lowerIdInitiates_higherIdWaits() {
            var lower = new NodeId("aaa");
            var higher = new NodeId("zzz");

            assertThat(ConnectionDirection.shouldInitiate(lower, higher))
                .as("Lower NodeId should initiate")
                .isTrue();
            assertThat(ConnectionDirection.shouldInitiate(higher, lower))
                .as("Higher NodeId should not initiate")
                .isFalse();
        }

        @Test
        void shouldInitiate_equalIds_doesNotInitiate() {
            var id = new NodeId("same");

            assertThat(ConnectionDirection.shouldInitiate(id, id))
                .as("Equal NodeIds should not initiate")
                .isFalse();
        }
    }

    @Nested
    class ListNodes {

        @Test
        void listNodes_noConnections_returnsEmptyList() {
            var messages = new CopyOnWriteArrayList<>();
            var router = MessageRouter.mutable();
            router.addRoute(NetworkServiceMessage.ConnectedNodesList.class, messages::add);

            var network = createAndStartNetwork(NodeId.randomNodeId(), List.of(), router);

            network.listNodes(new NetworkServiceMessage.ListConnectedNodes());

            assertThat(messages).hasSize(1);
            var list = (NetworkServiceMessage.ConnectedNodesList) messages.getFirst();
            assertThat(list.connected()).isEmpty();
        }
    }

    @Nested
    class BroadcastAndSend {

        @Test
        void broadcast_noConnections_doesNotFail() {
            var network = createAndStartNetwork(NodeId.randomNodeId(), List.of(), MessageRouter.mutable());

            // Should not throw even with no connected peers
            network.broadcast(stubProtocolMessage(NodeId.randomNodeId()));
        }

        @Test
        void send_unknownPeer_logsWarning() {
            var network = createAndStartNetwork(NodeId.randomNodeId(), List.of(), MessageRouter.mutable());

            // Should not throw for unknown peer
            network.send(NodeId.randomNodeId(), stubProtocolMessage(NodeId.randomNodeId()));
        }
    }

    /// R5 (spec §4.1): transport never mutates topology. The chaos kill+recreate
    /// reconnect path emits a `ConnectionEstablished` and forwards the
    /// `TransportObservation` upward via the peer-state listener — but does NOT call
    /// `registerPeer` on `TopologyObserver` (which has been removed). Authoritative
    /// membership flows through HealthReconciler → KV → snapshot.
    @Nested
    class ReconnectFinalization {

        @Test
        void finalizeReconnect_routesConnectionEstablished_doesNotMutateTopology() {
            var router = MessageRouter.mutable();
            var establishedFor = new CopyOnWriteArrayList<NodeId>();
            router.addRoute(NetworkServiceMessage.ConnectionEstablished.class,
                            msg -> establishedFor.add(msg.nodeId()));

            var network = createNetworkWithStubTopology(NodeId.randomNodeId(), router);

            var peerId = NodeId.randomNodeId();
            var address = NodeAddress.nodeAddress("10.0.0.5", 19500)
                                     .fold(_ -> fail("Invalid address"), addr -> addr);
            var info = NodeInfo.nodeInfo(peerId, address, NodeRole.ACTIVE, Map.of());

            network.finalizeReconnect(peerId, Option.some(info));

            assertThat(establishedFor)
                .as("ConnectionEstablished routes through during reconnect finalization")
                .containsExactly(peerId);
        }

        /// P1 regression: `finalizeReconnect` MUST propagate the Hello-derived
        /// `NodeInfo` into the routed `ConnectionEstablished` so the SWIM handler
        /// in `AetherNode` can resolve the peer's address without falling back to
        /// a stale static-topology lookup. Without this, CTM-replaced peers stay
        /// permanently UNKNOWN and the QUIC eviction storm recurs.
        @Test
        void finalizeReconnect_unknownNodeInfo_propagatedIntoRoutedMessage() {
            var router = MessageRouter.mutable();
            var captured = new CopyOnWriteArrayList<NetworkServiceMessage.ConnectionEstablished>();
            router.addRoute(NetworkServiceMessage.ConnectionEstablished.class, captured::add);

            var network = createNetworkWithStubTopology(NodeId.randomNodeId(), router);

            var peerId = NodeId.randomNodeId();
            var address = NodeAddress.nodeAddress("10.0.0.5", 19500)
                                     .fold(_ -> fail("Invalid address"), addr -> addr);
            var info = NodeInfo.nodeInfo(peerId, address, NodeRole.ACTIVE, Map.of());

            network.finalizeReconnect(peerId, Option.some(info));

            assertThat(captured).hasSize(1);
            var msg = captured.getFirst();
            assertThat(msg.nodeId()).isEqualTo(peerId);
            assertThat(msg.nodeInfo().isPresent())
                .as("transport-supplied NodeInfo must propagate to the routed ConnectionEstablished")
                .isTrue();
            assertThat(msg.nodeInfo().unwrap()).isEqualTo(info);
        }

        @Test
        void finalizeReconnect_knownPeer_routesConnectionEstablished() {
            var router = MessageRouter.mutable();
            var establishedFor = new CopyOnWriteArrayList<NodeId>();
            router.addRoute(NetworkServiceMessage.ConnectionEstablished.class,
                            msg -> establishedFor.add(msg.nodeId()));
            var network = createNetworkWithStubTopology(NodeId.randomNodeId(), router);

            var peerId = NodeId.randomNodeId();
            // Empty Option signals "topology already knew this peer at the call site".
            network.finalizeReconnect(peerId, Option.empty());

            assertThat(establishedFor)
                .as("ConnectionEstablished routes through even for known peers")
                .containsExactly(peerId);
        }
    }

    /// Wave 2 ADD-only FSM-wired reconciler. When `setDesiredConnections` is wired the
    /// missing-peer reconciler reconciles against the FSM desired core-member set instead of
    /// static topology, dropping the legacy SWIM membership/health gates. These tests drive
    /// `reconcileMissingPeersTick()` directly against seeded `PeerState`s on a started network
    /// (live client) and assert via the dial seam: `connectPeer` resolves the address on the
    /// Netty event loop (deferred resolution) and THEN calls `PeerState.beginConnecting`, so a
    /// considered-for-dial peer leaves INIT asynchronously (awaited). The dial to the unroutable
    /// test address then fails fast, evicting CONNECTING → EVICTED — both post-INIT phases prove
    /// the dispatch. Peers are seeded in INIT (the fresh `PeerState` phase) so no live QUIC
    /// connection is needed to reach the observable transition.
    @Nested
    class FsmWiredReconciler {

        @Test
        void reconcileTick_wiredPath_dialsMissingDesiredPeer() {
            var self = new NodeId("aaa-self");
            var network = createAndStartNetwork(self, List.of(), MessageRouter.mutable());

            var peerId = new NodeId("zzz-peer");
            var seeded = network.seedPeerForTests(peerId, initPeer(peerId));
            network.setDesiredConnections(() -> List.of(desiredNodeInfo(peerId, NodeRole.ACTIVE)));

            network.reconcileMissingPeersTick();

            // The dial is dispatched on the deferred-resolution continuation (async): await the
            // peer LEAVING INIT. The dial to the unroutable port then fails fast, so the settled
            // phase is CONNECTING or (post connect-failure) EVICTED — both prove the dispatch.
            awaitTrue(() -> seeded.phase() != PeerState.Phase.INIT,
                      "Wired reconciler dials a missing (not-connected) desired peer: leaves INIT");
            assertThat(seeded.phase())
                .as("dispatched dial lands in CONNECTING (in flight) or EVICTED (failed fast)")
                .isIn(PeerState.Phase.CONNECTING, PeerState.Phase.EVICTED);
        }

        @Test
        void reconcileTick_wiredPath_ignoresTopologyUsesDesiredSet() {
            var self = new NodeId("aaa-self");
            // topologyPeer is present in topology() but absent from the desired set.
            var topologyPeer = new NodeId("mmm-topology");
            var topologyInfo = topologyNodeInfo(topologyPeer);
            var network = createAndStartNetwork(self, List.of(topologyInfo), MessageRouter.mutable());

            // desiredPeer is absent from topology() but present in the desired set.
            var desiredPeer = new NodeId("zzz-desired");
            var topologySeed = network.seedPeerForTests(topologyPeer, initPeer(topologyPeer));
            var desiredSeed = network.seedPeerForTests(desiredPeer, initPeer(desiredPeer));
            network.setDesiredConnections(() -> List.of(desiredNodeInfo(desiredPeer, NodeRole.ACTIVE)));

            network.reconcileMissingPeersTick();

            awaitTrue(() -> desiredSeed.phase() != PeerState.Phase.INIT,
                      "Desired peer absent from topology IS considered for dial: leaves INIT");
            assertThat(desiredSeed.phase())
                .as("dispatched dial lands in CONNECTING (in flight) or EVICTED (failed fast)")
                .isIn(PeerState.Phase.CONNECTING, PeerState.Phase.EVICTED);
            assertThat(topologySeed.phase())
                .as("Topology peer absent from desired set is NOT considered: stays INIT")
                .isEqualTo(PeerState.Phase.INIT);
        }

        @Test
        void reconcileTick_wiredPath_readmitsRemovedDesiredPeerWithoutSwimGate() {
            var self = new NodeId("aaa-self");
            var network = createAndStartNetwork(self, List.of(), MessageRouter.mutable());

            var peerId = new NodeId("zzz-removed");
            var removed = PeerState.peerState(peerId, System.nanoTime());
            removed.authoritativeRemove(System.nanoTime());
            var seeded = network.seedPeerForTests(peerId, removed);
            // No SWIM membership gate wired — presence in the desired set is the re-admit authority.
            network.setDesiredConnections(() -> List.of(desiredNodeInfo(peerId, NodeRole.ACTIVE)));

            network.reconcileMissingPeersTick();

            // REMOVED -> INIT happens synchronously in the tick (readmit); the dial dispatch is
            // async (deferred resolution), so await the post-INIT phase.
            awaitTrue(() -> seeded.phase() == PeerState.Phase.CONNECTING
                            || seeded.phase() == PeerState.Phase.EVICTED,
                      "REMOVED desired peer is readmitted then dialled: REMOVED -> INIT -> CONNECTING");
        }

        @Test
        void reconcileTick_wiredPath_higherSelfIdSkipsDesiredPeerUntilGrace() {
            // self id is HIGHER than the peer id, so ConnectionDirection.shouldInitiate is false
            // and the grace window has not elapsed (peer just observed missing) — skip the dial.
            var self = new NodeId("zzz-self");
            var network = createAndStartNetwork(self, List.of(), MessageRouter.mutable());

            var peerId = new NodeId("aaa-peer");
            var seeded = network.seedPeerForTests(peerId, initPeer(peerId));
            network.setDesiredConnections(() -> List.of(desiredNodeInfo(peerId, NodeRole.ACTIVE)));

            network.reconcileMissingPeersTick();

            assertThat(ConnectionDirection.shouldInitiate(self, peerId))
                .as("Higher self id does not initiate to a lower peer id")
                .isFalse();
            assertThat(seeded.phase())
                .as("Single-dialer honoured in wired path: higher-self skips dial before grace, stays INIT")
                .isEqualTo(PeerState.Phase.INIT);
        }

        @Test
        void reconcileTick_unwiredDefault_usesLegacyTopologyPath() {
            var self = new NodeId("aaa-self");
            var peerId = new NodeId("zzz-legacy");
            var peerInfo = topologyNodeInfo(peerId);
            // Peer is in topology() and (via the default coreNodes()) in the SWIM-membership set,
            // so the legacy path considers it. No desiredConnections wired → legacy branch taken.
            var network = createAndStartNetwork(self, List.of(peerInfo), MessageRouter.mutable());

            var seeded = network.seedPeerForTests(peerId, initPeer(peerId));

            network.reconcileMissingPeersTick();

            awaitTrue(() -> seeded.phase() != PeerState.Phase.INIT,
                      "Unwired default runs the legacy topology-driven reconciler: leaves INIT");
            assertThat(seeded.phase())
                .as("dispatched dial lands in CONNECTING (in flight) or EVICTED (failed fast)")
                .isIn(PeerState.Phase.CONNECTING, PeerState.Phase.EVICTED);
        }

        private PeerState initPeer(NodeId peerId) {
            // Fresh PeerState is in INIT — the dial seam (connectPeer -> beginConnecting) drives
            // INIT -> CONNECTING, observable without standing up a live QUIC connection.
            return PeerState.peerState(peerId, System.nanoTime());
        }

        private NodeInfo desiredNodeInfo(NodeId peerId, NodeRole role) {
            var address = NodeAddress.nodeAddress("127.0.0.1", 1)
                                     .fold(_ -> fail("Invalid address"), addr -> addr);
            return NodeInfo.nodeInfo(peerId, address, role, Map.of());
        }

        private NodeInfo topologyNodeInfo(NodeId peerId) {
            var address = NodeAddress.nodeAddress("127.0.0.1", 1)
                                     .fold(_ -> fail("Invalid address"), addr -> addr);
            return NodeInfo.nodeInfo(peerId, address, NodeRole.ACTIVE, Map.of());
        }
    }

    /// Wave-3 dialer-side Hello identity verification (cluster-topology-overhaul spec). The
    /// dialer verifies `hello.sender() == dialedPeerId` inside
    /// `QuicClusterClient.completePeerConnection`: a mismatch (e.g. a DNS re-resolution landing
    /// on whatever answers) closes the connection un-attached and fails the dial down the
    /// normal connect-failed path ([QuicClusterNetwork] `onConnectFailed`), which journals a
    /// `dialer-hello-REJECTED` record. These tests stand up two REAL networks and dial under a
    /// wrong/right expected identity.
    @Nested
    class DialerHelloIdentityEnforcement {

        @Test
        void dial_mismatchedHelloSender_rejectedJournaledAndFailedNotAttached() {
            var actualPeer = createAndStartNetwork(new NodeId("bbb-actual"), List.of(), MessageRouter.mutable());
            var actualPort = actualPeer.boundPort().fold(() -> fail("peer not bound"), port -> port);

            var failures = new CopyOnWriteArrayList<NetworkServiceMessage.ConnectionFailed>();
            var established = new CopyOnWriteArrayList<NetworkServiceMessage.ConnectionEstablished>();
            var router = MessageRouter.mutable();
            router.addRoute(NetworkServiceMessage.ConnectionFailed.class, failures::add);
            router.addRoute(NetworkServiceMessage.ConnectionEstablished.class, established::add);
            var dialer = createAndStartNetwork(new NodeId("aaa-self"), List.of(), router);
            var journal = new CopyOnWriteArrayList<PeerTransitionRecord>();
            dialer.setPeerTransitionListener(journal::add);

            var expectedId = new NodeId("zzz-expected");
            dialer.connect(dialTarget(expectedId, actualPort));

            awaitTrue(() -> journal.stream().anyMatch(DialerHelloIdentityEnforcement::isRejectedRecord),
                      "journal records the dialer-hello-REJECTED cause");
            var rejected = journal.stream()
                                  .filter(DialerHelloIdentityEnforcement::isRejectedRecord)
                                  .findFirst()
                                  .orElseThrow();
            assertThat(rejected.peerId()).isEqualTo(expectedId);
            assertThat(rejected.cause()).contains("expected=zzz-expected")
                                        .contains("actual=bbb-actual")
                                        .contains("addr=");
            awaitTrue(() -> !failures.isEmpty(), "ConnectionFailed routed — normal connect-failed path engaged");
            assertThat(failures.getFirst().nodeId()).isEqualTo(expectedId);
            assertThat(established).as("a mismatched dial must never attach").isEmpty();
            assertThat(dialer.connectedPeers()).as("peer must not be CONNECTED").isEmpty();
            assertThat(dialer.connectedNodeCount()).isZero();
        }

        @Test
        void dial_matchingHelloSender_attachesAsBefore() {
            var peerId = new NodeId("zzz-actual");
            var peer = createAndStartNetwork(peerId, List.of(), MessageRouter.mutable());
            var peerPort = peer.boundPort().fold(() -> fail("peer not bound"), port -> port);

            var established = new CopyOnWriteArrayList<NetworkServiceMessage.ConnectionEstablished>();
            var router = MessageRouter.mutable();
            router.addRoute(NetworkServiceMessage.ConnectionEstablished.class, established::add);
            var dialer = createAndStartNetwork(new NodeId("aaa-self"), List.of(), router);
            var journal = new CopyOnWriteArrayList<PeerTransitionRecord>();
            dialer.setPeerTransitionListener(journal::add);

            dialer.connect(dialTarget(peerId, peerPort));

            awaitTrue(() -> dialer.connectedPeers().contains(peerId), "verified peer attaches: CONNECTED");
            awaitTrue(() -> !established.isEmpty(), "ConnectionEstablished routes for the verified peer");
            assertThat(established.getFirst().nodeId()).isEqualTo(peerId);
            assertThat(journal.stream().anyMatch(DialerHelloIdentityEnforcement::isDialerHelloRecord))
                .as("the Wave-1 dialer-hello journal observation stays on a verified attach")
                .isTrue();
            assertThat(journal.stream().noneMatch(DialerHelloIdentityEnforcement::isRejectedRecord))
                .as("no REJECTED record for a verified identity")
                .isTrue();
        }

        private static boolean isRejectedRecord(PeerTransitionRecord record) {
            return record.cause().startsWith("dialer-hello-REJECTED");
        }

        private static boolean isDialerHelloRecord(PeerTransitionRecord record) {
            return record.cause().startsWith("dialer-hello ");
        }

        private NodeInfo dialTarget(NodeId expectedId, int port) {
            var address = NodeAddress.nodeAddress("127.0.0.1", port)
                                     .fold(_ -> fail("Invalid address"), addr -> addr);
            return NodeInfo.nodeInfo(expectedId, address, NodeRole.ACTIVE, Map.of());
        }
    }

    // --- Helper methods ---

    private QuicClusterNetwork createAndStartNetwork(NodeId nodeId, List<NodeInfo> peers, MessageRouter router) {
        var nodeAddress = NodeAddress.nodeAddress("127.0.0.1", 19999)
                                     .fold(_ -> fail("Invalid address"), addr -> addr);
        var selfInfo = NodeInfo.nodeInfo(nodeId, nodeAddress);
        TopologyObserver topology = stubTopologyManager(selfInfo, peers);

        var network = new QuicClusterNetwork(topology, codec, codec, router, serverSsl, clientSsl);
        networks.add(network);

        network.startOnPort(0).await(AWAIT_TIMEOUT)
               .onFailure(cause -> fail("Start failed: " + cause.message()));

        return network;
    }

    /// Build a QUIC network with the standard stub topology, used by reconnect-finalization
    /// tests that drive the post-attach path directly without standing up a real handshake.
    /// R5: transport never mutates topology — the stub no longer needs to capture
    /// `registerPeer` calls (the API is gone).
    private QuicClusterNetwork createNetworkWithStubTopology(NodeId nodeId, MessageRouter router) {
        var nodeAddress = NodeAddress.nodeAddress("127.0.0.1", 19998)
                                     .fold(_ -> fail("Invalid address"), addr -> addr);
        var selfInfo = NodeInfo.nodeInfo(nodeId, nodeAddress);
        TopologyObserver topology = stubTopologyManager(selfInfo, List.of());

        var network = new QuicClusterNetwork(topology, codec, codec, router, serverSsl, clientSsl);
        networks.add(network);
        return network;
    }

    @SuppressWarnings("unused")
    private MessageRouter trackingRouter() {
        var router = MessageRouter.mutable();
        router.addRoute(NetworkServiceMessage.ConnectionEstablished.class, _ -> {});
        router.addRoute(NetworkServiceMessage.ConnectionFailed.class, _ -> {});
        router.addRoute(TransportObservation.PeerJoined.class, _ -> {});
        router.addRoute(TransportObservation.PeerDisconnected.class, _ -> {});
        router.addRoute(TransportObservation.SelfShutdown.class, _ -> {});
        router.addRoute(ClusterStateNotification.class, _ -> {});
        router.addRoute(NetworkServiceMessage.Send.class, _ -> {});
        router.addRoute(NetworkServiceMessage.ConnectedNodesList.class, _ -> {});
        return router;
    }

    private static StubProtocolMessage stubProtocolMessage(NodeId sender) {
        return new StubProtocolMessage(sender);
    }

    /// Bounded poll for an async condition (no Awaitility in this module): the QUIC dial,
    /// handshake, and failure continuations run on Netty event loops, so observable effects
    /// (journal records, routed messages, attach) arrive asynchronously. Asserts the condition
    /// holds within AWAIT_TIMEOUT.
    private static void awaitTrue(BooleanSupplier condition, String description) {
        var deadline = System.nanoTime() + AWAIT_TIMEOUT.nanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            sleepBriefly();
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record StubProtocolMessage(NodeId sender) implements org.pragmatica.consensus.ProtocolMessage {
        @Override
        public StreamType streamType() {
            return StreamType.CONSENSUS;
        }
    }

    private TopologyObserver stubTopologyManager(NodeInfo self, List<NodeInfo> peers) {
        return new TopologyObserver() {
            @Override
            public NodeInfo self() {
                return self;
            }

            @Override
            public Option<NodeInfo> get(NodeId id) {
                if (id.equals(self.id())) {
                    return Option.some(self);
                }
                return peers.stream()
                            .filter(p -> p.id().equals(id))
                            .findFirst()
                            .map(Option::some)
                            .orElse(Option.empty());
            }

            @Override
            public int clusterSize() {
                return Math.max(peers.size() + 1, 1);
            }

            @Override
            public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
            }

            @Override
            public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override
            public TimeSpan pingInterval() {
                return PING_INTERVAL;
            }

            @Override
            public TimeSpan helloTimeout() {
                return HELLO_TIMEOUT;
            }

            @Override
            public Option<TlsConfig> tls() {
                return Option.empty();
            }

            @Override
            public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override
            public List<NodeId> topology() {
                var result = new ArrayList<NodeId>();
                result.add(self.id());
                peers.forEach(p -> result.add(p.id()));
                return result;
            }

            @Override public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {}
            @Override public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {}
            @Override public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {}
            @Override public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {}
        };
    }

    private static List<SliceCodec.TypeCodec<?>> combinedCodecs() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();
        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);
        return all;
    }
}
