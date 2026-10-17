package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.storage.OwnerEpochGate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// v1882's same-epoch two-writer shape of F15 on the #1818 path (r11). Writers A (a replica, deposed) and R (a replica) both
/// stamp the OLD epoch. R's own high-water is still OLD when it writes X, then advances (it observes the ownership rewrite)
/// before A's W reaches it. Before r11, X reached A after A's local accept of W and was answered "superseded" — a second ack
/// that A's rollback of W (WriteIndeterminate) then undid, leaving an acknowledged X on ONE replica. An owner-epoch fence
/// refusal is now evidence like a stale refusal: A never applies W once a replica has fenced it, so nothing is rolled back
/// and no ack rests on a copy that is undone. Invariant pinned: an acknowledged X is on at least W = 2 replicas.
class DHTDeposedWriterSameEpochTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, timeSpan(2).seconds());
    private static final long[] OLD_EPOCH = {0L, 1L, 1L};
    private static final long[] NEW_EPOCH = {0L, 2L, 2L};
    private static final NodeId A = new NodeId("a-deposed");
    private static final NodeId R = new NodeId("r-advancing");
    private static final NodeId N = new NodeId("n-new");
    private static final List<NodeId> ALL = List.of(A, R, N);
    private static final byte[] KEY = "probe-s-1818-same-epoch".getBytes(StandardCharsets.UTF_8);

    @Test
    void sameEpochTwoWriters_aDeposedReplicaNeverCountsAWriteItWouldRollBack() throws Exception {
        var cluster = new Cluster();

        cluster.holding = true;
        var x = cluster.clients.get(R).put(KEY, "X".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(5);
        var w = cluster.clients.get(A).put(KEY, "W".getBytes(StandardCharsets.UTF_8));
        cluster.holding = false;

        cluster.gates.get(R).advance(KEY, NEW_EPOCH[0], NEW_EPOCH[1], NEW_EPOCH[2]);
        cluster.deliverHeldTo(A);
        cluster.deliverHeldTo(N);
        cluster.deliverHeldTo(R);
        var xOutcome = x.await();
        var wOutcome = w.await();
        var xVersion = cluster.version(R);
        var atOrAboveX = ALL.stream().filter(id -> cluster.version(id) >= xVersion).toList();
        boolean indeterminate = wOutcome.fold(cause -> cause instanceof DHTError.WriteIndeterminate, _ -> false);

        assertThat(indeterminate).as("arming: W is indeterminate: " + wOutcome).isTrue();
        assertThat(cluster.entry(A)).as("A never applied W, so there was nothing to roll back").doesNotStartWith("W@");
        assertThat(xOutcome.isSuccess() ? atOrAboveX.size() : Integer.MAX_VALUE)
            .as("an acknowledged X, or a write that superseded it, is on at least W = 2 replicas: x=" + xOutcome + " on " + atOrAboveX)
            .isGreaterThanOrEqualTo(2);
    }

    /// The no-evidence window (v1882 r12, the owner ruling "fix it now"): no replica answers within the evidence wait, so BOTH
    /// writers apply their own slot per the named limit before any fence refusal arrives. X then reaches A while A's own W is
    /// pending: A answers a typed retriable `writePending` refusal, NOT "superseded", so X's quorum can no longer count a copy
    /// that A's rollback of W undoes. Invariant: an acknowledged X is on at least W = 2 replicas. (This was an enabled tripwire
    /// asserting the one-replica result until the pending refusal closed the window.)
    @Test
    void noEvidenceWindow_anAckedXIsNeverLeftBelowItsQuorum() throws Exception {
        var cluster = new Cluster();

        cluster.holding = true;
        var x = cluster.clients.get(R).put(KEY, "X".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(5);
        var w = cluster.clients.get(A).put(KEY, "W".getBytes(StandardCharsets.UTF_8));
        cluster.holding = false;
        Thread.sleep(600);

        assertThat(cluster.entry(A)).as("arming: A applied W on silence").startsWith("W@");
        assertThat(cluster.entry(R)).as("arming: R applied X on silence").startsWith("X@");

        cluster.gates.get(R).advance(KEY, NEW_EPOCH[0], NEW_EPOCH[1], NEW_EPOCH[2]);
        cluster.deliverHeldToRequestsFrom(R, A);
        cluster.deliverHeldTo(N);
        cluster.deliverHeldTo(R);
        var xOutcome = x.await();
        var wOutcome = w.await();
        var xVersion = cluster.version(R);
        var atOrAboveX = ALL.stream().filter(id -> cluster.version(id) >= xVersion).toList();

        assertThat(wOutcome.isFailure()).as("arming: W sank").isTrue();
        assertThat(xOutcome.isSuccess() ? atOrAboveX.size() : Integer.MAX_VALUE)
            .as("an acknowledged X, or a write that superseded it, is on at least W = 2 replicas: x=" + xOutcome + " on " + atOrAboveX)
            .isGreaterThanOrEqualTo(2);
    }

    private static final class Gate implements OwnerEpochGate {
        private final AtomicReference<long[]> highWater;

        Gate(long[] seeded) {
            highWater = new AtomicReference<>(seeded);
        }

        @Override
        public boolean isStale(byte[] key, long incarnation, long term, long counter) {
            return Arrays.compare(highWater.get(), new long[]{incarnation, term, counter}) > 0;
        }

        @Override
        public void advance(byte[] key, long incarnation, long term, long counter) {
            highWater.accumulateAndGet(new long[]{incarnation, term, counter},
                                       (current, presented) -> Arrays.compare(presented, current) > 0 ? presented : current);
        }
    }

    private record Epoch(long[] epoch) implements OwnerEpochSource {
        @Override
        public long currentEpochIncarnation() {
            return epoch[0];
        }

        @Override
        public long currentEpochTerm() {
            return epoch[1];
        }

        @Override
        public long currentEpochCounter() {
            return epoch[2];
        }
    }

    private static final class Cluster {
        final Map<NodeId, DHTNode> nodes = new LinkedHashMap<>();
        final Map<NodeId, Gate> gates = new LinkedHashMap<>();
        final Map<NodeId, DistributedDHTClient> clients = new LinkedHashMap<>();
        final List<Map.Entry<NodeId, ProtocolMessage>> held = new ArrayList<>();
        volatile boolean holding;

        Cluster() {
            ALL.forEach(id -> {
                var ring = ConsistentHashRing.<NodeId>consistentHashRing();

                ALL.forEach(ring::addNode);
                var gate = new Gate(id.equals(N) ? NEW_EPOCH : OLD_EPOCH);
                var node = dhtNode(id, memoryStorageEngine(gate), ring, CONFIG);
                DHTNetwork network = this::deliver;

                gates.put(id, gate);
                nodes.put(id, node);
                clients.put(id, distributedDHTClient(node, network, CONFIG, new Epoch(OLD_EPOCH)));
            });
        }

        String entry(NodeId id) {
            return nodes.get(id).storage().entries().await().or(List.of()).stream()
                        .filter(entry -> Arrays.equals(entry.key(), KEY))
                        .findFirst()
                        .map(entry -> new String(entry.value(), StandardCharsets.UTF_8) + "@" + entry.version())
                        .orElse("absent");
        }

        long version(NodeId id) {
            return nodes.get(id).storage().entries().await().or(List.of()).stream()
                        .filter(entry -> Arrays.equals(entry.key(), KEY))
                        .mapToLong(DHTMessage.KeyValue::version).findFirst().orElse(Long.MIN_VALUE);
        }

        /// Deliver to `target` only the held requests that `sender` made.
        void deliverHeldToRequestsFrom(NodeId sender, NodeId target) {
            var due = held.stream()
                           .filter(entry -> entry.getKey().equals(target) && entry.getValue() instanceof DHTMessage.PutRequest request
                                            && request.sender().equals(sender))
                           .toList();

            held.removeAll(due);
            due.forEach(entry -> route(target, entry.getValue()));
        }

        void deliverHeldTo(NodeId target) {
            var due = held.stream().filter(entry -> entry.getKey().equals(target)).toList();

            held.removeAll(due);
            due.forEach(entry -> route(target, entry.getValue()));
        }

        private void deliver(NodeId target, ProtocolMessage message) {
            if (holding && message instanceof DHTMessage.PutRequest) {
                held.add(Map.entry(target, message));

                return;
            }

            route(target, message);
        }

        private void route(NodeId target, ProtocolMessage message) {
            var node = nodes.get(target);

            switch (message) {
                case DHTMessage.PutRequest request -> node.handlePutRequest(request, response -> deliver(request.sender(), response));
                case DHTMessage.PutResponse response -> clients.get(target).onPutResponse(response);
                default -> {}
            }
        }
    }
}
