/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.net.quic;

import io.netty.handler.codec.quic.QuicSslContext;
import org.pragmatica.consensus.NodeId;
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

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.fail;

/// Shared fixtures of the transport tripwire tests (cluster T assessment, rc4 `7c786db0d3`): real
/// [QuicClusterNetwork]s on loopback with an in-test topology. Nothing here changes behaviour — it only
/// builds what `QuicSimultaneousDialLaneTest` and `QuicClusterNetworkReconcilerTest` build privately.
final class TransportTripwireFixtures {
    static final TimeSpan AWAIT = TimeSpan.timeSpan(15).seconds();

    private TransportTripwireFixtures() {}

    static NodeAddress addressOf(String host, int port) {
        return NodeAddress.nodeAddress(host, port).fold(_ -> fail("bad address"), a -> a);
    }

    static QuicSslContext serverSsl(String name) {
        return QuicTlsProvider.serverContext(ClusterTestTls.clusterTls(name)).fold(_ -> fail("server ssl"), ssl -> ssl);
    }

    static QuicSslContext clientSsl(String name) {
        return QuicTlsProvider.clientContext(ClusterTestTls.clusterTls(name)).fold(_ -> fail("client ssl"), ssl -> ssl);
    }

    static NodeInfo infoOf(NodeId id, QuicClusterNetwork network) {
        var port = network.boundPort().fold(() -> fail("not bound"), bound -> bound);

        return NodeInfo.nodeInfo(id, addressOf("127.0.0.1", port));
    }

    static void awaitTrue(BooleanSupplier condition, TimeSpan bound, String what) {
        var deadline = System.nanoTime() + bound.nanos();

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        fail("Timed out waiting for: " + what);
    }

    /// True when the condition holds for the whole of `window` (polled), false the moment it breaks.
    static boolean holdsFor(BooleanSupplier condition, TimeSpan window) {
        var deadline = System.nanoTime() + window.nanos();

        while (System.nanoTime() < deadline) {
            if (!condition.getAsBoolean()) {
                return false;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }

        return true;
    }

    /// Starts recording every attach decision `network` reports (see `QuicClusterNetwork#attachObserverForTests`).
    static List<QuicClusterNetwork.AttachObservation> observeAttaches(QuicClusterNetwork network) {
        var seen = new CopyOnWriteArrayList<QuicClusterNetwork.AttachObservation>();

        network.attachObserverForTests(seen::add);

        return seen;
    }

    /// This end's own dial of a simultaneous pair is decided: it attached (superseded the inbound link, or lost to it as
    /// a duplicate), was abandoned because the inbound link made the peer CONNECTED first, or failed. Not visible in the
    /// peer's phase: the inbound link has already made it CONNECTED.
    static boolean ownDialDecided(QuicClusterNetwork network, List<QuicClusterNetwork.AttachObservation> attaches) {
        return attaches.stream().anyMatch(attach -> attach.origin().startsWith("dial attempt"))
               || network.quicMetrics().dialAbandonedCount() > 0
               || network.quicMetrics().snapshot().get("quic_dial_failures_total").longValue() > 0;
    }

    static boolean connected(QuicClusterNetwork network, NodeId peer) {
        return network.peerPhaseForTests(peer)
                      .filter(phase -> phase == PeerState.Phase.CONNECTED)
                      .isPresent();
    }

    /// Builds a started network. `peers` may be extended after start (ports are known only then).
    static QuicClusterNetwork startedNetwork(TripwireTopology topology, MessageRouter router, List<QuicClusterNetwork> registry) {
        var network = new QuicClusterNetwork(topology,
                                             LaneProbe.codec(),
                                             LaneProbe.codec(),
                                             router,
                                             serverSsl("tripwire-server"),
                                             clientSsl("tripwire-client"));

        registry.add(network);
        network.startOnPort(0).await(AWAIT).onFailure(cause -> fail("start failed: " + cause.message()));

        return network;
    }

    static PeerState evictedAfterConnected(NodeId peerId) {
        var state = PeerState.peerState(peerId, 1L);
        var channel = org.mockito.Mockito.mock(io.netty.handler.codec.quic.QuicChannel.class);

        org.mockito.Mockito.when(channel.isActive()).thenReturn(true);
        state.beginConnecting(2L);
        state.attach(QuicPeerConnection.quicPeerConnection(peerId, channel), 3L);
        state.evict(4L);

        return state;
    }

    /// Topology stub: a mutable peer list, a per-peer lookup counter (the reconciler's proxy for "a dial was
    /// dispatched": `get` is called only after the backoff gate lets an attempt through) and settable
    /// ping/hello intervals.
    static final class TripwireTopology implements TopologyObserver {
        final Map<NodeId, Long> lookups = new ConcurrentHashMap<>();
        private final NodeInfo selfInfo;
        private final List<NodeInfo> peers = new CopyOnWriteArrayList<>();
        private final boolean answerLookups;
        private final TimeSpan pingInterval;

        TripwireTopology(NodeId self, boolean answerLookups, TimeSpan pingInterval) {
            this.selfInfo = NodeInfo.nodeInfo(self, addressOf("127.0.0.1", 19994));
            this.answerLookups = answerLookups;
            this.pingInterval = pingInterval;
        }

        TripwireTopology addPeer(NodeInfo peer) {
            peers.add(peer);

            return this;
        }

        long lookupsOf(NodeId peer) {
            return lookups.getOrDefault(peer, 0L);
        }

        @Override public Unit setConsensusMembership(Predicate<NodeId> membership) {return Unit.unit();}
        @Override public NodeInfo self() {return selfInfo;}
        @Override public Option<NodeInfo> get(NodeId id) {
            if (id.equals(selfInfo.id())) {
                return Option.some(selfInfo);
            }
            lookups.merge(id, 1L, Long::sum);

            return answerLookups
                   ? peers.stream().filter(p -> p.id().equals(id)).findFirst().map(Option::some).orElse(Option.empty())
                   : Option.empty();
        }
        @Override public int clusterSize() {return Math.max(peers.size() + 1, 1);}
        @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
        @Override public Promise<Unit> start() {return Promise.unitPromise();}
        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
        @Override public TimeSpan pingInterval() {return pingInterval;}
        @Override public TimeSpan helloTimeout() {return TimeSpan.timeSpan(5).seconds();}
        @Override public Option<TlsConfig> tls() {return Option.empty();}
        @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}
        @Override public List<NodeId> topology() {
            var result = new ArrayList<NodeId>();

            result.add(selfInfo.id());
            peers.forEach(p -> result.add(p.id()));

            return result;
        }
        @Override public Set<NodeId> coreNodes() {return Set.copyOf(topology());}
        @Override public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {}
        @Override public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {}
        @Override public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {}
        @Override public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {}
    }
}
