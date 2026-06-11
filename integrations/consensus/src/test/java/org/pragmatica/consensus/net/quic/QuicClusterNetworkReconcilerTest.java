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

import io.netty.handler.codec.quic.QuicSslContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterFormationConfig;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Verifies the periodic missing-peer reconciler in [`QuicClusterNetwork`]: every tick walks
/// configured peers, and for any peer whose `PeerState.phase()` is not CONNECTED, dispatches
/// a reconnect attempt subject to per-peer jittered exponential backoff.
///
/// Closes the asymmetric-handshake hole — node A handshakes with B/C/D but permanently misses
/// E — that left the smoke-gate sticky on `4 of 5` healthy peers.
@Timeout(10)
class QuicClusterNetworkReconcilerTest {
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(5).seconds();
    private static final TimeSpan PING_INTERVAL = TimeSpan.timeSpan(1).seconds();
    private static final TimeSpan HELLO_TIMEOUT = TimeSpan.timeSpan(5).seconds();

    private SliceCodec codec;
    private QuicSslContext serverSsl;
    private QuicSslContext clientSsl;
    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), combinedCodecs());
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

    @Test
    void reconcileMissingPeersTick_oneMissingPeer_dispatchesSingleDial() {
        // Self has lower NodeId than the configured peer so ConnectionDirection lets us
        // initiate. Peer is unreachable (port 1 is reserved/blackhole). The reconciler should
        // count it as missing on the first tick and request its NodeInfo from topology.
        var self = new NodeId("aaa-self");
        var missingPeer = new NodeId("zzz-missing");
        var peerInfo = NodeInfo.nodeInfo(missingPeer, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        network.reconcileMissingPeersTick();

        assertThat(stub.lookups.getOrDefault(missingPeer, 0L))
            .as("first reconciler tick must dispatch one dial for the missing peer")
            .isEqualTo(1L);
    }

    @Test
    void reconcileMissingPeersTick_allPeersConnected_isNoOp() {
        // No peers configured beyond self → connectedPeers() == configured peers (empty);
        // reconciler must not look anything up.
        var self = new NodeId("solo-self");
        var stub = countingTopology(self, List.of());
        var network = createNetwork(self, List.of(), MessageRouter.mutable(), stub);

        network.reconcileMissingPeersTick();

        assertThat(stub.lookups)
            .as("reconciler must be a no-op when no peers are configured")
            .isEmpty();
    }

    @Test
    void reconcileMissingPeersTick_repeatedTickWithinBackoff_doesNotBurstLoop() {
        // 5 ticks fired in tight succession under a frozen clock must not produce 5 dials
        // for the same missing peer. The first tick seeds nextAttemptMs ≥ now + 5000ms*0.8;
        // ticks 2..5 within the same logical instant fall before that deadline and are
        // suppressed by the per-peer reconcile-backoff state on PeerState.
        var self = new NodeId("aaa-self");
        var missingPeer = new NodeId("zzz-missing");
        var peerInfo = NodeInfo.nodeInfo(missingPeer, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        for (int i = 0; i < 5; i++) {
            network.reconcileMissingPeersTick();
            // Advance 1ms — well below the 5s lower jitter floor (5000 * 0.8 = 4000ms).
            clock.addAndGet(1L);
        }

        assertThat(stub.lookups.getOrDefault(missingPeer, 0L))
            .as("close-spaced reconciler ticks must coalesce; backoff suppresses bursts")
            .isEqualTo(1L);
    }

    @Test
    void reconcileMissingPeersTick_backoffProgresses_onRepeatedFailures() {
        // Drive the reconciler past its first backoff window, then trigger a second tick.
        // The second window must be at least 2x the first (delay doubled before jitter).
        // The topology returns Option.empty() on get() so connectPeer is never invoked
        // (no async phase change to CONNECTING). The reconciler still counts "dispatched"
        // attempts via the topology lookup — backoff progression is exercised end-to-end.
        var self = new NodeId("aaa-self");
        var missingPeer = new NodeId("zzz-missing");
        var stub = countingTopologyWithoutInfo(self, List.of(missingPeer));
        var clock = new AtomicLong(0L);
        var network = createNetwork(self, List.of(), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        // Tick 1 — first attempt dispatched, currentDelay seeded to 5_000ms,
        // nextAttemptMs ∈ [4_000, 6_000].
        network.reconcileMissingPeersTick();
        assertThat(stub.lookups.getOrDefault(missingPeer, 0L)).isEqualTo(1L);

        // Step past upper jitter bound of first window (5_000 * 1.2 = 6_000ms).
        clock.set(6_500L);

        // Tick 2 — second attempt dispatched, currentDelay doubled to 10_000ms,
        // nextAttemptMs ∈ [now + 8_000, now + 12_000].
        network.reconcileMissingPeersTick();
        assertThat(stub.lookups.getOrDefault(missingPeer, 0L)).isEqualTo(2L);

        // Probe inside the second window's lower bound (clock at 6_500 + 7_000 = 13_500;
        // nextAttemptMs ≥ 6_500 + 8_000 = 14_500 → still suppressed).
        clock.addAndGet(7_000L);
        network.reconcileMissingPeersTick();
        assertThat(stub.lookups.getOrDefault(missingPeer, 0L))
            .as("second backoff window must still suppress dispatch 7s after tick 2")
            .isEqualTo(2L);

        // Step past the second window's upper bound (10_000 * 1.2 = 12_000ms after tick 2;
        // tick 2 was at clock=6_500 → upper bound at 18_500). Set clock to 19_000.
        clock.set(19_000L);
        network.reconcileMissingPeersTick();
        assertThat(stub.lookups.getOrDefault(missingPeer, 0L))
            .as("after second window expires, third dispatch is permitted")
            .isEqualTo(3L);
    }

    @Test
    void reconcileMissingPeersTick_skipsPeerWhereSelfShouldNotInitiate() {
        // ConnectionDirection: only the lower NodeId initiates. Self has higher NodeId than
        // the missing peer here, so the reconciler must skip the dial — the peer accepts
        // inbound, so any reconnect must come from the lower-id side.
        var self = new NodeId("zzz-self");
        var lowerPeer = new NodeId("aaa-lower");
        var peerInfo = NodeInfo.nodeInfo(lowerPeer, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo));
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);

        network.reconcileMissingPeersTick();

        assertThat(stub.lookups.getOrDefault(lowerPeer, 0L))
            .as("higher-NodeId side must not initiate reconciler dial")
            .isEqualTo(0L);
    }

    @Test
    void reconcileMissingPeersTick_swimDepartedConfiguredPeer_isNotRedialed() {
        // Membership v2 §5.4: a configured core peer that the SWIM-fed authoritative
        // membership has dropped (absent from coreNodes()) must NOT be re-dialed by the
        // missing-peer reconciler — it is left for the leader's auto-heal. This is the
        // forever-redial wedge fix. Self is lower-id so it would otherwise initiate.
        var self = new NodeId("aaa-self");
        var departedPeer = new NodeId("zzz-departed");
        var peerInfo = NodeInfo.nodeInfo(departedPeer, addressOf("127.0.0.1", 1));
        // coreNodes() omits the departed peer (SWIM-departed) while topology() still lists it.
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        network.reconcileMissingPeersTick();

        assertThat(stub.lookups.getOrDefault(departedPeer, 0L))
            .as("SWIM-departed configured peer (absent from coreNodes) must not be re-dialed")
            .isEqualTo(0L);
    }

    @Test
    void reconcileMissingPeersTick_swimPresentButDisconnectedPeer_isRedialed() {
        // A SWIM-present peer (still in coreNodes()) that is merely QUIC-disconnected MUST
        // still be re-dialed — that is legitimate reconnection, distinct from a departure.
        var self = new NodeId("aaa-self");
        var presentPeer = new NodeId("zzz-present");
        var peerInfo = NodeInfo.nodeInfo(presentPeer, addressOf("127.0.0.1", 1));
        // coreNodes() includes the peer (SWIM still considers it present).
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, presentPeer));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        network.reconcileMissingPeersTick();

        assertThat(stub.lookups.getOrDefault(presentPeer, 0L))
            .as("SWIM-present but QUIC-disconnected peer must still be re-dialed (reconnection)")
            .isEqualTo(1L);
    }

    @Test
    void reconcileMissingPeersTick_coldStartNoSnapshot_freshlyDiscoveredPeerIsDialable() {
        // Cold start: no snapshot yet, so coreNodes() falls back to the locally-seeded
        // configured core set, which INCLUDES the configured peer. The reconciler must dial
        // it so formation can proceed (the gate must NOT block a not-yet-connected seed).
        var self = new NodeId("aaa-self");
        var seedPeer = new NodeId("zzz-seed");
        var peerInfo = NodeInfo.nodeInfo(seedPeer, addressOf("127.0.0.1", 1));
        // Cold-start coreNodes() fallback = all configured core members (self + seed).
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, seedPeer));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        network.reconcileMissingPeersTick();

        assertThat(stub.lookups.getOrDefault(seedPeer, 0L))
            .as("cold-start configured seed (present in coreNodes fallback) must be dialable")
            .isEqualTo(1L);
    }

    @Test
    void reconcileMissingPeersTick_removedPeerBackInCoreNodes_isReadmittedAndDialed() {
        // Transient-partition survivor: a peer driven to REMOVED (departurePermanent) that SWIM
        // has since RE-ADMITTED to the authoritative membership (incarnation supersede → back in
        // coreNodes()) must be reset REMOVED->INIT and re-dialed. Membership-convergence FSM §9.4:
        // only a strictly-higher incarnation is terminal; a same/refuted-incarnation peer rejoins.
        var self = new NodeId("aaa-self");
        var removedPeer = new NodeId("zzz-removed");
        var peerInfo = NodeInfo.nodeInfo(removedPeer, addressOf("127.0.0.1", 1));
        // coreNodes() INCLUDES the peer — SWIM re-admitted it after the tombstone was superseded.
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, removedPeer));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        // Seed the peer in the terminal REMOVED phase, as departurePermanent would have left it.
        var removedState = PeerState.peerState(removedPeer, 1L);
        removedState.authoritativeRemove(2L);
        network.seedPeerForTests(removedPeer, removedState);
        assertThat(removedState.phase()).isEqualTo(PeerState.Phase.REMOVED);

        network.reconcileMissingPeersTick();

        assertThat(removedState.phase())
            .as("REMOVED peer back in coreNodes() must be readmitted out of the terminal phase")
            .isNotEqualTo(PeerState.Phase.REMOVED);
        assertThat(stub.lookups.getOrDefault(removedPeer, 0L))
            .as("re-admitted peer must be re-dialed by the reconciler")
            .isEqualTo(1L);
    }

    @Test
    void reconcileMissingPeersTick_removedPeerAbsentFromCoreNodes_staysRemovedAndIsNotDialed() {
        // Genuine departure: a REMOVED peer that is NOT in coreNodes() (SWIM never re-admitted it,
        // no higher incarnation) is a permanent corpse. It must stay REMOVED and not be re-dialed —
        // left for the leader's auto-heal. This is the anti-resurrection guarantee at the dial side.
        var self = new NodeId("aaa-self");
        var departedPeer = new NodeId("zzz-departed");
        var peerInfo = NodeInfo.nodeInfo(departedPeer, addressOf("127.0.0.1", 1));
        // coreNodes() OMITS the departed peer (SWIM-departed, no incarnation supersede).
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        var removedState = PeerState.peerState(departedPeer, 1L);
        removedState.authoritativeRemove(2L);
        network.seedPeerForTests(departedPeer, removedState);

        network.reconcileMissingPeersTick();

        assertThat(removedState.phase())
            .as("REMOVED peer absent from coreNodes() must remain terminal (no resurrection)")
            .isEqualTo(PeerState.Phase.REMOVED);
        assertThat(stub.lookups.getOrDefault(departedPeer, 0L))
            .as("genuinely-departed REMOVED peer must not be re-dialed")
            .isEqualTo(0L);
    }

    private static NodeAddress addressOf(String host, int port) {
        return NodeAddress.nodeAddress(host, port).fold(_ -> fail("bad address"), a -> a);
    }

    private QuicClusterNetwork createNetwork(NodeId nodeId,
                                              List<NodeInfo> peers,
                                              MessageRouter router,
                                              TopologyObserver topology) {
        var network = new QuicClusterNetwork(topology, codec, codec, router, serverSsl, clientSsl,
                                              ClusterFormationConfig.defaults(), QuicDisconnectListener.noop());
        networks.add(network);
        network.startOnPort(0).await(AWAIT_TIMEOUT).onFailure(cause -> fail("start failed: " + cause.message()));
        return network;
    }

    private CountingTopology countingTopology(NodeId selfId, List<NodeInfo> peers) {
        var selfInfo = NodeInfo.nodeInfo(selfId, addressOf("127.0.0.1", 19998));
        return new CountingTopology(selfInfo, peers, false, Option.empty());
    }

    /// Variant that pins an explicit `coreNodes()` set — the SWIM-fed authoritative
    /// membership. A configured peer present in `topology()` but absent from this set is
    /// SWIM-departed and must not be re-dialed; a peer present in both is SWIM-present and
    /// must be re-dialed (reconnection). Used by the §5.4 dial-gate tests.
    private CountingTopology countingTopology(NodeId selfId, List<NodeInfo> peers, Set<NodeId> coreNodes) {
        var selfInfo = NodeInfo.nodeInfo(selfId, addressOf("127.0.0.1", 19998));
        return new CountingTopology(selfInfo, peers, false, Option.some(coreNodes));
    }

    /// Variant that lists peer NodeIds in `topology()` but returns `Option.empty()` from
    /// `get(NodeId)`. The reconciler counts the lookup attempt (proof that the backoff
    /// gate let the tick through) without actually invoking `connectPeer` — keeps the
    /// per-peer phase at INIT so backoff progression can be exercised across multiple
    /// ticks without async dial-failure noise.
    private CountingTopology countingTopologyWithoutInfo(NodeId selfId, List<NodeId> peerIds) {
        var selfInfo = NodeInfo.nodeInfo(selfId, addressOf("127.0.0.1", 19998));
        var peers = peerIds.stream().map(id -> NodeInfo.nodeInfo(id, addressOf("127.0.0.1", 1))).toList();
        return new CountingTopology(selfInfo, peers, true, Option.empty());
    }

    /// Stub topology that counts `get(NodeId)` invocations per peer. Used as the proxy for
    /// "reconciler dispatched a dial" — `considerPeerForReconcile` calls `topologyManager.get`
    /// only after the backoff gate allows the attempt.
    private static final class CountingTopology implements TopologyObserver {
        private final NodeInfo selfInfo;
        private final List<NodeInfo> peers;
        private final boolean returnEmptyOnGet;
        private final Option<Set<NodeId>> coreNodesOverride;
        private final Map<NodeId, Long> lookups = new ConcurrentHashMap<>();

        CountingTopology(NodeInfo selfInfo, List<NodeInfo> peers, boolean returnEmptyOnGet, Option<Set<NodeId>> coreNodesOverride) {
            this.selfInfo = selfInfo;
            this.peers = peers;
            this.returnEmptyOnGet = returnEmptyOnGet;
            this.coreNodesOverride = coreNodesOverride;
        }

        @Override public NodeInfo self() {return selfInfo;}
        @Override public Option<NodeInfo> get(NodeId id) {
            if (!id.equals(selfInfo.id())) {
                lookups.merge(id, 1L, Long::sum);
            }
            if (id.equals(selfInfo.id())) {return Option.some(selfInfo);}
            if (returnEmptyOnGet) {return Option.empty();}
            return peers.stream().filter(p -> p.id().equals(id)).findFirst().map(Option::some).orElse(Option.empty());
        }
        @Override public int clusterSize() {return Math.max(peers.size() + 1, 1);}
        @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
        @Override public Promise<Unit> start() {return Promise.unitPromise();}
        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
        @Override public TimeSpan pingInterval() {return PING_INTERVAL;}
        @Override public TimeSpan helloTimeout() {return HELLO_TIMEOUT;}
        @Override public Option<TlsConfig> tls() {return Option.empty();}
        @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}
        @Override public List<NodeId> topology() {
            var result = new ArrayList<NodeId>();
            result.add(selfInfo.id());
            peers.forEach(p -> result.add(p.id()));
            return result;
        }
        @Override public Set<NodeId> coreNodes() {
            return coreNodesOverride.or(() -> Set.copyOf(topology()));
        }
        @Override public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {}
        @Override public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {}
        @Override public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {}
        @Override public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {}
    }

    private static List<SliceCodec.TypeCodec<?>> combinedCodecs() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();
        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);
        return all;
    }
}
