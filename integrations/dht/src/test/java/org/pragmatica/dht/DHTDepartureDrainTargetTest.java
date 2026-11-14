package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancer.dhtRebalancer;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// The two departure-drain counterexamples the TLA+ departure model (#3205) found, reproduced exactly. Shared root: a LIVE node that
/// is only a stale drain-set entry (#1818) was treated as leaving.
///
/// - #3208 (`allnamed`, NoLastCopyLoss): N=3, RF=2, nodes 1 and 2 drain, node 3 is live but named in the drain set, every copy is
///   on 1 or 2. The exclusion left no target at all, so both drainers halted with the key.
/// - #3209 (`strand`, ReplicaPlacement): N=4, RF=2, ring order 1..4 for the key, node 1 drains holding the only copy, node 3 is a
///   stale entry. The delta branch targeted only 4, which is outside the final replica set {2,3}.
class DHTDepartureDrainTargetTest {
    private static final DHTConfig CONFIG = new DHTConfig(2, 1, 1, DHTConfig.DEFAULT_TIMEOUT);
    private static final byte[] V0 = "v0".getBytes(StandardCharsets.UTF_8);
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(5).seconds();

    @Test
    void allNamed_staleLiveEntryIsTheOnlyNonDrainer_keySurvivesOnIt_andTheUnprovableCopyIsReportedAtRisk() {
        var cluster = new Cluster(List.of("n1", "n2", "n3"));
        var key = cluster.keyInOrder("n1", "n2", "n3");
        var drainers = Set.of(id("n1"), id("n2"));
        var drainSet = Set.of(id("n1"), id("n2"), id("n3"));

        cluster.put(key, "n1", "n2");
        var reports = cluster.drain(drainers, drainSet);

        assertThat(cluster.holds("n3", key))
            .as("#3208: the live node named only by a stale drain-set entry receives the last copy").isTrue();
        assertThat(cluster.atRiskRaised.get())
            .as("#3208: no target outside the drain set can be shown to survive, so the sole copy is raised at risk").isPositive();
        assertThat(reports).as("#3208: the drain reports the unprovable copy as data at risk")
                           .allMatch(DeparturePushReport::dataAtRisk);
    }

    @Test
    void strand_staleEntryShiftsTheNewcomerPastTheFinalReplicaSet_aFinalReplicaHoldsTheKey() {
        var cluster = new Cluster(List.of("n1", "n2", "n3", "n4"));
        var key = cluster.keyInOrder("n1", "n2", "n3", "n4");

        cluster.put(key, "n1");
        cluster.drain(Set.of(id("n1")), Set.of(id("n1"), id("n3")));

        var finalRing = ConsistentHashRing.<NodeId>consistentHashRing();
        List.of("n2", "n3", "n4").forEach(name -> finalRing.addNode(id(name)));
        var finalReplicas = finalRing.nodesFor(key, 2);

        assertThat(finalReplicas).as("instrument: the final replica set of the model's shape").containsExactly(id("n2"), id("n3"));
        assertThat(finalReplicas.stream().anyMatch(replica -> cluster.holds(replica.id(), key)))
            .as("#3209: a member of the final replica set {2,3} holds the key the drainer held").isTrue();
    }

    @Test
    void exactDrainSet_coDrainerAckDoesNotDeliver_onlyANonNamedTargetDoes() {
        var cluster = new Cluster(List.of("n1", "n2", "n3", "n4"));
        var key = cluster.keyInOrder("n1", "n2", "n3", "n4");
        var drainers = Set.of(id("n1"), id("n2"));

        cluster.put(key, "n1");
        var reports = cluster.drain(drainers, drainers);

        assertThat(cluster.holds("n3", key) || cluster.holds("n4", key))
            .as("a node outside the drain set holds the key").isTrue();
        assertThat(reports).as("a non-named target acknowledged the key: nothing at risk")
                           .noneMatch(DeparturePushReport::dataAtRisk);
    }

    private static NodeId id(String name) {
        return new NodeId(name);
    }

    private record Member(NodeId id, DHTNode node, DHTRebalancer rebalancer, DHTAntiEntropy antiEntropy) {}

    private static final class Gate implements OwnerEpochGate {
        @Override
        public boolean isStale(byte[] key, long i, long t, long c) {
            return false;
        }

        @Override
        public void advance(byte[] key, long i, long t, long c) {}
    }

    private static final class Cluster {
        final Map<NodeId, Member> members = new LinkedHashMap<>();
        final Set<NodeId> departing = new java.util.HashSet<>();
        final List<Map.Entry<NodeId, ProtocolMessage>> queue = new CopyOnWriteArrayList<>();
        final AtomicInteger atRiskRaised = new AtomicInteger();
        private boolean queueing = false;

        Cluster(List<String> names) {
            var ids = names.stream().map(NodeId::new).toList();
            ids.forEach(id -> {
                var ring = ConsistentHashRing.<NodeId>consistentHashRing();
                ids.forEach(ring::addNode);
                var node = dhtNode(id, memoryStorageEngine(new Gate()), ring, CONFIG);
                DHTNetwork net = AcceptingNetwork.accepting(this::deliver);
                members.put(id, new Member(id, node, dhtRebalancer(node, net, CONFIG),
                                           dhtAntiEntropy(node, net, CONFIG, departing::contains)));
            });
        }

        /// The first probe key whose full ring walk is exactly `order`.
        byte[] keyInOrder(String... order) {
            var expected = java.util.Arrays.stream(order).map(NodeId::new).toList();
            var ring = members.values().iterator().next().node().ring();
            for (int i = 0; i < 100_000; i++) {
                var key = ("probe-" + i).getBytes(StandardCharsets.UTF_8);
                if (ring.nodesFor(key, order.length).equals(expected)) {
                    return key;
                }
            }
            throw new AssertionError("no probe key walks " + expected);
        }

        void put(byte[] key, String... holders) {
            for (var holder : holders) {
                members.get(id(holder)).node().putLocalVersioned(key, V0, 1L, 0L, 1L, 1L).await();
            }
        }

        boolean holds(String name, byte[] key) {
            return members.get(id(name)).node().getLocal(key).await().or(Option.<byte[]>none()).isPresent();
        }

        /// Every drainer plans from its pre-drain storage (messages held until all pushes have planned), then every message is
        /// routed, to drainers too: a copy landing on a drainer dies with it.
        List<DeparturePushReport> drain(Set<NodeId> drainers, Set<NodeId> drainSet) {
            departing.addAll(drainSet);
            hold();
            var pushes = new ArrayList<Promise<DeparturePushReport>>();
            var observer = new DeparturePushObserver() {
                @Override
                public void onIncomplete(int keysAtRisk, List<String> sampleKeys) {}

                @Override
                public Promise<Unit> onSoleCopyAtRisk(int count, List<String> sampleKeys) {
                    atRiskRaised.incrementAndGet();
                    return Promise.unitPromise();
                }
            };
            drainers.forEach(d -> pushes.add(members.get(d).rebalancer()
                                                    .pushOnDeparture(DepartureWindow.departureWindow(System.nanoTime() + BUDGET.nanos()),
                                                                     DeparturePushTuning.forBudget(BUDGET),
                                                                     drainSet,
                                                                     observer)));
            release().forEach(e -> route(e.getKey(), e.getValue()));
            return pushes.stream().map(p -> p.await().or(DeparturePushReport.NOT_ENUMERATED)).toList();
        }

        private synchronized void hold() {
            queueing = true;
        }

        private synchronized List<Map.Entry<NodeId, ProtocolMessage>> release() {
            queueing = false;
            var pending = new ArrayList<>(queue);
            queue.clear();
            return pending;
        }

        private synchronized boolean held(NodeId target, ProtocolMessage message) {
            return queueing && queue.add(Map.entry(target, message));
        }

        private void deliver(NodeId target, ProtocolMessage message) {
            if (!held(target, message)) {
                route(target, message);
            }
        }

        private void route(NodeId target, ProtocolMessage message) {
            Option.option(members.get(target)).onPresent(m -> route(m, message));
        }

        private void route(Member m, ProtocolMessage message) {
            switch (message) {
                case DHTMessage.MigrationDataResponse r -> m.antiEntropy().onMigrationDataResponse(r);
                case DHTMessage.MigrationDataAck a -> m.rebalancer().onMigrationDataAck(a);
                default -> {}
            }
        }
    }
}
