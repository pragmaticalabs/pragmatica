/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #1818 round 3: a `MigrationDataResponse` is applied only when it is authentic. A pull answer must match
/// a pull this node sent — its correlation id, the node asked, and the partition asked for. An unsolicited
/// batch is accepted only from a sender with authority over it: a departure push (`ackRequested`) from a
/// node that is departing, a survivor-rebalance push from a co-replica of every entry's partition.
/// Everything else is dropped, with a WARN and a counter.
class DHTMigrationResponseAuthenticityTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, DHTConfig.DEFAULT_TIMEOUT);
    private static final byte[] VALUE = "payload".getBytes(StandardCharsets.UTF_8);

    @Test
    void unsolicitedBatch_fromANodeThatIsNotACoReplica_isNotStored() {
        var cluster = new Cluster();
        var shape = cluster.shape("stranger");

        cluster.receiver().antiEntropy()
               .onMigrationDataResponse(new DHTMessage.MigrationDataResponse("unsolicited", shape.stranger(), List.of(shape.entry()), false));

        assertThat(cluster.receiverHolds(shape.key())).as("a non-co-replica's unsolicited batch is dropped").isFalse();
        assertThat(cluster.rejected()).isEqualTo(1);
    }

    @Test
    void pullAnswer_fromANodeOtherThanTheOneAsked_isNotStored() {
        var cluster = new Cluster();
        var shape = cluster.shape("spoofed");
        var requestId = cluster.pullFromHolder(shape);

        cluster.receiver().antiEntropy()
               .onMigrationDataResponse(new DHTMessage.MigrationDataResponse(requestId, shape.stranger(), List.of(shape.entry()), false));

        assertThat(cluster.receiverHolds(shape.key())).as("an answer whose sender was not asked is dropped").isFalse();
        assertThat(cluster.rejected()).isEqualTo(1);

        cluster.receiver().antiEntropy()
               .onMigrationDataResponse(new DHTMessage.MigrationDataResponse(requestId, shape.holder(), List.of(shape.entry()), false));

        assertThat(cluster.receiverHolds(shape.key())).as("the forged answer did not consume the real one's slot").isTrue();
    }

    @Test
    void pullAnswer_carryingAnEntryOutsideTheRequestedPartition_isNotStored() {
        var cluster = new Cluster();
        var shape = cluster.shape("smuggled");
        var requestId = cluster.pullFromHolder(shape);
        var smuggled = cluster.entryOutside(shape.partition(), "smuggled-extra");

        cluster.receiver().antiEntropy()
               .onMigrationDataResponse(new DHTMessage.MigrationDataResponse(requestId,
                                                                             shape.holder(),
                                                                             List.of(shape.entry(), smuggled),
                                                                             false));

        assertThat(cluster.receiverHolds(smuggled.key())).as("an entry outside the requested partition is dropped").isFalse();
        assertThat(cluster.rejected()).isEqualTo(1);
    }

    @Test
    void departurePush_fromANodeThatIsNotDeparting_isNackedAndNotStored() {
        var cluster = new Cluster();
        var shape = cluster.shape("not-departing");

        cluster.receiver().antiEntropy()
               .onMigrationDataResponse(new DHTMessage.MigrationDataResponse("push", shape.stranger(), List.of(shape.entry()), true));

        assertThat(cluster.receiverHolds(shape.key())).isFalse();
        assertThat(cluster.ackFor("push")).as("the pusher is told the batch was not taken").isFalse();
        assertThat(cluster.rejected()).isEqualTo(1);
    }

    /// A node that drains ITSELF on quorum loss is in neither the leader's drain set nor this node's DEPARTING
    /// set, and it halts with its in-memory store. Its push is accepted on authority instead: it is a current
    /// replica, in this node's ring, of every entry's partition — so a copy it holds last is not lost.
    @Test
    void departurePush_fromASelfDrainingCurrentReplica_isStoredAndAcked() {
        var cluster = new Cluster();
        var shape = cluster.shape("self-drain");

        cluster.receiver().antiEntropy()
               .onMigrationDataResponse(new DHTMessage.MigrationDataResponse("push", shape.holder(), List.of(shape.entry()), true));

        assertThat(cluster.receiverHolds(shape.key())).isTrue();
        assertThat(cluster.ackFor("push")).isTrue();
        assertThat(cluster.rejected()).isZero();
    }

    /// #1818 round 4 (J2): a departure push places a copy only where it belongs. Entries for a partition this
    /// receiver replicates in neither the pre- nor the post-departure ring are not stored — a stray copy on a
    /// non-replica is what the #428 fallback can later resurrect — and the batch is nacked, so the pusher keeps
    /// those entries at risk.
    @Test
    void departurePush_entryForAPartitionThisNodeDoesNotReplicate_isNotStored_andTheBatchIsNacked() {
        var cluster = new Cluster();
        var pusher = new NodeId("node-1");
        var stray = cluster.entryNotReplicatedByReceiver(pusher, "stray");
        var placed = cluster.entryForPostDepartureNewcomer(pusher, "placed");

        cluster.departing(pusher);
        cluster.receiver().antiEntropy()
               .onMigrationDataResponse(new DHTMessage.MigrationDataResponse("push", pusher, List.of(placed, stray), true));

        assertThat(cluster.receiverHolds(stray.key())).as("the mis-addressed entry is not placed here").isFalse();
        assertThat(cluster.receiverHolds(placed.key())).as("control: the entry this node newly owns is placed").isTrue();
        assertThat(cluster.ackFor("push")).as("the pusher keeps the batch at risk").isFalse();
    }

    @Test
    void departurePush_fromADepartingNode_isStoredAndAcked() {
        var cluster = new Cluster();
        var shape = cluster.shape("departing");

        cluster.departing(shape.stranger());
        cluster.receiver().antiEntropy()
               .onMigrationDataResponse(new DHTMessage.MigrationDataResponse("push", shape.stranger(), List.of(shape.entry()), true));

        assertThat(cluster.receiverHolds(shape.key())).isTrue();
        assertThat(cluster.ackFor("push")).isTrue();
        assertThat(cluster.rejected()).isZero();
    }

    @Test
    void pullAnswer_fromTheNodeAsked_isStored() {
        var cluster = new Cluster();
        var shape = cluster.shape("legit");
        var requestId = cluster.pullFromHolder(shape);

        cluster.receiver().antiEntropy()
               .onMigrationDataResponse(new DHTMessage.MigrationDataResponse(requestId, shape.holder(), List.of(shape.entry()), false));

        assertThat(cluster.receiverHolds(shape.key())).as("control: a matching answer is applied").isTrue();
        assertThat(cluster.rejected()).isZero();
    }

    // --- harness: five nodes, every message to the receiver's peers is captured, never delivered ---

    private record Member(NodeId id, DHTNode node, DHTAntiEntropy antiEntropy) {}

    /// A key whose partition the receiver and the holder replicate and the stranger does not.
    private record Shape(byte[] key, Partition partition, NodeId holder, NodeId stranger, DHTMessage.KeyValue entry) {}

    private static final class Cluster {
        private final Map<NodeId, Member> members = new LinkedHashMap<>();
        private final List<Map.Entry<NodeId, ProtocolMessage>> sent = new CopyOnWriteArrayList<>();
        private final NodeId receiverId = new NodeId("node-0");
        private final Set<NodeId> departing = new HashSet<>();

        Cluster() {
            var ids = java.util.stream.IntStream.range(0, 5).mapToObj(i -> new NodeId("node-" + i)).toList();

            ids.forEach(id -> members.put(id, member(id, ids)));
        }

        private Member member(NodeId id, List<NodeId> ids) {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();

            ids.forEach(ring::addNode);

            var node = dhtNode(id, memoryStorageEngine(), ring, CONFIG);
            DHTNetwork network = (target, message) -> sent.add(Map.entry(target, message));

            return new Member(id, node, dhtAntiEntropy(node, network, CONFIG, departing::contains));
        }

        Member receiver() {
            return members.get(receiverId);
        }

        void departing(NodeId id) {
            departing.add(id);
        }

        long rejected() {
            return receiver().antiEntropy().rejectedResponseCount();
        }

        Shape shape(String prefix) {
            var ring = receiver().node().ring();

            for (int i = 0; i < 20_000; i++) {
                var key = (prefix + "-" + i).getBytes(StandardCharsets.UTF_8);
                var replicas = ring.nodesFor(key, 3);

                if (replicas.contains(receiverId)) {
                    var holder = replicas.stream().filter(id -> !id.equals(receiverId)).findFirst().orElseThrow();
                    var stranger = members.keySet().stream().filter(id -> !replicas.contains(id)).findFirst().orElseThrow();

                    return new Shape(key, ring.partitionFor(key), holder, stranger, new DHTMessage.KeyValue(key, VALUE, 1L, 0L, 0L, 0L));
                }
            }

            throw new AssertionError("no key the receiver replicates");
        }

        /// An entry whose partition the receiver replicates neither with `pusher` in the ring nor without it.
        DHTMessage.KeyValue entryNotReplicatedByReceiver(NodeId pusher, String prefix) {
            return entryWhere(prefix, key -> !receiverReplicates(key, pusher, true) && !receiverReplicates(key, pusher, false));
        }

        /// An entry whose partition the receiver replicates only once `pusher` has left — a departure newcomer.
        DHTMessage.KeyValue entryForPostDepartureNewcomer(NodeId pusher, String prefix) {
            return entryWhere(prefix, key -> !receiverReplicates(key, pusher, true) && receiverReplicates(key, pusher, false));
        }

        private boolean receiverReplicates(byte[] key, NodeId pusher, boolean withPusher) {
            return receiver().node().ring().nodesFor(key, 3, id -> withPusher || !id.equals(pusher)).contains(receiverId);
        }

        private DHTMessage.KeyValue entryWhere(String prefix, Predicate<byte[]> wanted) {
            for (int i = 0; i < 20_000; i++) {
                var key = (prefix + "-" + i).getBytes(StandardCharsets.UTF_8);

                if (wanted.test(key)) {
                    return new DHTMessage.KeyValue(key, VALUE, 1L, 0L, 0L, 0L);
                }
            }

            throw new AssertionError("no key of the wanted placement");
        }

        /// An entry whose key lies in a partition other than `partition`.
        DHTMessage.KeyValue entryOutside(Partition partition, String prefix) {
            var ring = receiver().node().ring();

            for (int i = 0; i < 20_000; i++) {
                var key = (prefix + "-" + i).getBytes(StandardCharsets.UTF_8);

                if (!ring.partitionFor(key).equals(partition)) {
                    return new DHTMessage.KeyValue(key, VALUE, 1L, 0L, 0L, 0L);
                }
            }

            throw new AssertionError("no key outside " + partition);
        }

        /// Make the receiver pull the shape's partition from the holder, as anti-entropy does when their
        /// digests differ, and return the pull's correlation id.
        String pullFromHolder(Shape shape) {
            members.get(shape.holder()).node().putLocal(shape.key(), VALUE).await();
            receiver().antiEntropy().synchronizeNow();

            var digestRequest = sentTo(shape.holder(), DHTMessage.DigestRequest.class, shape.partition());

            members.get(shape.holder()).node()
                   .handleDigestRequest(digestRequest, response -> receiver().antiEntropy().onDigestResponse(response));

            return sentTo(shape.holder(), DHTMessage.MigrationDataRequest.class, shape.partition()).requestId();
        }

        private <M extends ProtocolMessage> M sentTo(NodeId target, Class<M> type, Partition partition) {
            return sent.stream()
                       .filter(entry -> entry.getKey().equals(target))
                       .map(Map.Entry::getValue)
                       .filter(type::isInstance)
                       .map(type::cast)
                       .filter(message -> partitionOf(message) == partition.value())
                       .reduce((first, second) -> second)
                       .orElseThrow();
        }

        private static int partitionOf(ProtocolMessage message) {
            return switch (message) {
                case DHTMessage.DigestRequest request -> request.partitionStart();
                case DHTMessage.MigrationDataRequest request -> request.partitionStart();
                default -> -1;
            };
        }

        boolean receiverHolds(byte[] key) {
            return receiver().node().getLocal(key).await().or(Option.none()).isPresent();
        }

        boolean ackFor(String requestId) {
            return sent.stream()
                       .map(Map.Entry::getValue)
                       .filter(DHTMessage.MigrationDataAck.class::isInstance)
                       .map(DHTMessage.MigrationDataAck.class::cast)
                       .filter(ack -> ack.requestId().equals(requestId))
                       .findFirst()
                       .orElseThrow()
                       .applied();
        }
    }
}
