package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancer.dhtRebalancer;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// Issue #1818: a node wrongly in the drain set must never cost the last copy. When the exclusion
/// exhausts the ring, no node lies beyond the vacated slots, so the push must fall back to every
/// remaining node instead of only the newcomers. Keys are under-replicated (one responsible replica
/// lacks each), as run 7's joiners were; the empty-set arm is the positive control.
class DHTDepartureStaleDrainSetTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, DHTConfig.DEFAULT_TIMEOUT);
    private static final byte[] V0 = "v0".getBytes(StandardCharsets.UTF_8);
    /// Resend window of the push under test. Generous on purpose (#3049): the push ends as soon as every page is acknowledged,
    /// so the budget only decides how long a stalled host may delay a page before it is abandoned (the 50 ms it replaced let a
    /// CI thread stall past the window lose a page: DeparturePushRun abandons a page whose window ended before its first send).
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(30).seconds();
    /// The legacy budget, kept to prove the instrument sees a stalled page lost.
    private static final TimeSpan LEGACY_BUDGET = TimeSpan.timeSpan(50).millis();
    /// A first page read delayed past [#LEGACY_BUDGET], injected deterministically: the delay is fixed, never a race.
    private static final long STALL_MS = 300L;
    private static final byte[] DEPOSED = "deposed".getBytes(StandardCharsets.UTF_8);

    @Test
    void coDrain_firstPageReadStalledPastLegacyBudget_losesNothing_controlWithLegacyBudgetDoes() {
        var names = List.of("n-a", "n-b", "n-c", "n-d", "n-e", "n-f");
        var drainers = Set.of(new NodeId("n-a"), new NodeId("n-b"));

        assertThat(losses(names, drainers, drainers, LEGACY_BUDGET, STALL_MS))
            .as("positive control: a first read stalled past the legacy budget abandons the page").isPositive();
        assertThat(losses(names, drainers, drainers, BUDGET, STALL_MS))
            .as("the stall is within the budget: nothing is lost").isZero();
    }

    // ---- 4. (a): co-drain with a stale or missing drain-set entry, real ring ----
    @Test
    void coDrain_realRing_staleEntryNeverLosesALastCopy_controlWithoutTheSetDoes() {
        var rnd = new Random(1820);
        int lossWithStale = 0, lossWithExact = 0, lossWithEmpty = 0, trials = 0;

        for (int t = 0; t < 120; t++) {
            int n = 5 + rnd.nextInt(5);
            var names = new ArrayList<String>();
            for (int i = 0; i < n; i++) {
                names.add("node-" + Integer.toString(rnd.nextInt(1 << 20), 36));
            }
            if (new HashSet<>(names).size() != n) {
                continue;
            }
            var ids = names.stream().map(NodeId::new).toList();
            var shuffled = new ArrayList<>(ids);
            java.util.Collections.shuffle(shuffled, rnd);
            int drainCount = 2 + rnd.nextInt(Math.min(2, n - 3));
            var drainers = Set.copyOf(shuffled.subList(0, drainCount));
            var stale = shuffled.get(drainCount);
            var staleSet = new HashSet<>(drainers);
            staleSet.add(stale);

            trials++;
            lossWithStale += losses(names, drainers, Set.copyOf(staleSet), BUDGET, 0L);
            lossWithExact += losses(names, drainers, drainers, BUDGET, 0L);
            lossWithEmpty += losses(names, drainers, Set.of(), BUDGET, 0L);
        }

        System.out.printf("coDrain trials=%d lossStale=%d lossExact=%d lossEmpty(base behaviour)=%d%n",
                          trials, lossWithStale, lossWithExact, lossWithEmpty);
        assertThat(lossWithEmpty).as("positive control: the instrument can see a loss").isPositive();
        assertThat(lossWithExact).as("exact drain set loses nothing").isZero();
        assertThat(lossWithStale).as("a stale extra entry loses nothing").isZero();
    }

    /// Concurrent drain: every drainer pushes from its own pre-drain storage with `coDeparting` as the
    /// drain set it saw; pushes landing on any drainer die with it. Returns keys with no surviving holder.
    private static int losses(List<String> names, Set<NodeId> drainers, Set<NodeId> coDeparting, TimeSpan budget, long firstReadStallMs) {
        var cluster = new Cluster(names, Set.of(), firstReadStallMs);
        cluster.hold();
        var rf = CONFIG.effectiveReplicationFactor(names.size());
        var keys = new ArrayList<byte[]>();

        for (int i = 0; i < 400; i++) {
            var key = ("k-" + i).getBytes(StandardCharsets.UTF_8);
            keys.add(key);
            var ring = cluster.members.values().iterator().next().node().ring();
            // Under-replicated, as run 7's joiners left it: one responsible replica lacks the key.
            var responsible = new ArrayList<>(ring.nodesFor(key, rf));
            responsible.remove(i % responsible.size());
            responsible.forEach(holder -> cluster.members.get(holder).node()
                                                         .putLocalVersioned(key, V0, 1L, 0L, 1L, 1L).await());
        }

        cluster.departing.addAll(coDeparting);
        var pushes = new ArrayList<Promise<Unit>>();
        drainers.forEach(d -> pushes.add(cluster.members.get(d).rebalancer()
                                                .pushOnDeparture(budget, coDeparting,
                                                                 DeparturePushObserver.noop())));
        cluster.flush();
        pushes.forEach(Promise::await);

        int lost = 0;
        for (var key : keys) {
            boolean held = cluster.members.values().stream()
                                          .filter(m -> !drainers.contains(m.id()))
                                          .anyMatch(m -> m.node().getLocal(key).await().or(Option.<byte[]>none()).isPresent());
            if (!held) {
                lost++;
                if (DEBUG && lost <= 2) {
                    var ring = cluster.members.values().iterator().next().node().ring();
                    var holders = cluster.members.values().stream()
                                                 .filter(m -> m.node().getLocal(key).await().or(Option.<byte[]>none()).isPresent())
                                                 .map(m -> m.id().id()).toList();
                    System.out.printf("LOST key=%s rf=%d current=%s ext5=%s drainers=%s set=%s holdersAfter=%s ringSize=%d%n",
                                      new String(key, StandardCharsets.UTF_8), rf, ring.nodesFor(key, rf), ring.nodesFor(key, 5),
                                      drainers, coDeparting, holders, ring.nodeCount());
                }
            }
        }
        return lost;
    }

    static boolean DEBUG = false;

    private static String value(Member member, byte[] key) {
        return member.node().getLocal(key).await().or(Option.<byte[]>none())
                     .map(v -> new String(v, StandardCharsets.UTF_8)).or("<absent>");
    }

    private record Member(NodeId id, DHTNode node, DHTRebalancer rebalancer, DHTAntiEntropy antiEntropy, Gate gate) {}

    private static final class Gate implements OwnerEpochGate {
        private final AtomicReference<long[]> highWater = new AtomicReference<>(new long[]{0L, 0L, 0L});

        @Override
        public boolean isStale(byte[] key, long i, long t, long c) {
            return Arrays.compare(highWater.get(), new long[]{i, t, c}) > 0;
        }

        @Override
        public void advance(byte[] key, long i, long t, long c) {
            highWater.accumulateAndGet(new long[]{i, t, c}, (cur, p) -> Arrays.compare(p, cur) > 0 ? p : cur);
        }
    }

    private static StorageEngine refusingBadPrefix(StorageEngine d) {
        return new StorageEngine() {
            public Promise<Option<byte[]>> get(byte[] key) { return d.get(key); }
            public Promise<Unit> put(byte[] key, byte[] value) { return d.put(key, value); }
            public Promise<Boolean> remove(byte[] key) { return d.remove(key); }
            public Promise<Boolean> exists(byte[] key) { return d.exists(key); }
            public Promise<Boolean> putVersioned(byte[] k, byte[] v, long ver, long i, long t, long c) {
                return d.putVersioned(k, v, ver, i, t, c);
            }
            public Promise<Boolean> putReplica(byte[] k, byte[] v, long ver, long i, long t, long c) {
                return new String(k, StandardCharsets.UTF_8).startsWith("bad/")
                       ? Causes.cause("refused").promise()
                       : d.putReplica(k, v, ver, i, t, c);
            }
            public long size() { return d.size(); }
            public Promise<Unit> clear() { return d.clear(); }
            public Promise<Unit> shutdown() { return d.shutdown(); }
            public Promise<List<byte[]>> keys() { return d.keys(); }
            public Promise<List<DHTMessage.KeyValue>> entries() { return d.entries(); }
            public Promise<List<DHTMessage.KeyValue>> entriesForPartition(ConsistentHashRing<?> ring, Partition p) {
                return d.entriesForPartition(ring, p);
            }
        };
    }

    /// Delegates everything; the first entry read answers only after `stallMs`, as a starved host would.
    private static StorageEngine stallingFirstRead(StorageEngine d, long stallMs) {
        var stalled = new AtomicBoolean(false);
        return new StorageEngine() {
            public Promise<Option<byte[]>> get(byte[] key) { return d.get(key); }
            public Promise<Unit> put(byte[] key, byte[] value) { return d.put(key, value); }
            public Promise<Boolean> remove(byte[] key) { return d.remove(key); }
            public Promise<Boolean> exists(byte[] key) { return d.exists(key); }
            public Promise<Boolean> putVersioned(byte[] k, byte[] v, long ver, long i, long t, long c) {
                return d.putVersioned(k, v, ver, i, t, c);
            }
            public Promise<Boolean> putReplica(byte[] k, byte[] v, long ver, long i, long t, long c) {
                return d.putReplica(k, v, ver, i, t, c);
            }
            public Promise<Option<DHTMessage.KeyValue>> getEntry(byte[] key) {
                return stalled.compareAndSet(false, true)
                       ? Promise.promise(TimeSpan.timeSpan(stallMs).millis(), p -> d.getEntry(key).onResult(p::resolve))
                       : d.getEntry(key);
            }
            public long size() { return d.size(); }
            public Promise<Unit> clear() { return d.clear(); }
            public Promise<Unit> shutdown() { return d.shutdown(); }
            public Promise<List<byte[]>> keys() { return d.keys(); }
            public Promise<List<DHTMessage.KeyValue>> entries() { return d.entries(); }
            public Promise<List<DHTMessage.KeyValue>> entriesForPartition(ConsistentHashRing<?> ring, Partition p) {
                return d.entriesForPartition(ring, p);
            }
        };
    }

    private static final class Cluster {
        final Map<NodeId, Member> members = new LinkedHashMap<>();
        final List<ProtocolMessage> delivered = new CopyOnWriteArrayList<>();
        final List<Map.Entry<NodeId, ProtocolMessage>> queue = new CopyOnWriteArrayList<>();
        /// What the leader's drain set tells every receiver: the set the drainers pushed with.
        final Set<NodeId> departing = new HashSet<>();
        private boolean queueing = false;

        Cluster(List<String> names, Set<String> partialRefusers) {
            this(names, partialRefusers, 0L);
        }

        Cluster(List<String> names, Set<String> partialRefusers, long firstReadStallMs) {
            var ids = names.stream().map(NodeId::new).toList();
            ids.forEach(id -> {
                var ring = ConsistentHashRing.<NodeId>consistentHashRing();
                ids.forEach(ring::addNode);
                var gate = new Gate();
                StorageEngine plain = partialRefusers.contains(id.id())
                                      ? refusingBadPrefix(memoryStorageEngine(gate))
                                      : memoryStorageEngine(gate);
                StorageEngine storage = firstReadStallMs > 0 ? stallingFirstRead(plain, firstReadStallMs) : plain;
                var node = dhtNode(id, storage, ring, CONFIG);
                DHTNetwork net = AcceptingNetwork.accepting(this::deliver);
                members.put(id, new Member(id, node, dhtRebalancer(node, net, CONFIG), dhtAntiEntropy(node, net, CONFIG, departing::contains), gate));
            });
        }

        Member member(String name) {
            return members.get(new NodeId(name));
        }

        /// Starts holding every message instead of routing it.
        synchronized void hold() {
            queueing = true;
        }

        /// Stops holding and routes every held message, to drainers too: a message to a drained node dies with it, and the
        /// push still completes on its ack, so no page waits for a resend. Held messages are taken under the same monitor as
        /// [#hold(NodeId, ProtocolMessage)], so a message sent by the resend timer cannot be added after the take and orphaned.
        void flush() {
            release().forEach(e -> route(e.getKey(), e.getValue()));
        }

        private synchronized List<Map.Entry<NodeId, ProtocolMessage>> release() {
            queueing = false;
            var pending = new ArrayList<>(queue);
            queue.clear();
            return pending;
        }

        private synchronized boolean hold(NodeId target, ProtocolMessage message) {
            return queueing && queue.add(Map.entry(target, message));
        }

        private void route(NodeId target, ProtocolMessage message) {
            delivered.add(message);
            Option.option(members.get(target)).onPresent(m -> route(m, message));
        }

        private void deliver(NodeId target, ProtocolMessage message) {
            if (!hold(target, message)) {
                route(target, message);
            }
        }

        boolean ackFor(String requestId) {
            return delivered.stream()
                            .filter(DHTMessage.MigrationDataAck.class::isInstance)
                            .map(DHTMessage.MigrationDataAck.class::cast)
                            .filter(ack -> ack.requestId().equals(requestId))
                            .findFirst().orElseThrow().applied();
        }

        private void route(Member m, ProtocolMessage message) {
            switch (message) {
                case DHTMessage.DigestRequest r -> m.node().handleDigestRequest(r, resp -> deliver(r.sender(), resp));
                case DHTMessage.StampDigestRequest r -> m.node().handleStampDigestRequest(r, resp -> deliver(r.sender(), resp));
                case DHTMessage.DigestResponse r -> m.antiEntropy().onDigestResponse(r);
                case DHTMessage.MigrationDataRequest r -> m.node().handleMigrationDataRequest(r, resp -> deliver(r.sender(), resp));
                case DHTMessage.MigrationPageRequest r -> m.node().handleMigrationPageRequest(r, resp -> deliver(r.sender(), resp));
                case DHTMessage.MigrationDataResponse r -> m.antiEntropy().onMigrationDataResponse(r);
                case DHTMessage.MigrationPageResponse r -> m.antiEntropy().onMigrationPageResponse(r);
                case DHTMessage.MigrationDataAck a -> m.rebalancer().onMigrationDataAck(a);
                default -> {}
            }
        }
    }
}
