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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.pragmatica.aether.deployment.membership.ntt.DepartureOutcome;
import org.pragmatica.aether.deployment.membership.ntt.DrainWindow;
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
import org.pragmatica.dht.DHTTopologyListener;
import org.pragmatica.dht.DeparturePushObserver;
import org.pragmatica.dht.DeparturePushReport;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.Server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.io.TimeSpan;

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

        // #2034: the push resends an unacked page until its window ends, so it is awaited and the targets are read as a set. The
        // default tuning keeps the last 2 s for event delivery, so 3 s leaves 1 s of sending.
        var window = DrainWindow.drainWindow(System.nanoTime() + TimeSpan.timeSpan(3).seconds().nanos(),
                                             System.currentTimeMillis(),
                                             () -> false);
        DhtDeparturePush.dhtDeparturePush(rebalancer, collector, SELF, () -> _ -> DeparturePushObserver.noop())
                        .apply(window)
                        .await();

        var vacatedSlots = ring.nodesFor(key, 3, id -> !id.equals(SELF) && !id.equals(CO_DRAINER))
                               .stream()
                               .filter(id -> !ring.nodesFor(key, 3).contains(id))
                               .toList();

        assertThat(vacatedSlots).as("control: both drainers held the key, so two slots open").hasSize(2);
        var pushed = pushedTo.stream().distinct().toList();

        assertThat(pushed).as("the push reached every node that newly owns the key").containsAll(vacatedSlots);
        // #3208/#3209: the drain set may name a live node (#1818), so the push also reaches the named prefix of the ring walk and the
        // first unnamed node. That is the co-drainer and a node that already held the key, never a node outside the replica set.
        // The old exact-target pin asserted the narrower pre-#3208 set, which could strand the copy.
        assertThat(pushed.stream().filter(id -> !vacatedSlots.contains(id)).toList())
            .as("the extra targets are the co-drainer and an existing replica, nobody else")
            .allMatch(id -> id.equals(CO_DRAINER) || ring.nodesFor(key, 3).contains(id));
    }

    /// #2684 round B, N09: the drain window's `halted` reaches the DHT window, so a node that already halted sends nothing.
    @Test
    @Timeout(20)
    void departurePush_haltedDrainWindow_sendsNothing() {
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

        var window = DrainWindow.drainWindow(System.nanoTime() + TimeSpan.timeSpan(3).seconds().nanos(),
                                             System.currentTimeMillis(),
                                             () -> true);
        DhtDeparturePush.dhtDeparturePush(rebalancer, collector, SELF, () -> _ -> DeparturePushObserver.noop())
                        .apply(window)
                        .await();

        assertThat(pushedTo).isEmpty();
    }

    /// #2684 round B, N08: the drain window's event floor reaches the DHT window. The legs deadline is 150 ms away, the floor 10 s:
    /// the at-risk event in flight for 3 s is awaited for the push's event wait (2 s), not abandoned at the legs deadline.
    @Test
    @Timeout(20)
    void departurePush_eventFloor_extendsTheEventWaitPastTheLegsDeadline() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        List.of(SELF, CO_DRAINER).forEach(ring::addNode);
        var node = DHTNode.dhtNode(SELF, memoryStorageEngine(), ring, CONFIG);
        var rebalancer = DHTRebalancer.dhtRebalancer(node, recordingNetwork(new CopyOnWriteArrayList<>()), CONFIG);
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new SilentClusterNetwork());
        var atRiskCalls = new AtomicInteger();

        node.putLocal("floor".getBytes(StandardCharsets.UTF_8), "payload".getBytes(StandardCharsets.UTF_8)).await();
        collector.setMetricsProducerEligibility(_ -> true);
        collector.setPingAuthority(LEADER::equals, LEADER::equals);
        collector.onClusterSyncPing(drainPing(Set.of(SELF, CO_DRAINER)));

        var started = System.nanoTime();
        var window = new DrainWindow(started + TimeSpan.timeSpan(150).millis().nanos(),
                                     started + TimeSpan.timeSpan(10).seconds().nanos(),
                                     System.currentTimeMillis(),
                                     () -> false);
        DhtDeparturePush.dhtDeparturePush(rebalancer, collector, SELF, () -> _ -> slowAtRisk(atRiskCalls))
                        .apply(window)
                        .await();

        assertThat(atRiskCalls.get()).as("control: the sole key was reported at risk").isEqualTo(1);
        assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(TimeSpan.timeSpan(1_500).millis().nanos());
    }

    private static DeparturePushObserver slowAtRisk(AtomicInteger calls) {
        return new DeparturePushObserver() {
            @Override
            public void onIncomplete(int keysAtRisk, List<String> sampleKeys) {}

            @Override
            public Promise<Unit> onSoleCopyAtRisk(int count, List<String> sampleKeys) {
                calls.incrementAndGet();

                return Promise.promise(TimeSpan.timeSpan(3).seconds(), Result::unitResult);
            }
        };
    }

    /// #2684 round B, N06/N14: the report-to-outcome mapping. Sole-copy keys unconfirmed, or an inventory never enumerated, is data at
    /// risk; replicated keys unconfirmed are repaired from their survivor and are not.
    @Test
    void outcome_soleCopyKeysUnconfirmed_isCompletedWithDataAtRisk() {
        assertThat(DhtDeparturePush.outcome(new DeparturePushReport(1, 0, true))).isEqualTo(DepartureOutcome.COMPLETED_WITH_DATA_AT_RISK);
    }

    @Test
    void outcome_inventoryNotEnumerated_isCompletedWithDataAtRisk() {
        assertThat(DhtDeparturePush.outcome(DeparturePushReport.NOT_ENUMERATED)).isEqualTo(DepartureOutcome.COMPLETED_WITH_DATA_AT_RISK);
    }

    @Test
    void outcome_onlyReplicatedKeysUnconfirmed_isCompleted() {
        assertThat(DhtDeparturePush.outcome(new DeparturePushReport(0, 3, true))).isEqualTo(DepartureOutcome.COMPLETED);
    }

    @Test
    void outcome_nothingAtRisk_isCompleted() {
        assertThat(DhtDeparturePush.outcome(DeparturePushReport.COMPLETE)).isEqualTo(DepartureOutcome.COMPLETED);
    }

    /// #1818 round 3, the receiving side: a departure push is accepted from a node the leader's drain ping
    /// named, or that this node's membership saw enter DEPARTING — and from no one else.
    @Test
    void departingSenders_areTheDrainSetAndTheMembershipDeparting_andNoOneElse() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        RING_MEMBERS.forEach(ring::addNode);
        var node = DHTNode.dhtNode(SELF, memoryStorageEngine(), ring, CONFIG);
        var topology = DHTTopologyListener.dhtTopologyListener(node);
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new SilentClusterNetwork());
        var membershipDeparting = new NodeId("node-3");
        var bystander = new NodeId("node-4");

        collector.setMetricsProducerEligibility(_ -> true);
        collector.setPingAuthority(LEADER::equals, LEADER::equals);

        var accepts = DhtDeparturePush.departingSenders(collector, topology);

        assertThat(accepts.test(CO_DRAINER)).as("nothing commanded yet").isFalse();

        collector.onClusterSyncPing(drainPing(Set.of(CO_DRAINER)));
        topology.onNodeDeparting(membershipDeparting);

        assertThat(accepts.test(CO_DRAINER)).as("named by the leader's drain set").isTrue();
        assertThat(accepts.test(membershipDeparting)).as("seen entering DEPARTING").isTrue();
        assertThat(accepts.test(bystander)).isFalse();
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
        return AcceptingTransports.acceptingDht((target, message) -> recordPush(pushedTo, target, message));
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
        public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId nodeId, M message) {
            return Promise.success(new WriteOutcome.Sent(nodeId));
        }

        @Override
        public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId nodeId, M message, TimeSpan offlineTtl) {
            return send(nodeId, message);
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
