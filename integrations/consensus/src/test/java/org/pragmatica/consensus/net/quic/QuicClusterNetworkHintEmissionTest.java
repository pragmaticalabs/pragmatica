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
import io.netty.handler.codec.quic.QuicChannel;
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
import org.pragmatica.consensus.topology.TransportObservation;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/// Verifies that `QuicClusterNetwork` invokes the injected `QuicDisconnectListener`
/// on every peer-removal view-change, alongside the existing
/// `TransportObservation.PeerDisconnected` emission.
///
/// The contract is pinned here with a listener this test injects itself. No production caller
/// installs one — `setDisconnectListener` has no call sites — so what this class guards is the
/// transport's side of the contract, kept honest for whoever wires a consumer next.
@Timeout(10)
class QuicClusterNetworkHintEmissionTest {
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
    void disconnect_unknownPeer_propagatesListenerForTopologyRemoval() {
        // SWIM-driven DisconnectNode is the authoritative "this peer is gone" signal —
        // even if we never had a live QUIC link, the REMOVE view-change must fire so
        // topology and HealthReconciler see the departure. Otherwise peers whose
        // connection tore down before lifecycle promotion stay in coreNodes forever.
        var captured = new CopyOnWriteArrayList<NodeId>();
        QuicDisconnectListener listener = captured::add;
        var network = createNetworkWithListener(NodeId.randomNodeId(), List.of(), MessageRouter.mutable(), listener);

        var missing = new NodeId("missing");
        network.disconnect(new NetworkServiceMessage.DisconnectNode(missing));

        assertThat(captured).as("REMOVE view-change fires listener even without a prior QUIC link").containsExactly(missing);
    }

    @Test
    void disconnect_followerPath_buffersConnectivityObservation_skipsDisconnectListener() {
        // Commit 2 (ClusterSync refactor): on a follower node, REMOVE view-changes
        // must NOT invoke the disconnect listener (which would feed the local
        // HealthReconciler). Instead, a PeerConnectivityObservation is pushed to the
        // upstream buffer via the PeerConnectivityReporter so the leader folds it.
        var listenerInvocations = new CopyOnWriteArrayList<NodeId>();
        QuicDisconnectListener listener = listenerInvocations::add;
        var reported = new CopyOnWriteArrayList<ReportedDisconnect>();
        PeerConnectivityReporter reporter = new PeerConnectivityReporter() {
            @Override public void onPeerDisconnected(NodeId peerId, long term, long counter, boolean deathPathInitiated) {
                reported.add(new ReportedDisconnect(peerId, term, counter));
            }
            @Override public void onPeerConnected(NodeId peerId, long term, long counter) {
                // No-op for this test: the disconnect-path assertions don't depend on
                // connection events.
            }
        };
        QuicClusterNetwork.ObservedEpochSupplier epoch = new QuicClusterNetwork.ObservedEpochSupplier() {
            @Override public long term() {return 11L;}
            @Override public long counter() {return 4L;}
        };
        var network = createNetworkWithListener(NodeId.randomNodeId(), List.of(), MessageRouter.mutable(), listener);
        network.setFollowerObservationWiring(() -> false, reporter, epoch);

        var missing = new NodeId("missing");
        network.disconnect(new NetworkServiceMessage.DisconnectNode(missing));

        assertThat(listenerInvocations).as("follower must NOT invoke local disconnect listener").isEmpty();
        assertThat(reported).as("follower pushes PeerConnectivityObservation upstream")
                            .containsExactly(new ReportedDisconnect(missing, 11L, 4L));
    }

    @Test
    void disconnect_leaderPath_invokesBothDisconnectListenerAndReporter() {
        // Topology-observation refactor Step 4: leader MUST also fire the connectivity
        // reporter on QUIC drops so the AetherNode-level adapter can ingest the
        // observation synchronously into ReachabilityAggregator. The disconnect listener
        // (HealthReconciler fast path) still fires too — these consumers are
        // complementary, not exclusive.
        var listenerInvocations = new CopyOnWriteArrayList<NodeId>();
        QuicDisconnectListener listener = listenerInvocations::add;
        var reported = new CopyOnWriteArrayList<ReportedDisconnect>();
        PeerConnectivityReporter reporter = new PeerConnectivityReporter() {
            @Override public void onPeerDisconnected(NodeId peerId, long term, long counter, boolean deathPathInitiated) {
                reported.add(new ReportedDisconnect(peerId, term, counter));
            }
            @Override public void onPeerConnected(NodeId peerId, long term, long counter) {
                // No-op for this test.
            }
        };
        QuicClusterNetwork.ObservedEpochSupplier epoch = new QuicClusterNetwork.ObservedEpochSupplier() {
            @Override public long term() {return 7L;}
            @Override public long counter() {return 2L;}
        };
        var network = createNetworkWithListener(NodeId.randomNodeId(), List.of(), MessageRouter.mutable(), listener);
        network.setFollowerObservationWiring(() -> true, reporter, epoch);

        var missing = new NodeId("missing");
        network.disconnect(new NetworkServiceMessage.DisconnectNode(missing));

        assertThat(listenerInvocations).as("leader disconnect listener fires")
                                       .containsExactly(missing);
        assertThat(reported).as("leader also emits via reporter for direct aggregator ingest")
                            .containsExactly(new ReportedDisconnect(missing, 7L, 2L));
    }

    private record ReportedDisconnect(NodeId peerId, long term, long counter) {}

    @Test
    void evictionBackoff_suppressesRapidReconnects_underDeterministicClock() {
        // 5 evict events fired in quick succession under a deterministic clock must NOT
        // produce 5 reconnect attempts. The first call seeds a `nextAttemptMs` of
        // `now + jitter(100ms)`; calls 2..5 within the same logical instant fall before
        // that deadline and must be suppressed. Lower bound: jitter floor is 50ms, so at
        // least the first attempt is allowed.
        var clock = new AtomicLong(1_000_000L);
        var network = createNetworkWithListener(NodeId.randomNodeId(), List.of(), MessageRouter.mutable(),
                                                 QuicDisconnectListener.noop());
        network.overrideWallClockForTests(clock::get);

        var peer = new NodeId("peer-bouncing");
        var allowed = 0;
        for (int i = 0; i < 5; i++) {
            if (network.backoffAllowsReconnect(peer)) {
                allowed++;
            }
            // Advance 1ms — well below the 100ms initial floor — to simulate the 1Hz
            // consensus heartbeat hammering the same peer in tight succession during a
            // single test tick.
            clock.addAndGet(1L);
        }
        assertThat(allowed)
            .as("backoff must suppress at least one of the 5 close-spaced eviction reconnects")
            .isLessThan(5)
            .isGreaterThanOrEqualTo(1);

        // Advance well past the upper jitter bound (BACKOFF_INITIAL_MS * 1.5 = 150ms,
        // doubled = 300ms, jittered to at most 450ms). After 1s the next attempt must
        // be allowed.
        clock.addAndGet(1_000L);
        assertThat(network.backoffAllowsReconnect(peer))
            .as("after the backoff window expires, reconnect is allowed again")
            .isTrue();
    }

    @Test
    void evictionBackoff_doublesDelayOnRepeatedEviction() {
        // Drive the backoff machine through three evictions and assert each successive
        // suppression window is at least 2x the previous one (the lower jitter bound is
        // 0.5x, so 2x growth is the worst-case lower bound for two consecutive doubles
        // *on average*, but for a single sample we assert the deadline grows monotonically
        // and exceeds the BACKOFF_INITIAL_MS lower jitter floor on the first window).
        var clock = new AtomicLong(0L);
        var network = createNetworkWithListener(NodeId.randomNodeId(), List.of(), MessageRouter.mutable(),
                                                 QuicDisconnectListener.noop());
        network.overrideWallClockForTests(clock::get);
        var peer = new NodeId("peer-flap");

        // Attempt 1 — first call seeds the backoff with `currentDelay = 100ms`.
        assertThat(network.backoffAllowsReconnect(peer)).isTrue();
        // Step past the (jittered) first window. Upper jitter on 100ms = 150ms.
        clock.addAndGet(200L);

        // Attempt 2 — allowed; doubles `currentDelay` to 200ms internally.
        assertThat(network.backoffAllowsReconnect(peer)).isTrue();
        // Probe right after attempt 2 — still inside the 200ms*0.5 = 100ms minimum window.
        var probedAt = clock.get();
        clock.addAndGet(50L);
        assertThat(network.backoffAllowsReconnect(peer))
            .as("second window must still be active 50ms after the doubled attempt at " + probedAt)
            .isFalse();

        // Step past the 200ms*1.5 = 300ms upper bound, then attempt 3.
        clock.addAndGet(400L);
        assertThat(network.backoffAllowsReconnect(peer)).isTrue();
        // Probe inside the doubled-again 400ms*0.5 = 200ms minimum window.
        clock.addAndGet(100L);
        assertThat(network.backoffAllowsReconnect(peer))
            .as("third window (>=200ms after attempt 3) must still suppress reconnect")
            .isFalse();
    }

    /// R5 (spec §4.1, §6 Signal Catalog): the `QuicPeerStateListener` is the canonical
    /// upward channel from QUIC transport. Aether wiring forwards listener events into
    /// `swimDetector.recordTransportHint(...)`. This test verifies the listener fires on
    /// peer-left events emitted by the transport.
    @Test
    void quicTransport_peerConnectionLost_invokesOnPeerLeft() {
        var leftCalls = new CopyOnWriteArrayList<NodeId>();
        var joinedCalls = new CopyOnWriteArrayList<NodeId>();
        var reconnectedCalls = new CopyOnWriteArrayList<NodeId>();
        QuicPeerStateListener listener = new QuicPeerStateListener() {
            @Override public void onPeerJoined(NodeId nodeId) { joinedCalls.add(nodeId); }
            @Override public void onPeerReconnected(NodeId nodeId) { reconnectedCalls.add(nodeId); }
            @Override public void onPeerLeft(NodeId nodeId) { leftCalls.add(nodeId); }
        };
        var network = createNetworkWithListener(NodeId.randomNodeId(),
                                                 List.of(),
                                                 MessageRouter.mutable(),
                                                 QuicDisconnectListener.noop());
        network.setPeerStateListener(listener);

        var missing = new NodeId("departed-peer");
        network.disconnect(new NetworkServiceMessage.DisconnectNode(missing));

        assertThat(leftCalls)
            .as("R5: REMOVE view-change must invoke onPeerLeft (TransportObservation hint to SWIM)")
            .containsExactly(missing);
        assertThat(joinedCalls).as("disconnect must not synthesize a join").isEmpty();
        assertThat(reconnectedCalls).as("disconnect must not synthesize a reconnect").isEmpty();
    }

    /// R5: the peer-state listener is the only authoritative upward signal from QUIC
    /// — no `MembershipDecision` edges are needed for SWIM, and no emission of
    /// removed-API mutations happens. The compile-time absence of `topologyManager
    /// .unregisterPeer/markDeparted` enforces this; this test additionally confirms the
    /// runtime path doesn't accidentally invoke a no-op shim.
    @Test
    void quicTransport_remove_routesPeerDisconnectedObservation() {
        // QUIC emits `TransportObservation.PeerDisconnected` synchronously on transport REMOVE.
        // This is the local-fast-path stream consumed by LeaderManager / ClusterFsmRouter for the
        // bootstrap window before consensus exists. `TopologyObserver` independently publishes
        // `MembershipDecision.NodeRemoved` once the consensus-committed snapshot re-projects,
        // for canonical-decision consumers (CDM, CTM, LB, etc.). The two streams are
        // type-distinct and consumed by disjoint subscribers.
        var disconnected = new CopyOnWriteArrayList<NodeId>();
        var router = MessageRouter.mutable();
        router.addRoute(TransportObservation.PeerDisconnected.class,
                        n -> disconnected.add(n.nodeId()));

        var network = createNetworkWithListener(NodeId.randomNodeId(),
                                                 List.of(),
                                                 router,
                                                 QuicDisconnectListener.noop());

        var missing = new NodeId("departed-peer");
        network.disconnect(new NetworkServiceMessage.DisconnectNode(missing));

        assertThat(disconnected)
            .as("REMOVE emits an informational PeerDisconnected observation (advisory, not authoritative)")
            .containsExactly(missing);
    }

    @Test
    void disconnect_secondCallOnEvictedPeer_doesNotReEmitRemove() {
        // REMOVE-emission idempotency regression: five independent producers (SWIM departure,
        // health reconciler, topology pruning) can call disconnect() for the same peer in a
        // tight window. Production logs showed one peer emitting 88 REMOVE observations in 11s.
        // The FIRST disconnect on a CONNECTED peer evicts it (CONNECTED → EVICTED) and routes
        // exactly one PeerDisconnected. The SECOND disconnect finds the peer already EVICTED —
        // evict() is a no-op (returns Empty) — so NO second observation must be routed.
        var disconnected = new CopyOnWriteArrayList<NodeId>();
        var router = MessageRouter.mutable();
        router.addRoute(TransportObservation.PeerDisconnected.class, n -> disconnected.add(n.nodeId()));
        var network = createNetworkWithListener(NodeId.randomNodeId(), List.of(), router, QuicDisconnectListener.noop());

        var peerId = new NodeId("flapping-peer");
        network.seedPeerForTests(peerId, connectedPeerState(peerId));

        network.disconnect(new NetworkServiceMessage.DisconnectNode(peerId));
        network.disconnect(new NetworkServiceMessage.DisconnectNode(peerId));
        network.disconnect(new NetworkServiceMessage.DisconnectNode(peerId));

        assertThat(disconnected)
            .as("repeated disconnect() on an already-EVICTED peer must emit REMOVE only once")
            .containsExactly(peerId);
    }

    @Test
    void departurePermanent_secondCallOnRemovedPeer_isNoopEmission() {
        // departurePermanent idempotency: the first call transitions the peer to REMOVED in
        // place (the entry stays RESIDENT so re-dial paths key off it), routing one
        // PeerDisconnected. Subsequent calls find the peer already REMOVED and must route
        // nothing — no duplicate authoritative-departure observation floods
        // ReachabilityAggregator/SWIM.
        var disconnected = new CopyOnWriteArrayList<NodeId>();
        var router = MessageRouter.mutable();
        router.addRoute(TransportObservation.PeerDisconnected.class, n -> disconnected.add(n.nodeId()));
        var network = createNetworkWithListener(NodeId.randomNodeId(), List.of(), router, QuicDisconnectListener.noop());

        var peerId = new NodeId("departing-peer");
        network.seedPeerForTests(peerId, connectedPeerState(peerId));

        network.departurePermanent(peerId);
        network.departurePermanent(peerId);
        network.departurePermanent(peerId);

        assertThat(disconnected)
            .as("repeated departurePermanent() for the same peer must emit REMOVE only once")
            .containsExactly(peerId);
    }

    private static PeerState connectedPeerState(NodeId peerId) {
        var chan = mock(QuicChannel.class);
        when(chan.isActive()).thenReturn(true);
        // Stamp the connection well in the past so it sits outside the disconnect()
        // protection window (helloTimeout * 3 = 15s); a freshly-attached link would be
        // shielded by the protection early-return and never reach the evict path.
        var past = System.nanoTime() - Duration.ofMinutes(1).toNanos();
        var state = PeerState.peerState(peerId, past);
        state.beginConnecting(past);
        state.attach(QuicPeerConnection.quicPeerConnection(peerId, chan), past);
        return state;
    }

    @Test
    void defaultConstructor_usesNoopListener_withoutCrashing() {
        var nodeId = NodeId.randomNodeId();
        var address = NodeAddress.nodeAddress("127.0.0.1", 19999).fold(_ -> fail("bad address"), a -> a);
        var selfInfo = NodeInfo.nodeInfo(nodeId, address);
        var topology = stubTopologyManager(selfInfo, List.of());
        var network = new QuicClusterNetwork(topology, codec, codec, MessageRouter.mutable(),
                                              serverSsl, clientSsl);
        networks.add(network);

        network.startOnPort(0).await(AWAIT_TIMEOUT).onFailure(cause -> fail("start failed: " + cause.message()));
        network.disconnect(new NetworkServiceMessage.DisconnectNode(new NodeId("missing")));
    }

    private QuicClusterNetwork createNetworkWithListener(NodeId nodeId,
                                                          List<NodeInfo> peers,
                                                          MessageRouter router,
                                                          QuicDisconnectListener listener) {
        var address = NodeAddress.nodeAddress("127.0.0.1", 19999).fold(_ -> fail("bad address"), a -> a);
        var selfInfo = NodeInfo.nodeInfo(nodeId, address);
        var topology = stubTopologyManager(selfInfo, peers);
        var network = new QuicClusterNetwork(topology, codec, codec, router, serverSsl, clientSsl,
                                              ClusterFormationConfig.defaults(), listener);
        networks.add(network);
        network.startOnPort(0).await(AWAIT_TIMEOUT).onFailure(cause -> fail("start failed: " + cause.message()));
        return network;
    }

    private TopologyObserver stubTopologyManager(NodeInfo self, List<NodeInfo> peers) {
        return new TopologyObserver() {
            @Override public NodeInfo self() {return self;}
            @Override public Option<NodeInfo> get(NodeId id) {
                if (id.equals(self.id())) {return Option.some(self);}
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
