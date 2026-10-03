package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.DHTMessage.Readiness;
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #1777 track 1, CTO ruling R1 (from v1882's probe): ANY live change of the committed (RF, CF) — not only one that
/// gains a partition — must keep every value acked under the OLD factors readable. A CF raise at a fixed RF gains no
/// partition yet lowers the read quorum below what the old writes were acked at; an RF decrease moves the replica set
/// away from where the old copies are. Until the new replica set has been filled from the old one, a node reads and
/// writes with the transitional quorums max(old, new), and every replica refuses "absent" (the catch-up gate).
class DHTReplicationChangeTest {
    private static final byte[] KEY = "change-key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VALUE = "v".getBytes(StandardCharsets.UTF_8);

    /// RF3/CF1 -> RF3/CF2: old W1 R3, new W2 R2; W_old + R_new = 3 is not > 3. The write reached ONE replica.
    @Test
    void cfRaiseAtFixedRf_threeNodeRing_valueAckedUnderTheOldFactors_staysReadable_andIsFilledEverywhere() {
        cfRaiseAtFixedRf(3);
    }

    /// Five nodes: the #428 fallback has two non-replicas to probe, which must not be what saves the read.
    @Test
    void cfRaiseAtFixedRf_fiveNodeRing_valueAckedUnderTheOldFactors_staysReadable_andIsFilledEverywhere() {
        cfRaiseAtFixedRf(5);
    }

    private static void cfRaiseAtFixedRf(int size) {
        var cluster = new Cluster(size, factors(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);

        cluster.putReachingOnly(replicas.getLast(), replicas.getLast());
        cluster.changeAll(factors(3, 2));

        assertThat(replicas).as("the change re-opened the catch-up gate on every replica")
                            .allMatch(id -> cluster.readiness(id) == Readiness.CATCHING_UP);
        assertThat(cluster.config(replicas.getFirst()).readQuorum()).as("the transitional read quorum is max(R_old, R_new)")
                                                                     .isEqualTo(3);
        assertThat(cluster.valueOrRefusal(replicas.getFirst())).as("read during the change: the value or a retryable refusal, never absent")
                                                                .isTrue();

        cluster.catchUp(3);

        assertThat(replicas).as("every key is on the new replica set").allMatch(cluster::holds);
        assertThat(cluster.config(replicas.getFirst()).readQuorum()).as("settled: the new read quorum").isEqualTo(2);
        assertThat(cluster.config(replicas.getFirst()).writeQuorum()).isEqualTo(2);
        assertThat(cluster.read(replicas.getFirst())).isEqualTo(Option.some("v"));
        assertThat(cluster.fallbackResolutions.get()).as("no #428 fallback was needed").isZero();
    }

    /// RF5/CF2 -> RF3/CF2 on five nodes: the write reached only replicas 4 and 5 of the old set, neither of which is in
    /// the new set {1, 2, 3}. Without the gate the new set reads "absent" and only the #428 fallback probe could rescue
    /// it; with it, the read refuses (retryable) until the new set has pulled from the old holders.
    @Test
    void rfDecrease_valueOnlyOutsideTheNewReplicaSet_isNeverAbsent_andNeedsNoFallback() {
        var cluster = new Cluster(5, factors(5, 2));
        var replicas = cluster.replicasOf(KEY, 5);

        cluster.putReachingOnly(replicas.get(3), replicas.get(3), replicas.get(4));
        cluster.changeAll(factors(3, 2));

        var reader = replicas.getFirst();

        assertThat(cluster.valueOrRefusal(reader)).as("during the change: the value or a retryable refusal, never absent")
                                                  .isTrue();

        cluster.catchUp(3);

        assertThat(cluster.replicasOf(KEY, 3)).as("every key is on the new replica set").allMatch(cluster::holds);
        assertThat(cluster.read(reader)).isEqualTo(Option.some("v"));
        assertThat(cluster.fallbackResolutions.get()).as("no #428 fallback was needed").isZero();
    }

    /// The transitional quorums are what protect a value written by a node that has NOT YET applied the change (it
    /// writes at W_old) after two of the three replicas have already caught up: a coordinator still settling reads
    /// R_t = 3 and reaches the one replica holding it. With R_new = 2 it would read two caught-up, authoritative
    /// "absent" answers and return a false "absent".
    @Test
    void laggingWriterAtTheOldQuorum_isReadByASettlingCoordinator() {
        var cluster = new Cluster(5, factors(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var coordinator = cluster.nonReplicaOf(replicas);

        cluster.changeAll(factors(3, 2));
        cluster.catchUpOnly(replicas.get(0), replicas.get(1));
        assertThat(cluster.readiness(replicas.get(0))).as("arming: the first two replicas have caught up").isEqualTo(Readiness.SERVING);
        assertThat(cluster.readiness(replicas.get(1))).isEqualTo(Readiness.SERVING);

        var lagging = distributedDHTClient(cluster.nodes.get(replicas.get(2)), cluster::route, factors(3, 1));

        cluster.dropPutsTo = Set.of(replicas.get(0), replicas.get(1));
        assertThat(lagging.put(KEY, VALUE).await().isSuccess()).as("acked at the old W1 by a node yet to apply the change").isTrue();
        cluster.dropPutsTo = Set.of();

        assertThat(cluster.config(coordinator).readQuorum()).as("arming: the coordinator is still settling").isEqualTo(3);
        assertThat(cluster.read(coordinator)).as("never a false absent").isEqualTo(Option.some("v"));
    }

    /// A second change while the first is still settling keeps the strictest quorums of all of them.
    @Test
    void secondChangeWhileSettling_keepsTheStrictestQuorums() {
        var cluster = new Cluster(5, factors(5, 4));
        var node = cluster.anyId();

        cluster.changeAll(factors(5, 2));
        cluster.changeAll(factors(3, 1));

        assertThat(cluster.config(node).writeQuorum()).as("min(max(4, 2, 1), RF 3)").isEqualTo(3);
        assertThat(cluster.config(node).readQuorum()).as("min(max(2, 4, 3), RF 3)").isEqualTo(3);
    }

    private static DHTConfig factors(int replicationFactor, int confirmationFactor) {
        return DHTConfig.DEFAULT.withFactors(replicationFactor, confirmationFactor).unwrap();
    }

    private static final class Cluster {
        final Map<NodeId, DHTNode> nodes = new LinkedHashMap<>();
        final Map<NodeId, DHTAntiEntropy> antiEntropies = new LinkedHashMap<>();
        final Map<NodeId, DistributedDHTClient> clients = new LinkedHashMap<>();
        final AtomicInteger fallbackResolutions = new AtomicInteger();
        volatile Set<NodeId> dropPutsTo = Set.of();

        Cluster(int size, DHTConfig config) {
            var ids = IntStream.range(0, size).mapToObj(i -> new NodeId("node-" + i)).toList();
            var observer = new ResolveFallbackObserver() {
                @Override
                public void onResolvedViaFallback(String keyHex, int probed) {
                    fallbackResolutions.incrementAndGet();
                }

                @Override
                public void onUnresolvedAfterFallback(ResolveMiss miss) {}
            };

            ids.forEach(id -> {
                var ring = ConsistentHashRing.<NodeId>consistentHashRing();

                ids.forEach(ring::addNode);
                nodes.put(id, dhtNode(id, memoryStorageEngine(), ring, config));
            });
            ids.forEach(id -> antiEntropies.put(id, dhtAntiEntropy(nodes.get(id), this::route, _ -> false)));
            ids.forEach(id -> clients.put(id,
                                          distributedDHTClient(nodes.get(id), this::route, OwnerEpochSource.zero())
                                              .withResolveFallbackObserver(observer)));
        }

        NodeId anyId() {
            return nodes.keySet().iterator().next();
        }

        List<NodeId> replicasOf(byte[] key, int replicationFactor) {
            return nodes.get(anyId()).ring().nodesFor(key, replicationFactor);
        }

        /// Put through `writer` while every replica except `reaching` drops the request.
        void putReachingOnly(NodeId writer, NodeId... reaching) {
            var reached = Set.of(reaching);

            dropPutsTo = Set.copyOf(nodes.keySet().stream().filter(id -> !reached.contains(id)).toList());
            assertThat(clients.get(writer).put(KEY, VALUE).await().isSuccess()).as("acked under the old factors").isTrue();
            dropPutsTo = Set.of();
            assertThat(nodes.keySet().stream().filter(id -> !reached.contains(id)))
                .as("arming: only the reached replicas hold the value")
                .noneMatch(this::holds);
        }

        void changeAll(DHTConfig config) {
            nodes.values().forEach(node -> node.resolveReplication(config));
        }

        void catchUpOnly(NodeId... ids) {
            for (int round = 0; round < 3; round++) {
                for (var id : ids) {
                    antiEntropies.get(id).catchUpNow();
                }
            }
        }

        NodeId nonReplicaOf(List<NodeId> replicas) {
            return nodes.keySet().stream().filter(id -> !replicas.contains(id)).findFirst().orElseThrow();
        }

        boolean valueOrRefusal(NodeId reader) {
            return clients.get(reader)
                          .get(KEY)
                          .await()
                          .fold(cause -> cause instanceof DHTError.NotCaughtUp, value -> value.isPresent());
        }

        void catchUp(int rounds) {
            for (int round = 0; round < rounds; round++) {
                antiEntropies.values().forEach(DHTAntiEntropy::catchUpNow);
            }
        }

        boolean holds(NodeId id) {
            return nodes.get(id).getLocal(KEY).await().or(Option.none()).isPresent();
        }

        Readiness readiness(NodeId id) {
            return nodes.get(id).readiness(nodes.get(id).partitionFor(KEY));
        }

        DHTConfig config(NodeId id) {
            return nodes.get(id).config();
        }

        DistributedDHTClient client(NodeId id) {
            return clients.get(id);
        }

        Option<String> read(NodeId id) {
            return clients.get(id).get(KEY).await().unwrap().map(bytes -> new String(bytes, StandardCharsets.UTF_8));
        }

        void route(NodeId target, ProtocolMessage message) {
            var node = nodes.get(target);
            var antiEntropy = antiEntropies.get(target);
            var client = clients.get(target);

            switch (message) {
                case DHTMessage.GetRequest r -> node.handleGetRequest(r, resp -> route(r.sender(), resp));
                case DHTMessage.GetResponse r -> client.onGetResponse(r);
                case DHTMessage.PutRequest r -> {
                    if (!dropPutsTo.contains(target)) {
                        node.handlePutRequest(r, resp -> route(r.sender(), resp));
                    }
                }
                case DHTMessage.PutResponse r -> client.onPutResponse(r);
                case DHTMessage.DigestRequest r -> node.handleDigestRequest(r, resp -> route(r.sender(), resp));
                case DHTMessage.DigestResponse r -> antiEntropy.onDigestResponse(r);
                case DHTMessage.MigrationDataRequest r -> node.handleMigrationDataRequest(r, resp -> route(r.sender(), resp));
                case DHTMessage.MigrationDataResponse r -> antiEntropy.onMigrationDataResponse(r);
                default -> {}
            }
        }
    }
}
