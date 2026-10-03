package org.pragmatica.dht;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.DHTMessage.Readiness;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTNode.dhtNodeAwaitingReplication;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #1777 track 1: the DHT takes its replication from the cluster's committed `[replication]` factors — W = CF,
/// R = RF − CF + 1 — and a node that has not read them yet neither places keys nor answers authoritatively.
class DHTReplicationResolutionTest {
    private static final List<NodeId> FIVE = IntStream.range(0, 5).mapToObj(i -> new NodeId("node-" + i)).toList();
    private static final DHTConfig RF3 = DHTConfig.DEFAULT.withFactors(3, 2).unwrap();
    private static final DHTConfig RF5 = DHTConfig.DEFAULT.withFactors(5, 3).unwrap();

    @Nested
    class Derivation {
        @Test
        void withFactors_rf3cf2_isWriteTwoReadTwo() {
            assertThat(RF3.writeQuorum()).isEqualTo(2);
            assertThat(RF3.readQuorum()).isEqualTo(2);
        }

        @Test
        void withFactors_rf5cf3_isWriteThreeReadThree() {
            assertThat(RF5.writeQuorum()).isEqualTo(3);
            assertThat(RF5.readQuorum()).isEqualTo(3);
        }

        @Test
        void withFactors_rf5cf2_readsFourSoEveryReadMeetsAWrite() {
            var config = DHTConfig.DEFAULT.withFactors(5, 2).unwrap();

            assertThat(config.writeQuorum()).isEqualTo(2);
            assertThat(config.readQuorum()).isEqualTo(4);
            assertThat(config.hasQuorumOverlap()).isTrue();
        }

        @Test
        void withFactors_confirmationAboveFactor_isRefused() {
            assertThat(DHTConfig.DEFAULT.withFactors(3, 4).isFailure()).isTrue();
        }

        @Test
        void withFactors_keepsTimeoutAndRetryPolicy() {
            assertThat(RF5.operationTimeout()).isEqualTo(DHTConfig.DEFAULT.operationTimeout());
            assertThat(RF5.retryPolicy()).isEqualTo(DHTConfig.DEFAULT.retryPolicy());
        }
    }

    @Nested
    class Unresolved {
        private final List<ProtocolMessage> sent = new ArrayList<>();
        private final DHTNode node = awaiting(FIVE.getFirst());
        private final DHTNetwork network = (_, message) -> sent.add(message);
        private final DistributedDHTClient client = distributedDHTClient(node, network, OwnerEpochSource.zero());
        private final byte[] key = "unresolved".getBytes(StandardCharsets.UTF_8);

        @Test
        void quorumOperations_refuseRetryably_andSendNothing() {
            assertThat(failureOf(client.get(key).await())).isEqualTo(DHTError.ReplicationUnresolved.class);
            assertThat(failureOf(client.put(key, key).await())).isEqualTo(DHTError.ReplicationUnresolved.class);
            assertThat(failureOf(client.remove(key).await())).isEqualTo(DHTError.ReplicationUnresolved.class);
            assertThat(failureOf(client.exists(key).await())).isEqualTo(DHTError.ReplicationUnresolved.class);
            assertThat(sent).isEmpty();
        }

        /// A node booted with a placeholder RF3 into an RF5 cluster replicates partitions its placeholder ring does
        /// not show. Answering "absent" as SERVING for one of them would be a false absent vote.
        @Test
        void everyPartition_answersCatchingUp_untilResolved() {
            var notOwnedUnderPlaceholder = IntStream.range(0, Partition.MAX_PARTITIONS)
                                                    .mapToObj(Partition::at)
                                                    .filter(partition -> !node.ring().nodesFor(partition, 3).contains(node.nodeId()))
                                                    .findFirst()
                                                    .orElseThrow();

            assertThat(node.readiness(notOwnedUnderPlaceholder)).isEqualTo(Readiness.CATCHING_UP);

            node.resolveReplication(RF3);

            assertThat(node.readiness(notOwnedUnderPlaceholder)).isEqualTo(Readiness.SERVING);
            assertThat(node.replicationResolved()).isTrue();
        }

        @Test
        void antiEntropy_doesNotRun_untilResolved() {
            var antiEntropy = DHTAntiEntropy.dhtAntiEntropy(node, network, _ -> false);

            node.beginCatchUp();
            antiEntropy.synchronizeNow();
            antiEntropy.catchUpNow();

            assertThat(sent).isEmpty();
        }
    }

    @Nested
    class Change {
        /// A raised replication factor makes this node a replica of partitions it did not hold. Those must refuse
        /// "absent" until filled — exactly as a ring change does — or a read meets an empty new replica as an
        /// authoritative one.
        @Test
        void raisedFactor_marksEveryGainedPartitionCatchingUp() {
            var node = resolved(FIVE.getFirst(), RF3);
            var gained = partitionsWhere(node, partition -> !replicaUnder(node, partition, 3) && replicaUnder(node, partition, 5));
            var kept = partitionsWhere(node, partition -> replicaUnder(node, partition, 3));

            assertThat(gained).isNotEmpty();

            node.resolveReplication(RF5);

            assertThat(gained).allMatch(partition -> node.readiness(partition) == Readiness.CATCHING_UP);
            assertThat(kept).allMatch(partition -> node.readiness(partition) == Readiness.SERVING);
            assertThat(node.config()).isEqualTo(RF5);
        }

        @Test
        void loweredFactor_forgetsAPendingPartitionItNoLongerHolds() {
            var node = resolved(FIVE.getFirst(), RF5);

            node.beginCatchUp();
            var lost = partitionsWhere(node, partition -> replicaUnder(node, partition, 5) && !replicaUnder(node, partition, 3));

            assertThat(lost).isNotEmpty().allMatch(partition -> node.readiness(partition) == Readiness.CATCHING_UP);

            node.resolveReplication(RF3);

            assertThat(lost).allMatch(partition -> node.readiness(partition) == Readiness.SERVING);
        }

        /// End to end over an in-JVM network: a value written at RF3/CF2 stays readable through a committed change to
        /// RF5/CF3 — each read returns the value or a retryable refusal, never "absent" — and catch-up fills the new
        /// replicas.
        @Test
        void raisedFactor_valueStaysReadable_andNewReplicasAreFilled() {
            var cluster = new Cluster(RF3);
            var key = "re-placed".getBytes(StandardCharsets.UTF_8);
            var writer = cluster.clients.get(FIVE.getFirst());

            assertThat(writer.put(key, "v".getBytes(StandardCharsets.UTF_8)).await().isSuccess()).isTrue();

            cluster.nodes.values().forEach(node -> node.resolveReplication(RF5));

            for (var reader : cluster.clients.values()) {
                reader.get(key)
                      .await()
                      .onSuccess(value -> assertThat(value.isPresent()).as("never a false absent").isTrue())
                      .onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.NotCaughtUp.class));
            }

            for (int round = 0; round < 3; round++) {
                cluster.antiEntropies.values().forEach(DHTAntiEntropy::catchUpNow);
            }

            assertThat(cluster.nodes.values()).allMatch(node -> node.getLocal(key).await().or(Option.none()).isPresent());
            assertThat(new String(writer.get(key).await().unwrap().unwrap(), StandardCharsets.UTF_8)).isEqualTo("v");
        }
    }

    private static Class<?> failureOf(org.pragmatica.lang.Result<?> result) {
        return result.fold(cause -> cause.getClass(), _ -> Void.class);
    }

    private static DHTNode awaiting(NodeId id) {
        return dhtNodeAwaitingReplication(id, memoryStorageEngine(), ring(), DHTConfig.DEFAULT, HlcClock.hlcClock(id));
    }

    private static DHTNode resolved(NodeId id, DHTConfig config) {
        return dhtNode(id, memoryStorageEngine(), ring(), config);
    }

    private static ConsistentHashRing<NodeId> ring() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        FIVE.forEach(ring::addNode);

        return ring;
    }

    private static boolean replicaUnder(DHTNode node, Partition partition, int replicationFactor) {
        return node.ring().nodesFor(partition, replicationFactor).contains(node.nodeId());
    }

    private static List<Partition> partitionsWhere(DHTNode node, java.util.function.Predicate<Partition> filter) {
        return IntStream.range(0, Partition.MAX_PARTITIONS).mapToObj(Partition::at).filter(filter).toList();
    }

    /// Five nodes on a synchronous in-JVM network: every request is answered inline.
    private static final class Cluster {
        final Map<NodeId, DHTNode> nodes = new LinkedHashMap<>();
        final Map<NodeId, DHTAntiEntropy> antiEntropies = new LinkedHashMap<>();
        final Map<NodeId, DistributedDHTClient> clients = new LinkedHashMap<>();

        Cluster(DHTConfig config) {
            FIVE.forEach(id -> nodes.put(id, resolved(id, config)));
            FIVE.forEach(id -> antiEntropies.put(id, dhtAntiEntropy(nodes.get(id), (target, message) -> route(target, message), _ -> false)));
            FIVE.forEach(id -> clients.put(id, distributedDHTClient(nodes.get(id), (target, message) -> route(target, message), OwnerEpochSource.zero())));
        }

        private void route(NodeId target, ProtocolMessage message) {
            var node = nodes.get(target);
            var antiEntropy = antiEntropies.get(target);
            var client = clients.get(target);

            switch (message) {
                case DHTMessage.GetRequest r -> node.handleGetRequest(r, resp -> route(r.sender(), resp));
                case DHTMessage.GetResponse r -> client.onGetResponse(r);
                case DHTMessage.PutRequest r -> node.handlePutRequest(r, resp -> route(r.sender(), resp));
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
