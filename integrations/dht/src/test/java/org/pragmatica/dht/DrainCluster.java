package org.pragmatica.dht;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancer.dhtRebalancer;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// In-process cluster for the departure-drain tests (#3208, #3209): every member is a real [DHTNode], [DHTRebalancer] and
/// [DHTAntiEntropy] on its own ring, wired through an accepting network. `dropTo` makes a member unreachable while it stays in the
/// ring, `removeOnFirstSend` removes a member from the pushers' rings when the first push to it leaves, and `sentTo` records every
/// departure page by target, `entriesTo` the entries pushed to each.
final class DrainCluster {
    static final DHTConfig CONFIG = new DHTConfig(2, 1, 1, DHTConfig.DEFAULT_TIMEOUT);
    static final TimeSpan BUDGET = TimeSpan.timeSpan(5).seconds();
    private static final byte[] V0 = "v0".getBytes(StandardCharsets.UTF_8);

    final Map<NodeId, Member> members = new LinkedHashMap<>();
    final Set<NodeId> departing = new HashSet<>();
    final Set<NodeId> dropTo = new HashSet<>();
    private final Set<NodeId> pushers = new HashSet<>();
    final List<NodeId> sentTo = new CopyOnWriteArrayList<>();
    final Map<NodeId, Integer> entriesTo = new ConcurrentHashMap<>();
    final List<Map.Entry<NodeId, ProtocolMessage>> queue = new CopyOnWriteArrayList<>();
    final AtomicInteger atRiskRaised = new AtomicInteger();
    volatile NodeId removeOnFirstSend;
    private boolean queueing = false;

    record Member(NodeId id, DHTNode node, DHTRebalancer rebalancer, DHTAntiEntropy antiEntropy) {}

    private static final class Gate implements OwnerEpochGate {
        @Override
        public boolean isStale(byte[] key, long i, long t, long c) {
            return false;
        }

        @Override
        public void advance(byte[] key, long i, long t, long c) {}
    }

    DrainCluster(List<String> names) {
        var ids = names.stream().map(NodeId::new).toList();
        ids.forEach(id -> {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ids.forEach(ring::addNode);
            var node = dhtNode(id, memoryStorageEngine(new Gate()), ring, CONFIG);
            DHTNetwork net = AcceptingNetwork.accepting((target, message) -> deliver(id, target, message));
            members.put(id, new Member(id, node, dhtRebalancer(node, net, CONFIG),
                                       dhtAntiEntropy(node, net, CONFIG, departing::contains)));
        });
    }

    static NodeId id(String name) {
        return new NodeId(name);
    }

    /// The first probe key whose full ring walk is exactly `order`.
    byte[] keyInOrder(String... order) {
        var expected = java.util.Arrays.stream(order).map(NodeId::new).toList();
        var ring = members.values().iterator().next().node().ring();
        for (int i = 0; i < 200_000; i++) {
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

    /// Every drainer plans from its pre-drain storage (messages held until all pushes have planned), then every message is routed,
    /// to drainers too: a copy landing on a drainer dies with it. The awaits are bounded, so a drain that never ends fails on the
    /// caller's assertion about the copy instead of hanging the suite.
    List<DeparturePushReport> drain(Set<NodeId> drainers, Set<NodeId> drainSet) {
        return drain(drainers, drainSet, drainSet);
    }

    /// As [#drain(Set, Set)], with every receiver knowing only `receiversKnow` of the drain set (#1818: separate leader pings).
    List<DeparturePushReport> drain(Set<NodeId> drainers, Set<NodeId> drainSet, Set<NodeId> receiversKnow) {
        departing.addAll(receiversKnow);
        pushers.addAll(drainers);
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
        var bound = BUDGET.plus(TimeSpan.timeSpan(5).seconds());

        return pushes.stream().map(p -> p.await(bound).or(DeparturePushReport.NOT_ENUMERATED)).toList();
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

    private void deliver(NodeId from, NodeId target, ProtocolMessage message) {
        if (message instanceof DHTMessage.MigrationDataResponse response && pushers.contains(from)) {
            sentTo.add(target);
            entriesTo.merge(target, response.entries().size(), Integer::sum);
            if (target.equals(removeOnFirstSend)) {
                members.get(from).node().ring().removeNode(target);
                removeOnFirstSend = null;
            }
        }
        if (dropTo.contains(target)) {
            return;
        }
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
