package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.lang.Promise;

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

/// The sweep v1882 wrote (adapted from its V1882SameEpochSweepTest: package, name and the step parameter): the SAME-EPOCH two-writer shape of F15 on the owner-epoch (#1818) path, with
/// no hand-chosen schedule. Replicas A (deposed writer), R (writer; its high-water advances mid-run) and N (already
/// advanced); RF3 / W2; both writers stamp the OLD epoch. R writes X, then A writes W (a put, or a remove). The four
/// remote requests are held, then delivered in EVERY order, with R's advance inserted at EVERY position (24 x 5 = 120
/// schedules). Invariant: an acknowledged X is held — X or anything newer — by at least W = 2 replicas.
class DHTSameEpochSweepTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, timeSpan(500).millis());
    /// The pause after issuing and after each delivery. Against the fixture's evidence bound (operationTimeout / 10 = 50 ms):
    /// ABOVE it, every schedule opens with a silence longer than the bound (the named no-evidence residual); BELOW it, every
    /// delivery lands inside the evidence window. Both are run.
    private static final long FAST_STEP_MS = 10L;
    private static final long SLOW_STEP_MS = 70L;
    private static final long[] OLD_EPOCH = {0L, 1L, 1L};
    private static final long[] NEW_EPOCH = {0L, 2L, 2L};
    private static final NodeId A = new NodeId("a-deposed");
    private static final NodeId R = new NodeId("r-advancing");
    private static final NodeId N = new NodeId("n-new");
    private static final List<NodeId> ALL = List.of(A, R, N);
    private static final byte[] KEY = "sweep-1818".getBytes(StandardCharsets.UTF_8);

    @Test
    void put_everySchedule_deliveriesInsideTheEvidenceWait() throws Exception {
        sweep(false, FAST_STEP_MS);
    }

    @Test
    void put_everySchedule_silenceLongerThanTheEvidenceWait() throws Exception {
        sweep(false, SLOW_STEP_MS);
    }

    private static void sweep(boolean removeMode, long stepMs) throws Exception {
        var violations = new ArrayList<String>();
        var xAcked = 0;
        var runs = 0;
        long maxX = 0, maxW = 0;
        var timeouts = new ArrayList<String>();

        for (var order : permutations(List.of(0, 1, 2, 3))) {
            for (int advanceAt = 0; advanceAt <= 4; advanceAt++) {
                var outcome = runOnce(removeMode, stepMs, order, advanceAt);

                runs++;
                maxX = Math.max(maxX, outcome.xMs());
                maxW = Math.max(maxW, outcome.wMs());
                if (outcome.anyTimeout() || outcome.xMs() >= CONFIG.operationTimeout().millis() || outcome.wMs() >= CONFIG.operationTimeout().millis()) {
                    timeouts.add("order=" + order + " advanceAt=" + advanceAt + " xMs=" + outcome.xMs() + " wMs=" + outcome.wMs());
                }
                if (outcome.xAcked()) {
                    xAcked++;
                }
                if (outcome.violation() != null) {
                    violations.add(outcome.violation());
                }
            }
        }

        System.out.println("SWEEP mode=" + (removeMode ? "remove" : "put") + " stepMs=" + stepMs + " runs=" + runs + " xAcked=" + xAcked
                           + " violations=" + violations.size()
                           + " maxXms=" + maxX + " maxWms=" + maxW + " timeouts=" + timeouts.size());
        timeouts.stream().limit(3).forEach(t -> System.out.println("SWEEP   timeout " + t));
        violations.stream().limit(5).forEach(v -> System.out.println("SWEEP   " + v));
        assertThat(xAcked).as("arming: some schedules acknowledge X").isGreaterThan(0);
        assertThat(violations).as("schedules where an acknowledged X is on fewer than W = 2 replicas").isEmpty();
    }

    private record Outcome(boolean xAcked, String violation, long xMs, long wMs, boolean anyTimeout) {}

    private static Outcome runOnce(boolean removeMode, long stepMs, List<Integer> order, int advanceAt) throws Exception {
        var cluster = new Cluster();

        cluster.holding = true;
        var started = System.nanoTime();
        var xDone = new java.util.concurrent.atomic.AtomicLong(-1);
        var wDone = new java.util.concurrent.atomic.AtomicLong(-1);
        var x = cluster.clients.get(R).put(KEY, "X".getBytes(StandardCharsets.UTF_8));
        x.onResult(_ -> xDone.set((System.nanoTime() - started) / 1_000_000L));
        Thread.sleep(3);
        Promise<?> w = cluster.clients.get(A).put(KEY, "W".getBytes(StandardCharsets.UTF_8));
        w.onResult(_ -> wDone.set((System.nanoTime() - started) / 1_000_000L));
        cluster.holding = false;
        Thread.sleep(stepMs);

        // held requests by role: 0 = X->A, 1 = X->N, 2 = W->R, 3 = W->N
        var byRole = new ArrayList<ProtocolMessage>();
        var byTarget = new ArrayList<NodeId>();

        for (var role : List.of(new Object[]{R, A}, new Object[]{R, N}, new Object[]{A, R}, new Object[]{A, N})) {
            var sender = (NodeId) role[0];
            var target = (NodeId) role[1];
            var found = cluster.held.stream()
                                    .filter(e -> e.getKey().equals(target) && senderOf(e.getValue()).equals(sender))
                                    .findFirst()
                                    .orElseThrow(() -> new AssertionError("arming: no held request " + sender.id() + "->" + target.id()
                                                                          + " in " + cluster.held));

            byRole.add(found.getValue());
            byTarget.add(target);
        }

        cluster.held.clear();
        for (int step = 0; step <= order.size(); step++) {
            if (step == advanceAt) {
                cluster.gates.get(R).advance(KEY, NEW_EPOCH[0], NEW_EPOCH[1], NEW_EPOCH[2]);
            }
            if (step < order.size()) {
                var role = order.get(step);

                cluster.route(byTarget.get(role), byRole.get(role));
                Thread.sleep(stepMs);
            }
        }

        var xOutcome = x.await(timeSpan(2).seconds());
        var wOutcome = w.await(timeSpan(2).seconds());
        Thread.sleep(stepMs);
        boolean anyTimeout = xOutcome.fold(c -> c.message().contains("imeout"), _ -> false)
                             || wOutcome.fold(c -> c.message().contains("imeout"), _ -> false)
                             || xDone.get() < 0 || wDone.get() < 0;

        if (xOutcome.isFailure()) {
            return new Outcome(false, null, xDone.get(), wDone.get(), anyTimeout);
        }

        // X's version is its write stamp, read from the held request: X may be superseded (legitimately) everywhere
        var xVersion = ((DHTMessage.PutRequest) byRole.get(0)).version();

        var atOrAbove = ALL.stream().filter(id -> cluster.entryOf(id).map(e -> e.version() >= xVersion).orElse(false)).count();

        return new Outcome(true, atOrAbove >= 2
                                 ? null
                                 : "order=" + order + " advanceAt=" + advanceAt + " xVersion=" + xVersion + " atOrAboveX=" + atOrAbove + " "
                                   + cluster.dump(), xDone.get(), wDone.get(), anyTimeout);
    }

    private static NodeId senderOf(ProtocolMessage message) {
        return switch (message) {
            case DHTMessage.PutRequest request -> request.sender();
            case DHTMessage.RemoveRequest request -> request.sender();
            default -> new NodeId("?");
        };
    }

    private static List<List<Integer>> permutations(List<Integer> items) {
        if (items.size() <= 1) {
            return List.of(items);
        }

        var result = new ArrayList<List<Integer>>();

        for (var head : items) {
            var rest = new ArrayList<>(items);

            rest.remove(head);
            for (var tail : permutations(rest)) {
                var p = new ArrayList<Integer>();

                p.add(head);
                p.addAll(tail);
                result.add(p);
            }
        }

        return result;
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
        final List<Map.Entry<NodeId, ProtocolMessage>> held = new java.util.concurrent.CopyOnWriteArrayList<>();
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

        java.util.Optional<DHTMessage.KeyValue> entryOf(NodeId id) {
            return nodes.get(id).storage().entries().await().or(List.of()).stream()
                        .filter(entry -> Arrays.equals(entry.key(), KEY))
                        .findFirst();
        }

        String dump() {
            return ALL.stream()
                      .map(id -> id.id() + "=" + entryOf(id).map(e -> new String(e.value(), StandardCharsets.UTF_8)
                                                                      + "@" + e.version())
                                                            .orElse("absent"))
                      .toList()
                      .toString();
        }

        private void deliver(NodeId target, ProtocolMessage message) {
            if (holding && (message instanceof DHTMessage.PutRequest || message instanceof DHTMessage.RemoveRequest)) {
                held.add(Map.entry(target, message));

                return;
            }

            route(target, message);
        }

        void route(NodeId target, ProtocolMessage message) {
            var node = nodes.get(target);

            switch (message) {
                case DHTMessage.PutRequest request -> node.handlePutRequest(request, response -> deliver(request.sender(), response));
                case DHTMessage.PutResponse response -> clients.get(target).onPutResponse(response);
                case DHTMessage.RemoveRequest request -> node.handleRemoveRequest(request, response -> deliver(request.sender(), response));
                case DHTMessage.RemoveResponse response -> clients.get(target).onRemoveResponse(response);
                default -> {}
            }
        }
    }
}
