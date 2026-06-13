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

import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.channel.DefaultChannelPromise;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// Fix A — INBOUND tombstone reversibility (the split-brain livelock killer). A peer driven to
/// terminal REMOVED by a false-FAULTY storm — specifically the LOWEST-id node, which nobody dials
/// by the single-dialer rule, so the dial-path readmit can never run for it — must be readmittable
/// on the INBOUND path when it presents PROOF OF LIFE (a completed authenticated Hello PLUS raw-SWIM
/// recent-aliveness). A peer WITHOUT proof-of-life (FAULTY/UNKNOWN in raw SWIM, absent from the
/// FSM-membership set) must STAY tombstoned (anti-resurrection for deliberate departures).
@Timeout(30)
class QuicClusterNetworkInboundReadmitTest {
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(10).seconds();
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
    void onPeerConnected_removedPeerWithSwimProofOfLife_readmittedAndAttached() {
        // Self is the LOWEST id (peer nobody dials it) — but here self is the survivor whose peer
        // reconnects inbound. The peer is FSM-tombstoned (absent from coreNodes()), so the
        // FSM-membership gate is FALSE; only the raw-SWIM liveness gate (proof-of-life) can readmit.
        var self = new NodeId("aaa-self");
        var peerId = new NodeId("zzz-peer");
        // coreNodes() OMITS the peer (FSM holds it terminal-DEAD), proving readmit rides liveness.
        var network = createNetwork(self, Set.of(self));
        // Raw-SWIM proof-of-life: the peer is HEALTHY at the SWIM layer (refuted its false suspicion).
        network.setSwimLivenessGate(id -> id.equals(peerId));

        var removed = PeerState.peerState(peerId, System.nanoTime());
        removed.authoritativeRemove(System.nanoTime());
        var seeded = network.seedPeerForTests(peerId, removed);
        assertThat(seeded.phase()).isEqualTo(PeerState.Phase.REMOVED);

        network.onPeerConnectedForTests(activeConnection(peerId), addressOf("127.0.0.1", 5000), Map.of());

        assertThat(seeded.phase())
            .as("REMOVED peer with raw-SWIM proof-of-life must be readmitted and attached on the inbound path")
            .isEqualTo(PeerState.Phase.CONNECTED);
        assertThat(network.tombstoneRejectionCountForTests(peerId))
            .as("a readmitted peer must not be counted as rejected")
            .isZero();
    }

    @Test
    void onPeerConnected_removedPeerWithoutProofOfLife_rejectedAndCounted() {
        // No proof of life: the peer is absent from coreNodes() (FSM-membership false) AND raw-SWIM
        // does not see it alive (liveness gate false) — a genuinely-departed / deliberately-removed
        // corpse. Its inbound connection must be REJECTED and the connection closed, NOT readmitted.
        var self = new NodeId("aaa-self");
        var peerId = new NodeId("zzz-peer");
        var network = createNetwork(self, Set.of(self));
        network.setSwimLivenessGate(_ -> false);

        var removed = PeerState.peerState(peerId, System.nanoTime());
        removed.authoritativeRemove(System.nanoTime());
        var seeded = network.seedPeerForTests(peerId, removed);

        network.onPeerConnectedForTests(activeConnection(peerId), addressOf("127.0.0.1", 5000), Map.of());

        assertThat(seeded.phase())
            .as("REMOVED peer WITHOUT proof-of-life must stay tombstoned (rejected, not readmitted)")
            .isEqualTo(PeerState.Phase.REMOVED);
        assertThat(network.tombstoneRejectionCountForTests(peerId))
            .as("Fix D.1: the rejection must be counted (the livelock storm is one-grep diagnosable)")
            .isEqualTo(1L);
    }

    @Test
    void onPeerConnected_deliberatelyDecommissionedPeer_notReadmitted() {
        // Deliberate decommission: SWIM emitted DepartedObserved → raw-SWIM no longer sees it alive,
        // AND it is absent from coreNodes(). Even though it (a drained-but-not-yet-halted node) CAN
        // still complete a Hello, the liveness gate returns false, so it stays tombstoned. This is
        // the discrimination decision: proof-of-life requires POSITIVE raw-SWIM aliveness, which a
        // deliberate departure clears.
        var self = new NodeId("aaa-self");
        var decommissioned = new NodeId("zzz-decommissioned");
        var network = createNetwork(self, Set.of(self));
        // Liveness gate reports NOT-alive for the decommissioned node (DepartedObserved cleared it).
        network.setSwimLivenessGate(_ -> false);

        var removed = PeerState.peerState(decommissioned, System.nanoTime());
        removed.authoritativeRemove(System.nanoTime());
        var seeded = network.seedPeerForTests(decommissioned, removed);

        network.onPeerConnectedForTests(activeConnection(decommissioned), addressOf("127.0.0.1", 5000), Map.of());

        assertThat(seeded.phase())
            .as("deliberately-decommissioned peer (not SWIM-alive) must NOT be readmitted")
            .isEqualTo(PeerState.Phase.REMOVED);
    }

    @Test
    void onPeerConnected_repeatedRejections_incrementTombstoneCounter() {
        // The #185 livelock: a tombstoned peer re-dials every reconcile window and is killed each
        // time. The Fix D.1 counter must accumulate across rejections so the storm is visible.
        var self = new NodeId("aaa-self");
        var peerId = new NodeId("zzz-peer");
        var network = createNetwork(self, Set.of(self));
        network.setSwimLivenessGate(_ -> false);

        var removed = PeerState.peerState(peerId, System.nanoTime());
        removed.authoritativeRemove(System.nanoTime());
        network.seedPeerForTests(peerId, removed);

        network.onPeerConnectedForTests(activeConnection(peerId), addressOf("127.0.0.1", 5000), Map.of());
        network.onPeerConnectedForTests(activeConnection(peerId), addressOf("127.0.0.1", 5000), Map.of());
        network.onPeerConnectedForTests(activeConnection(peerId), addressOf("127.0.0.1", 5000), Map.of());

        assertThat(network.tombstoneRejectionCountForTests(peerId))
            .as("rejection counter must accumulate across repeated tombstone rejections")
            .isEqualTo(3L);
    }

    @Test
    void onPeerConnected_removedPeerBackInCoreNodes_readmittedViaFsmMembershipEvenWithoutLivenessGate() {
        // The legacy authority still works: a peer SWIM re-admitted to coreNodes() (incarnation
        // supersede) is readmitted on the inbound path even with NO liveness gate wired — the OR
        // of the two authorities preserves the existing dial-path behaviour on the inbound path.
        var self = new NodeId("aaa-self");
        var peerId = new NodeId("zzz-peer");
        // coreNodes() INCLUDES the peer — FSM-membership authority is true.
        var network = createNetwork(self, Set.of(self, peerId));

        var removed = PeerState.peerState(peerId, System.nanoTime());
        removed.authoritativeRemove(System.nanoTime());
        var seeded = network.seedPeerForTests(peerId, removed);

        network.onPeerConnectedForTests(activeConnection(peerId), addressOf("127.0.0.1", 5000), Map.of());

        assertThat(seeded.phase())
            .as("REMOVED peer back in coreNodes() must be readmitted via the FSM-membership authority")
            .isEqualTo(PeerState.Phase.CONNECTED);
    }

    // -- P3: both-corroboration-dead readmit guarded by tombstone age (drain grace floor) --

    private static final long GRACE_FLOOR_NANOS = 30_000L * 1_000_000L; // mirrors TOMBSTONE_READMIT_GRACE_FLOOR_MS

    @Test
    void onPeerConnected_bothSourcesDead_tombstoneOlderThanGrace_readmittedViaAuthenticatedHello() {
        // P3: both corroboration sources FALSE (absent from coreNodes(), raw-SWIM not alive — its
        // SWIM/FSM evidence is unrecoverable, the S06 both-sources-dead wedge), BUT the tombstone is
        // OLDER than the drain grace-terminate window. A deliberately-drained node halts within the
        // grace window and cannot handshake after it, so an authenticated Hello past the floor is a
        // false-DEAD survivor — accept it (the authenticated Hello itself is proof-of-life).
        var self = new NodeId("aaa-self");
        var peerId = new NodeId("zzz-peer");
        var network = createNetwork(self, Set.of(self));
        network.setSwimLivenessGate(_ -> false);

        // Tombstone stamped 35s in the past (> 30s grace floor) → deterministically past grace.
        var staleNanos = System.nanoTime() - (GRACE_FLOOR_NANOS + 5_000L * 1_000_000L);
        var removed = PeerState.peerState(peerId, staleNanos);
        removed.authoritativeRemove(staleNanos);
        var seeded = network.seedPeerForTests(peerId, removed);
        assertThat(seeded.phase()).isEqualTo(PeerState.Phase.REMOVED);

        network.onPeerConnectedForTests(activeConnection(peerId), addressOf("127.0.0.1", 5000), Map.of());

        assertThat(seeded.phase())
            .as("both-sources-dead REMOVED peer past the tombstone grace floor must be readmitted via authenticated Hello")
            .isEqualTo(PeerState.Phase.CONNECTED);
        assertThat(network.tombstoneRejectionCountForTests(peerId))
            .as("a readmitted peer must not retain a rejection count")
            .isZero();
    }

    @Test
    void onPeerConnected_bothSourcesDead_tombstoneYoungerThanGrace_rejected() {
        // P3 boundary: both corroboration sources FALSE and the tombstone is YOUNGER than the grace
        // floor — this is exactly the deliberate-drain window, so the DENY stands (a drained node
        // halting mid-window must not be resurrected by its in-flight handshake).
        var self = new NodeId("aaa-self");
        var peerId = new NodeId("zzz-peer");
        var network = createNetwork(self, Set.of(self));
        network.setSwimLivenessGate(_ -> false);

        // Fresh tombstone (age ≈ 0 < 30s grace floor).
        var removed = PeerState.peerState(peerId, System.nanoTime());
        removed.authoritativeRemove(System.nanoTime());
        var seeded = network.seedPeerForTests(peerId, removed);

        network.onPeerConnectedForTests(activeConnection(peerId), addressOf("127.0.0.1", 5000), Map.of());

        assertThat(seeded.phase())
            .as("both-sources-dead REMOVED peer WITHIN the tombstone grace window must stay tombstoned")
            .isEqualTo(PeerState.Phase.REMOVED);
        assertThat(network.tombstoneRejectionCountForTests(peerId))
            .as("the within-grace rejection must be counted")
            .isEqualTo(1L);
    }

    @Test
    void onPeerConnected_swimAlive_readmittedImmediately_evenWithFreshTombstone() {
        // P3 must not slow the existing fast paths: a SWIM-alive peer is readmitted IMMEDIATELY
        // regardless of tombstone age (no grace wait).
        var self = new NodeId("aaa-self");
        var peerId = new NodeId("zzz-peer");
        var network = createNetwork(self, Set.of(self));
        network.setSwimLivenessGate(id -> id.equals(peerId));

        // Fresh tombstone — would be REJECTED by the P3 age path, but SWIM-alive fast path wins.
        var removed = PeerState.peerState(peerId, System.nanoTime());
        removed.authoritativeRemove(System.nanoTime());
        var seeded = network.seedPeerForTests(peerId, removed);

        network.onPeerConnectedForTests(activeConnection(peerId), addressOf("127.0.0.1", 5000), Map.of());

        assertThat(seeded.phase())
            .as("SWIM-alive fast path readmits immediately even with a fresh tombstone (no grace wait)")
            .isEqualTo(PeerState.Phase.CONNECTED);
    }

    @Test
    void onPeerConnected_fsmMembership_readmittedImmediately_evenWithFreshTombstone() {
        // P3 must not slow the existing fast paths: an FSM-membership-readmitted peer is readmitted
        // IMMEDIATELY regardless of tombstone age (no grace wait).
        var self = new NodeId("aaa-self");
        var peerId = new NodeId("zzz-peer");
        // coreNodes() INCLUDES the peer — FSM-membership authority is true.
        var network = createNetwork(self, Set.of(self, peerId));

        var removed = PeerState.peerState(peerId, System.nanoTime());
        removed.authoritativeRemove(System.nanoTime());
        var seeded = network.seedPeerForTests(peerId, removed);

        network.onPeerConnectedForTests(activeConnection(peerId), addressOf("127.0.0.1", 5000), Map.of());

        assertThat(seeded.phase())
            .as("FSM-membership fast path readmits immediately even with a fresh tombstone (no grace wait)")
            .isEqualTo(PeerState.Phase.CONNECTED);
    }

    @Test
    void tombstonePastGraceFloor_boundary() {
        // Pure age predicate boundary (the deterministic core of the P3 grace decision).
        assertThat(QuicClusterNetwork.tombstonePastGraceFloor(GRACE_FLOOR_NANOS - 1))
            .as("just under the grace floor → DENY")
            .isFalse();
        assertThat(QuicClusterNetwork.tombstonePastGraceFloor(GRACE_FLOOR_NANOS))
            .as("exactly at the grace floor → ACCEPT")
            .isTrue();
        assertThat(QuicClusterNetwork.tombstonePastGraceFloor(GRACE_FLOOR_NANOS + 1))
            .as("past the grace floor → ACCEPT")
            .isTrue();
    }

    private QuicPeerConnection activeConnection(NodeId peerId) {
        var chan = mock(QuicChannel.class);
        when(chan.isActive()).thenReturn(true);
        // The successful-attach path registers a close listener on the channel's closeFuture;
        // stub it so the mock returns a real (never-completing) promise instead of null.
        when(chan.closeFuture()).thenReturn(new DefaultChannelPromise(chan, ImmediateEventExecutor.INSTANCE));
        return QuicPeerConnection.quicPeerConnection(peerId, chan);
    }

    private static NodeAddress addressOf(String host, int port) {
        return NodeAddress.nodeAddress(host, port).fold(_ -> fail("bad address"), a -> a);
    }

    private QuicClusterNetwork createNetwork(NodeId selfId, Set<NodeId> coreNodes) {
        var selfInfo = NodeInfo.nodeInfo(selfId, addressOf("127.0.0.1", 19998));
        var topology = new OverridableTopology(selfInfo, coreNodes);
        var network = new QuicClusterNetwork(topology, codec, codec, MessageRouter.mutable(),
                                             serverSsl, clientSsl);
        networks.add(network);
        network.startOnPort(0).await(AWAIT_TIMEOUT).onFailure(cause -> fail("start failed: " + cause.message()));
        return network;
    }

    /// Topology stub with an explicit `coreNodes()` override so a tombstoned peer can be made
    /// absent from the FSM-membership set (forcing the readmit to ride the raw-SWIM liveness gate).
    private static final class OverridableTopology implements TopologyObserver {
        private final NodeInfo selfInfo;
        private final Set<NodeId> coreNodes;

        OverridableTopology(NodeInfo selfInfo, Set<NodeId> coreNodes) {
            this.selfInfo = selfInfo;
            this.coreNodes = coreNodes;
        }

        @Override public NodeInfo self() {return selfInfo;}
        @Override public Option<NodeInfo> get(NodeId id) {
            return id.equals(selfInfo.id()) ? Option.some(selfInfo) : Option.empty();
        }
        @Override public int clusterSize() {return Math.max(coreNodes.size(), 1);}
        @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
        @Override public Promise<Unit> start() {return Promise.unitPromise();}
        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
        @Override public TimeSpan pingInterval() {return PING_INTERVAL;}
        @Override public TimeSpan helloTimeout() {return HELLO_TIMEOUT;}
        @Override public Option<TlsConfig> tls() {return Option.empty();}
        @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}
        @Override public List<NodeId> topology() {return List.copyOf(coreNodes);}
        @Override public Set<NodeId> coreNodes() {return coreNodes;}
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
