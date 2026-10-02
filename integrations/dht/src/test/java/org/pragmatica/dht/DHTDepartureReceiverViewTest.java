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

/// #1818 L1 (v1820 r4's probe): the drain set reaches the pusher and each receiver in separate leader pings,
/// so a receiver may not yet know a co-drainer the pusher excluded. A newcomer the pusher legitimately targets
/// must still take the copy, because nothing retries a nacked departure batch. Real rings of 5–9 nodes,
/// under-replicated keys (one responsible replica lacks each, as run 7's joiners left them), two or three
/// concurrent drainers, 120 random topologies. The pre-#1818 arm (no co-drain exclusion anywhere) is the
/// positive control. Without the carried leaving set, the receivers-know-nothing arm loses 527 keys.
class DHTDepartureReceiverViewTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, DHTConfig.DEFAULT_TIMEOUT);
    private static final byte[] V0 = "v0".getBytes(StandardCharsets.UTF_8);
    private static final int KEYS = 400;

    @Test
    void concurrentDrain_losesNoKey_whateverPartOfTheDrainSetTheReceiversHaveSeen() {
        var rnd = new Random(1820);
        var arms = new Arms();

        for (int t = 0; t < 120; t++) {
            trial(rnd, arms);
        }

        System.out.printf("L1 trials=%d receiversKnowExact=%d receiversKnowNothing=%d receiversKnowFirstOnly=%d "
                          + "staleAtPusherOnly=%d staleKnownByAll=%d pre1818(control)=%d%n",
                          arms.trials, arms.exactKnown, arms.noneKnown, arms.firstOnlyKnown,
                          arms.staleAtPusher, arms.staleKnownByAll, arms.control);

        assertThat(arms.control).as("positive control: the pre-#1818 push loses keys in this harness").isPositive();
        assertThat(arms.exactKnown).as("receivers know the exact drain set").isZero();
        assertThat(arms.noneKnown).as("receivers have not yet heard of any co-drainer").isZero();
        assertThat(arms.firstOnlyKnown).as("staggered: receivers know only the first drainer").isZero();
        assertThat(arms.staleAtPusher).as("a stale entry in the pusher's set only").isZero();
        assertThat(arms.staleKnownByAll).as("a stale entry everyone has seen").isZero();
    }

    private static final class Arms {
        int trials, exactKnown, noneKnown, firstOnlyKnown, staleAtPusher, staleKnownByAll, control;
    }

    private static void trial(Random rnd, Arms arms) {
        int n = 5 + rnd.nextInt(5);
        var names = new ArrayList<String>();

        for (int i = 0; i < n; i++) {
            names.add("node-" + Integer.toString(rnd.nextInt(1 << 20), 36));
        }

        if (new HashSet<>(names).size() != n) {
            return;
        }

        var shuffled = new ArrayList<>(names.stream().map(NodeId::new).toList());

        Collections.shuffle(shuffled, rnd);

        int drainCount = 2 + rnd.nextInt(Math.min(2, n - 3));
        var drainers = Set.copyOf(shuffled.subList(0, drainCount));
        var first = Set.of(shuffled.get(0));
        var stale = new HashSet<>(drainers);

        stale.add(shuffled.get(drainCount));

        arms.trials++;
        arms.exactKnown += losses(names, drainers, drainers, drainers);
        arms.noneKnown += losses(names, drainers, drainers, Set.of());
        arms.firstOnlyKnown += losses(names, drainers, drainers, first);
        arms.staleAtPusher += losses(names, drainers, Set.copyOf(stale), drainers);
        arms.staleKnownByAll += losses(names, drainers, Set.copyOf(stale), Set.copyOf(stale));
        arms.control += losses(names, drainers, Set.of(), Set.of());
    }

    /// Concurrent drain: every drainer pushes from its own pre-drain storage with `coDeparting` as the drain
    /// set it saw, while every receiver knows only `receiversKnow`. Pushes landing on a drainer die with it.
    /// Returns the keys no surviving node holds.
    private static int losses(List<String> names, Set<NodeId> drainers, Set<NodeId> coDeparting, Set<NodeId> receiversKnow) {
        var cluster = new Cluster(names);
        var rf = CONFIG.effectiveReplicationFactor(names.size());
        var keys = new ArrayList<byte[]>();

        cluster.queueing = true;
        for (int i = 0; i < KEYS; i++) {
            var key = ("k-" + i).getBytes(StandardCharsets.UTF_8);
            var responsible = new ArrayList<>(cluster.anyRing().nodesFor(key, rf));

            keys.add(key);
            responsible.remove(i % responsible.size());
            responsible.forEach(holder -> cluster.members.get(holder).node().putLocalVersioned(key, V0, 1L, 0L, 1L, 1L).await());
        }

        cluster.departing.addAll(receiversKnow);

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

        Cluster(List<String> names) {
            var ids = names.stream().map(NodeId::new).toList();

            ids.forEach(id -> members.put(id, member(id, ids)));
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
