package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancer.dhtRebalancer;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #1818 v1820 r5: a node joining concurrently with a drain, known to the receivers but not yet to the pusher,
/// must not push a legitimate newcomer past RF in the receiver's view. Built on the L1 harness.
///
/// #1818 L1 (v1820 r4's probe): the drain set reaches the pusher and each receiver in separate leader pings,
/// so a receiver may not yet know a co-drainer the pusher excluded. A newcomer the pusher legitimately targets
/// must still take the copy, because nothing retries a nacked departure batch. Real rings of 5–9 nodes,
/// under-replicated keys (one responsible replica lacks each, as run 7's joiners left them), two or three
/// concurrent drainers, 120 random topologies. The pre-#1818 arm (no co-drain exclusion anywhere) is the
/// positive control. Without the carried leaving set, the receivers-know-nothing arm loses 527 keys.
class DHTDepartureConcurrentJoinTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, DHTConfig.DEFAULT_TIMEOUT);
    private static final byte[] V0 = "v0".getBytes(StandardCharsets.UTF_8);
    private static final int KEYS = 400;

    @Test
    void concurrentJoinAndDrain_receiverKnowsJoinersThePusherDoesNot() {
        int lostWithJoins = 0, controlTotal = 0;
        for (int joins = 0; joins <= 3; joins++) {
            var rnd = new Random(1820);
            int trials = 0, lost = 0, control = 0;
            for (int t = 0; t < 120; t++) {
                int n = 5 + rnd.nextInt(5);
                var names = new ArrayList<String>();
                for (int i = 0; i < n; i++) names.add("node-" + Integer.toString(rnd.nextInt(1 << 20), 36));
                if (new HashSet<>(names).size() != n) continue;
                var shuffled = new ArrayList<>(names.stream().map(NodeId::new).toList());
                Collections.shuffle(shuffled, rnd);
                int drainCount = 2 + rnd.nextInt(Math.min(2, n - 3));
                var drainers = Set.copyOf(shuffled.subList(0, drainCount));
                var joiners = new ArrayList<String>();
                for (int j = 0; j < joins; j++) joiners.add("join-" + Integer.toString(rnd.nextInt(1 << 20), 36));
                trials++;
                lost += losses(names, joiners, drainers, drainers);
                control += losses(names, joiners, drainers, Set.of());
            }
            System.out.printf("joins=%d trials=%d lost=%d control(no co-drain exclusion)=%d%n", joins, trials, lost, control);
            lostWithJoins += lost;
            controlTotal += control;
        }
        assertThat(controlTotal).as("positive control: the harness can see a loss").isPositive();
        assertThat(lostWithJoins).as("joiners known to receivers but not to the pusher lose no key").isZero();
    }

    /// Joiners are known to every non-draining receiver and to themselves, but not to the drainers (pushers).
    private static int losses(List<String> names, List<String> joinerNames, Set<NodeId> drainers, Set<NodeId> coDeparting) {
        var cluster = new Cluster(names, joinerNames, drainers);
        var rf = CONFIG.effectiveReplicationFactor(names.size());
        var keys = new ArrayList<byte[]>();
        var preJoin = ConsistentHashRing.<NodeId>consistentHashRing();
        names.forEach(n -> preJoin.addNode(new NodeId(n)));

        cluster.queueing = true;
        for (int i = 0; i < KEYS; i++) {
            var key = ("k-" + i).getBytes(StandardCharsets.UTF_8);
            var responsible = new ArrayList<>(preJoin.nodesFor(key, rf));
            keys.add(key);
            responsible.remove(i % responsible.size());
            responsible.forEach(holder -> cluster.members.get(holder).node().putLocalVersioned(key, V0, 1L, 0L, 1L, 1L).await());
        }
        cluster.departing.addAll(drainers);
        var pushes = drainers.stream()
                             .map(d -> cluster.members.get(d).rebalancer()
                                              .pushOnDeparture(TimeSpan.timeSpan(50).millis(), coDeparting, DeparturePushObserver.noop()))
                             .toList();
        cluster.queueing = false;
        cluster.flush(drainers);
        pushes.forEach(Promise::await);
        return (int) keys.stream().filter(key -> !cluster.survivorHolds(key, drainers)).count();
    }

    private record Member(NodeId id, DHTNode node, DHTRebalancer rebalancer, DHTAntiEntropy antiEntropy) {}

    private static final class Cluster {
        final Map<NodeId, Member> members = new LinkedHashMap<>();
        final List<Map.Entry<NodeId, ProtocolMessage>> queue = new CopyOnWriteArrayList<>();
        /// The drain set every receiver has seen so far.
        final Set<NodeId> departing = new HashSet<>();
        volatile boolean queueing = false;

        Cluster(List<String> names, List<String> joinerNames, Set<NodeId> drainers) {
            var ids = names.stream().map(NodeId::new).toList();
            var all = new ArrayList<>(ids);
            joinerNames.forEach(j -> all.add(new NodeId(j)));

            ids.forEach(id -> members.put(id, member(id, drainers.contains(id) ? ids : all)));
            joinerNames.forEach(j -> members.put(new NodeId(j), member(new NodeId(j), all)));
        }

        private Member member(NodeId id, List<NodeId> ids) {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();

            ids.forEach(ring::addNode);

            var node = dhtNode(id, memoryStorageEngine(), ring, CONFIG);
            DHTNetwork network = this::deliver;

            return new Member(id, node, dhtRebalancer(node, network, CONFIG), dhtAntiEntropy(node, network, CONFIG, departing::contains));
        }

        ConsistentHashRing<NodeId> anyRing() {
            return members.values().iterator().next().node().ring();
        }

        boolean survivorHolds(byte[] key, Set<NodeId> drainers) {
            return members.values()
                          .stream()
                          .filter(m -> !drainers.contains(m.id()))
                          .anyMatch(m -> m.node().getLocal(key).await().or(Option.<byte[]>none()).isPresent());
        }

        void flush(Set<NodeId> dead) {
            var pending = new ArrayList<>(queue);

            queue.clear();
            pending.stream()
                   .filter(e -> !dead.contains(e.getKey()))
                   .forEach(e -> deliver(e.getKey(), e.getValue()));
        }

        private void deliver(NodeId target, ProtocolMessage message) {
            if (queueing) {
                queue.add(Map.entry(target, message));

                return;
            }

            Option.option(members.get(target)).onPresent(m -> route(m, message));
        }

        private void route(Member m, ProtocolMessage message) {
            switch (message) {
                case DHTMessage.DigestRequest r -> m.node().handleDigestRequest(r, resp -> deliver(r.sender(), resp));
                case DHTMessage.DigestResponse r -> m.antiEntropy().onDigestResponse(r);
                case DHTMessage.MigrationDataRequest r -> m.node().handleMigrationDataRequest(r, resp -> deliver(r.sender(), resp));
                case DHTMessage.MigrationDataResponse r -> m.antiEntropy().onMigrationDataResponse(r);
                case DHTMessage.MigrationDataAck a -> m.rebalancer().onMigrationDataAck(a);
                default -> {}
            }
        }
    }
}
