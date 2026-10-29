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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.consensus.ProtocolMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #2258 spec 3.2 item 7, the writer's side (NT-133, MT-59): a capacity refusal reaches the writer as `ReplicaFull`, not as
/// the catch-all timeout, and wins over every other answer when the put misses its write quorum.
class DistributedDHTClientCapacityTest {
    private static final NodeId LOCAL = new NodeId("local");
    private static final NodeId REMOTE_1 = new NodeId("remote-1");
    private static final NodeId REMOTE_2 = new NodeId("remote-2");

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private DistributedDHTClient client;

    private record Sent(NodeId target, ProtocolMessage message) {}

    @BeforeEach
    void setUp() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(LOCAL);
        ring.addNode(REMOTE_1);
        ring.addNode(REMOTE_2);
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, DHTConfig.DEFAULT);

        // RF=3, W=2: every node is a target
        client = distributedDHTClient(node, (target, message) -> sent.add(new Sent(target, message)), DHTConfig.DEFAULT);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private List<Sent> putRequests() {
        return sent.stream()
                   .filter(message -> message.message() instanceof DHTMessage.PutRequest)
                   .toList();
    }

    private static String idOf(Sent sent) {
        return ((DHTMessage.PutRequest) sent.message()).requestId();
    }

    private DHTMessage.PutResponse plainRefusal(Sent request) {
        return new DHTMessage.PutResponse(idOf(request), request.target(), false, false, false, false, false);
    }

    private DHTMessage.PutResponse capacityRefusal(Sent request) {
        return DHTMessage.PutResponse.capacityRefusal(idOf(request), request.target(), 100L, 128L);
    }

    private Sent requestTo(NodeId replica) {
        return putRequests().stream()
                            .filter(request -> request.target().equals(replica))
                            .findFirst()
                            .orElseThrow();
    }

    /// MT-59 gate: mapped to the catch-all, the put would fail `QuorumNotReached` or time out, naming nothing.
    @Test
    void aPutMissingW_withOneCapacityRefusalAndOneTimeout_failsReplicaFull() {
        var result = client.put(bytes("stream-segments/block"), new byte[10]);

        client.onPutResponse(capacityRefusal(requestTo(REMOTE_1)));
        client.onPutResponse(plainRefusal(requestTo(REMOTE_2)));

        var failure = result.await().<Cause>fold(cause -> cause, _ -> null);

        assertThat(failure).as("the writer is told why").isInstanceOf(DHTError.ReplicaFull.class);
        var full = (DHTError.ReplicaFull) failure;

        assertThat(full.usedBytes()).isEqualTo(100L);
        assertThat(full.maxBytes()).isEqualTo(128L);
    }

    /// The precedence over the fence refusals: a capacity refusal beside an owner-epoch fence is still `ReplicaFull`.
    @Test
    void aCapacityRefusal_winsOverAnOwnerEpochFenceRefusal() {
        var result = client.put(bytes("stream-segments/block"), new byte[10]);

        client.onPutResponse(capacityRefusal(requestTo(REMOTE_1)));
        var fenced = requestTo(REMOTE_2);

        client.onPutResponse(new DHTMessage.PutResponse(idOf(fenced), REMOTE_2, false, false, true, false, false));

        assertThat(result.await().<Cause>fold(cause -> cause, _ -> null)).isInstanceOf(DHTError.ReplicaFull.class);
    }

    /// Control: with no capacity refusal the answers stay what they were (the fence refusal is still indeterminate).
    @Test
    void withoutACapacityRefusal_theFenceRefusalStaysAsToday() {
        var result = client.put(bytes("stream-segments/block"), new byte[10]);

        client.onPutResponse(plainRefusal(requestTo(REMOTE_1)));
        var fenced = requestTo(REMOTE_2);

        client.onPutResponse(new DHTMessage.PutResponse(idOf(fenced), REMOTE_2, false, false, true, false, false));

        assertThat(result.await().<Cause>fold(cause -> cause, _ -> null)).isInstanceOf(DHTError.WriteIndeterminate.class);
    }

    /// A put that reaches W while one replica refused is a success (fewer than RF copies).
    @Test
    void aPutThatReachesW_whileOneReplicaRefuses_succeeds() {
        var result = client.put(bytes("stream-segments/block"), new byte[10]);

        client.onPutResponse(capacityRefusal(requestTo(REMOTE_1)));
        var accepted = requestTo(REMOTE_2);

        client.onPutResponse(new DHTMessage.PutResponse(idOf(accepted), REMOTE_2, true, false, false, false, false));

        assertThat(result.await().isSuccess()).as("the local copy and remote-2 make the quorum of 2").isTrue();
    }

    /// A client put is not a repair write: the replicas refuse it above their caps.
    @Test
    void aClientPut_isNotMarkedRepair() {
        client.put(bytes("stream-segments/block"), new byte[10]);

        assertThat(putRequests()).isNotEmpty()
                                 .allSatisfy(request -> assertThat(((DHTMessage.PutRequest) request.message()).repair()).isFalse());
    }
}
