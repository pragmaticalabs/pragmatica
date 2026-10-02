// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage.Broadcast;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.ListConnectedNodes;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.DHTNetwork;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.dht.DHTRebalancer;
import org.pragmatica.dht.DeparturePushObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.Server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// Pins the wiring that carries the leader's co-drain set to the DHT departure push (#1818): the
/// collector that received the drain ping, and the rebalancer that pushes, are joined only by
/// [DhtDeparturePush] — the piece `AetherNode` assembles. Two holders of one key drain in the same
/// ping; the push must stock BOTH slots they vacate, which it can do only if it sees the co-drainer.
class DhtDeparturePushTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, DHTConfig.DEFAULT_TIMEOUT);
    private static final NodeId SELF = new NodeId("node-0");
    private static final NodeId CO_DRAINER = new NodeId("node-1");
    private static final NodeId LEADER = new NodeId("node-6");
    private static final List<NodeId> RING_MEMBERS = IntStream.range(0, 7)
                                                              .mapToObj(i -> new NodeId("node-" + i))
                                                              .toList();

    @Test
    void departurePush_seesTheCoDrainerFromTheDrainPing_andStocksBothVacatedSlots() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        RING_MEMBERS.forEach(ring::addNode);
        var key = keyHeldByBoth(ring);
        var pushedTo = new CopyOnWriteArrayList<NodeId>();
        var node = DHTNode.dhtNode(SELF, memoryStorageEngine(), ring, CONFIG);
        var rebalancer = DHTRebalancer.dhtRebalancer(node, recordingNetwork(pushedTo), CONFIG);
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new SilentClusterNetwork());

        node.putLocal(key, "payload".getBytes(StandardCharsets.UTF_8)).await();
        collector.setMetricsProducerEligibility(_ -> true);
        collector.setPingAuthority(LEADER::equals, LEADER::equals);
        collector.onClusterSyncPing(drainPing(Set.of(SELF, CO_DRAINER)));

        DhtDeparturePush.dhtDeparturePush(rebalancer, collector, DeparturePushObserver::noop).get();

        var vacatedSlots = ring.nodesFor(key, 3, id -> !id.equals(SELF) && !id.equals(CO_DRAINER))
                               .stream()
                               .filter(id -> !ring.nodesFor(key, 3).contains(id))
                               .toList();

        assertThat(vacatedSlots).as("control: both drainers held the key, so two slots open").hasSize(2);
        assertThat(pushedTo).as("the push reached every node that newly owns the key").containsExactlyInAnyOrderElementsOf(vacatedSlots);
    }

    private static byte[] keyHeldByBoth(ConsistentHashRing<NodeId> ring) {
        return IntStream.range(0, 20_000)
                        .mapToObj(i -> ("wiring-" + i).getBytes(StandardCharsets.UTF_8))
                        .filter(candidate -> ring.nodesFor(candidate, 3).containsAll(List.of(SELF, CO_DRAINER)))
                        .findFirst()
                        .orElseThrow();
    }

    /// Records each departure-push target. The pushes are never acked; the test reads the targets only.
    private static DHTNetwork recordingNetwork(List<NodeId> pushedTo) {
        return (target, message) -> recordPush(pushedTo, target, message);
    }

    private static void recordPush(List<NodeId> pushedTo, NodeId target, ProtocolMessage message) {
        if (message instanceof DHTMessage.MigrationDataResponse) {
            pushedTo.add(target);
        }
    }

    private static ClusterSyncPing drainPing(Set<NodeId> drainNodes) {
        return new ClusterSyncPing(LEADER,
                                   Map.of(),
                                   0L,
                                   0L,
                                   0L,
                                   0L,
                                   Set.of(),
                                   drainNodes,
                                   Map.of(),
                                   Set.of(),
                                   true,
                                   true);
    }

    private static final class SilentClusterNetwork implements ClusterNetwork {
        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            return Unit.unit();
        }

        @Override
        public <M extends ProtocolMessage> Unit broadcast(M message) {
            return Unit.unit();
        }

        @Override
        public void connect(ConnectNode connectNode) {}

        @Override
        public void disconnect(DisconnectNode disconnectNode) {}

        @Override
        public void listNodes(ListConnectedNodes listConnectedNodes) {}

        @Override
        public void handleSend(Send send) {}

        @Override
        public void handleBroadcast(Broadcast broadcast) {}

        @Override
        public Promise<Unit> start() {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }

        @Override
        public int connectedNodeCount() {
            return 0;
        }

        @Override
        public Set<NodeId> connectedPeers() {
            return Set.of();
        }

        @Override
        public Option<Server> server() {
            return Option.none();
        }
    }
}
