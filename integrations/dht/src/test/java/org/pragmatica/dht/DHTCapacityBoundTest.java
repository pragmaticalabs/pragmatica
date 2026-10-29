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

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.storage.DhtPrefixAccounting;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2258 spec 3.2 item 7 across a cluster (NT-125, NT-133): five nodes with their own caps over an in-process fabric. A put
/// that cannot reach its write quorum for capacity fails `ReplicaFull` and stores nothing; a put that reaches it while one
/// replica refuses succeeds with fewer than RF copies; the fallback read's re-homing put is a repair write and is accepted
/// above the cap.
class DHTCapacityBoundTest {
    private static final TimeSpan OP_TIMEOUT = timeSpan(2).seconds();
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, OP_TIMEOUT);
    private static final long CAP = 100L;

    private static byte[] key(String name) {
        return ("stream-segments/" + name).getBytes(StandardCharsets.UTF_8);
    }

    private static final class Fabric {
        private final Map<NodeId, Member> members = new LinkedHashMap<>();
        private final ToLongFunction<NodeId> capOf;

        Fabric(ToLongFunction<NodeId> capOf) {
            this.capOf = capOf;
        }

        Fabric withNodes(int count) {
            for (var i = 1; i <= count; i++) {
                add(new NodeId("n" + i));
            }

            return this;
        }

        private void add(NodeId id) {
            members.values().forEach(existing -> existing.ring().addNode(id));
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();

            members.keySet().forEach(ring::addNode);
            ring.addNode(id);
            var storage = memoryStorageEngine(OwnerEpochGate.noOp(), DhtPrefixAccounting.dhtPrefixAccounting(capOf.applyAsLong(id)));
            var node = dhtNode(id, storage, ring, CONFIG);
            DHTNetwork network = this::deliver;

            members.put(id, new Member(id, node, storage, distributedDHTClient(node, network, CONFIG), ring));
        }

        List<NodeId> rSetFor(byte[] key) {
            return members.values().iterator().next().ring().nodesFor(key, CONFIG.effectiveReplicationFactor(members.size()));
        }

        NodeId outsiderFor(byte[] key) {
            var rSet = new HashSet<>(rSetFor(key));

            return members.keySet().stream().filter(id -> !rSet.contains(id)).findFirst().orElseThrow();
        }

        Member member(NodeId id) {
            return members.get(id);
        }

        boolean holds(NodeId id, byte[] key) {
            return members.get(id).node().getLocal(key).await().map(Option::isPresent).or(false);
        }

        /// Fills a member's `stream-segments` to its cap with a block of its own, written locally as a client put.
        void fill(NodeId id) {
            member(id).node().putLocal(key("fill-" + id.id()), new byte[(int) CAP]).await();
        }

        private void deliver(NodeId target, ProtocolMessage message) {
            var member = members.get(target);

            switch (message) {
                case DHTMessage.GetRequest request -> member.node().handleGetRequest(request, response -> members.get(request.sender()).client().onGetResponse(response));
                case DHTMessage.PutRequest request -> member.node().handlePutRequest(request, response -> members.get(request.sender()).client().onPutResponse(response));
                default -> {}
            }
        }
    }

    private record Member(NodeId id, DHTNode node, MemoryStorageEngine storage, DistributedDHTClient client, ConsistentHashRing<NodeId> ring) {}

    @Test
    void aPutThatCannotReachW_forCapacity_failsReplicaFull_andStoresNothing() {
        var fabric = new Fabric(_ -> CAP).withNodes(5);
        var block = key("block-1");
        var rSet = fabric.rSetFor(block);

        rSet.forEach(fabric::fill);

        var failure = fabric.member(rSet.getFirst()).client().put(block, new byte[50]).await().<Cause>fold(cause -> cause, _ -> null);

        assertThat(failure).as("every replica refused for capacity").isInstanceOf(DHTError.ReplicaFull.class);
        assertThat(((DHTError.ReplicaFull) failure).maxBytes()).isEqualTo(CAP);
        rSet.forEach(id -> assertThat(fabric.holds(id, block)).as("%s stored nothing", id.id()).isFalse());
        rSet.forEach(id -> assertThat(fabric.member(id).storage().usedBytes("stream-segments")).isEqualTo(CAP));
    }

    @Test
    void aPutThatReachesW_whileOneReplicaRefuses_succeeds_withFewerThanRfCopies_andCountsTheRefusal() {
        var block = key("block-2");
        var fabric = new Fabric(_ -> CAP).withNodes(5);
        var rSet = fabric.rSetFor(block);
        var full = rSet.getLast();

        fabric.fill(full);

        var put = fabric.member(rSet.getFirst()).client().put(block, new byte[50]).await();

        assertThat(put.isSuccess()).as("two of the three replicas accepted").isTrue();
        assertThat(fabric.holds(full, block)).as("the refusing replica holds no copy").isFalse();
        assertThat(rSet.stream().filter(id -> fabric.holds(id, block)).count()).isEqualTo(2L);
        assertThat(fabric.member(full).storage().refusedPuts()).isEqualTo(1L);
    }

    /// NT-133: the fallback read re-homes a stranded copy with a repair put, which every replica accepts above its cap.
    @Test
    void aReHomingPut_isARepairWrite_acceptedAboveTheCap() {
        var fabric = new Fabric(_ -> CAP).withNodes(5);
        var stranded = key("stranded");
        var rSet = fabric.rSetFor(stranded);
        var holder = fabric.outsiderFor(stranded);

        fabric.member(holder).node().putLocal(stranded, new byte[10]).await();
        rSet.forEach(fabric::fill);

        var read = fabric.member(rSet.getFirst()).client().get(stranded).await();

        assertThat(read.map(Option::isPresent).or(false)).as("the fallback read finds the copy").isTrue();
        rSet.forEach(id -> assertThat(fabric.holds(id, stranded)).as("%s holds the re-homed copy", id.id()).isTrue());
        rSet.forEach(id -> assertThat(fabric.member(id).storage().usedBytes("stream-segments")).as("accounted, past the cap")
                                                                                                .isEqualTo(CAP + 10));
    }
}
