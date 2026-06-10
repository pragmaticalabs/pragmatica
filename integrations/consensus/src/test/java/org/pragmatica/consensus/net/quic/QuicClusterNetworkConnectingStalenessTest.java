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
import org.pragmatica.consensus.net.NodeRole;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Verifies the CONNECTING-staleness escape hatch in [`QuicClusterNetwork`]'s missing-peer
/// reconciler. A dial that neither completes (`onPeerConnected`) nor fails (`onConnectFailed`)
/// — a hung `client.connect(...)` — pins the peer in CONNECTING forever. The in-flight dedup
/// guard then silently skips it on every tick, so it is never re-dialed and never re-counted as
/// CONNECTED (the `connectedPeerCount=3, expected >=4` wedge).
///
/// The reconciler force-evicts a peer that has been CONNECTING longer than
/// `helloTimeout * 3` (CONNECTING_STALENESS_HELLO_TIMEOUT_FACTOR) and re-dials it the same tick.
/// A peer freshly in CONNECTING (within the window) is left untouched so the dedup guard still
/// protects a genuinely in-flight dial.
@Timeout(10)
class QuicClusterNetworkConnectingStalenessTest {
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(5).seconds();
    private static final TimeSpan PING_INTERVAL = TimeSpan.timeSpan(1).seconds();
    // helloTimeout=5s → staleness window = 5s * 3 = 15s.
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
    void reconcileMissingPeersTick_peerStuckInConnectingBeyondStaleness_isEvictedAndReDialed() {
        // node-5 wedge reproduction: a dial that hung in CONNECTING long past the staleness
        // window. The reconciler must force-evict it (CONNECTING → EVICTED) and re-dial THIS tick.
        var self = new NodeId("aaa-self");
        var stuckPeer = new NodeId("zzz-stuck");
        var peerInfo = NodeInfo.nodeInfo(stuckPeer, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, stuckPeer));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, stub);
        network.overrideWallClockForTests(clock::get);

        // Seed the peer pinned in CONNECTING with a phase-entry timestamp far in the past,
        // so phaseAgeNanos exceeds the 15s staleness window deterministically.
        var stalePast = System.nanoTime() - Duration.ofMinutes(1).toNanos();
        var stuckState = PeerState.peerState(stuckPeer, stalePast);
        stuckState.beginConnecting(stalePast);
        network.seedPeerForTests(stuckPeer, stuckState);
        assertThat(stuckState.phase()).isEqualTo(PeerState.Phase.CONNECTING);

        network.reconcileMissingPeersTick();

        // The lookup count is the unambiguous proof: a peer still pinned in CONNECTING would have
        // short-circuited the dedup guard (0 lookups). A lookup of exactly 1 means the staleness
        // escape hatch fired (CONNECTING → EVICTED) AND the reconciler fell through to re-dial.
        // (The phase right after the tick is racy — the synchronous re-dial's beginConnecting may
        // already have flipped EVICTED back to CONNECTING — so we assert on the dial, not the phase.)
        assertThat(stub.lookups.getOrDefault(stuckPeer, 0L))
            .as("a peer stuck in CONNECTING beyond the staleness window must be force-evicted and re-dialed this tick")
            .isEqualTo(1L);
    }

    @Test
    void reconcileMissingPeersTick_peerFreshlyConnecting_isNotEvicted() {
        // The dedup guard must still protect a genuinely in-flight dial: a peer that entered
        // CONNECTING just now (well within the 15s staleness window) must NOT be evicted or
        // re-dialed — that would duplicate a live concurrent dial.
        var self = new NodeId("aaa-self");
        var dialingPeer = new NodeId("zzz-dialing");
        var peerInfo = NodeInfo.nodeInfo(dialingPeer, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, dialingPeer));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, stub);
        network.overrideWallClockForTests(clock::get);

        var now = System.nanoTime();
        var freshState = PeerState.peerState(dialingPeer, now);
        freshState.beginConnecting(now);
        network.seedPeerForTests(dialingPeer, freshState);
        assertThat(freshState.phase()).isEqualTo(PeerState.Phase.CONNECTING);

        network.reconcileMissingPeersTick();

        assertThat(freshState.phase())
            .as("a freshly-CONNECTING peer is a live in-flight dial and must stay CONNECTING")
            .isEqualTo(PeerState.Phase.CONNECTING);
        assertThat(stub.lookups.getOrDefault(dialingPeer, 0L))
            .as("the in-flight dedup guard must suppress a re-dial of a freshly-CONNECTING peer")
            .isEqualTo(0L);
    }

    // --- STEP 1: deferred + retrying DNS resolution at dial time ---

    @Test
    void reconcileMissingPeersTick_unresolvableHost_peerNotPinnedInConnectingAndStaysDialEligible() {
        // A configured peer whose hostname never resolves (RFC 6761 `.invalid` TLD). Deferred
        // resolution must fail cleanly WITHOUT pinning the peer in CONNECTING and WITHOUT marking it
        // REMOVED — the peer stays dial-eligible (EVICTED/ABSENT) so the next eligible tick re-attempts.
        // Lower-id self ("aaa-self") dials the higher-id peer ("zzz-unresolvable").
        var self = new NodeId("aaa-self");
        var unresolvable = new NodeId("zzz-unresolvable");
        var peerInfo = NodeInfo.nodeInfo(unresolvable, addressOf("no-such-host.invalid", 4242));
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, unresolvable));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, stub);
        network.overrideWallClockForTests(clock::get);

        network.reconcileMissingPeersTick();
        // Resolution is async on the Netty event loop — give the failure callback time to settle.
        awaitNotConnecting(network, unresolvable);

        var phase = network.peerPhaseForTests(unresolvable);
        assertThat(phase.map(p -> p == PeerState.Phase.CONNECTING).or(false))
            .as("an unresolvable peer must NOT be pinned in CONNECTING")
            .isFalse();
        assertThat(phase.map(p -> p == PeerState.Phase.REMOVED).or(false))
            .as("an unresolvable peer must NOT be marked REMOVED")
            .isFalse();
        assertThat(network.connectedPeers())
            .as("an unresolvable peer never connects")
            .doesNotContain(unresolvable);
    }

    @Test
    void reconcileMissingPeersTick_resolvableHost_reachesConnecting() {
        // A resolvable host (loopback) must resolve and dispatch the dial (beginConnecting runs in the
        // resolution-success continuation). The topology lookup proves the dial path was entered.
        var self = new NodeId("aaa-self");
        var resolvable = new NodeId("zzz-resolvable");
        var peerInfo = NodeInfo.nodeInfo(resolvable, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, resolvable));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, stub);
        network.overrideWallClockForTests(clock::get);

        network.reconcileMissingPeersTick();
        awaitLookup(stub, resolvable);

        assertThat(stub.lookups.getOrDefault(resolvable, 0L))
            .as("a resolvable peer must be dispatched for dial (topology lookup) at least once")
            .isGreaterThanOrEqualTo(1L);
    }

    private static void awaitNotConnecting(QuicClusterNetwork network, NodeId peerId) {
        var deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline
               && network.peerPhaseForTests(peerId).map(p -> p == PeerState.Phase.CONNECTING).or(false)) {
            Thread.onSpinWait();
        }
    }

    private static void awaitLookup(CountingTopology stub, NodeId peerId) {
        var deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline && stub.lookups.getOrDefault(peerId, 0L) == 0L) {
            Thread.onSpinWait();
        }
    }

    private static NodeAddress addressOf(String host, int port) {
        return NodeAddress.nodeAddress(host, port).fold(_ -> fail("bad address"), a -> a);
    }

    private QuicClusterNetwork createNetwork(NodeId nodeId, TopologyObserver topology) {
        var network = new QuicClusterNetwork(topology, codec, codec, MessageRouter.mutable(), serverSsl, clientSsl,
                                              ClusterFormationConfig.defaults(), QuicDisconnectListener.noop());
        networks.add(network);
        network.startOnPort(0).await(AWAIT_TIMEOUT).onFailure(cause -> fail("start failed: " + cause.message()));
        return network;
    }

    private CountingTopology countingTopology(NodeId selfId, List<NodeInfo> peers, Set<NodeId> coreNodes) {
        var selfInfo = NodeInfo.nodeInfo(selfId, addressOf("127.0.0.1", 19997));
        return new CountingTopology(selfInfo, peers, coreNodes);
    }

    /// Stub topology that counts `get(NodeId)` invocations per peer — the proxy for "reconciler
    /// dispatched a dial" (`reconcileDialPeer` calls `topologyManager.get` only after every gate,
    /// including the CONNECTING-staleness escape hatch, allows the attempt).
    private static final class CountingTopology implements TopologyObserver {
        private final NodeInfo selfInfo;
        private final List<NodeInfo> peers;
        private final Set<NodeId> coreNodes;
        private final Map<NodeId, Long> lookups = new ConcurrentHashMap<>();

        CountingTopology(NodeInfo selfInfo, List<NodeInfo> peers, Set<NodeId> coreNodes) {
            this.selfInfo = selfInfo;
            this.peers = peers;
            this.coreNodes = coreNodes;
        }

        @Override public NodeInfo self() {return selfInfo;}
        @Override public Option<NodeInfo> get(NodeId id) {
            if (!id.equals(selfInfo.id())) {
                lookups.merge(id, 1L, Long::sum);
            }
            if (id.equals(selfInfo.id())) {return Option.some(selfInfo);}
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
        @Override public Set<NodeId> coreNodes() {return coreNodes;}
        @Override public boolean isPassive(NodeId nodeId) {
            return peers.stream().anyMatch(p -> p.id().equals(nodeId) && p.role() == NodeRole.PASSIVE);
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
