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
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #2583 P1 pin for `DistributedDHTClient.dispatchTracked`: a replica whose `DHTNetwork.send` reports
/// [WriteOutcome.BackpressureRefused] never received the request, so the operation must fail at once instead of
/// waiting out the per-op timeout for a reply that cannot come. The refusal reaches the collector as a
/// [DHTError.PeerUnreachable] slot failure; what the caller sees is the quorum verdict, [DHTError.QuorumNotReached].
///
/// The quorum is the full replica set (W = R = 3), so one refusal makes quorum arithmetically unreachable; the other
/// remote reports `Sent` and is never answered. Only the refusal can resolve the promise before the (30 s) timeout, so a
/// client that ignores the outcome leaves it pending and the bounded `await` fails.
class DistributedDHTClientRefusedSendTest {
    private static final NodeId LOCAL_NODE = new NodeId("local");
    private static final NodeId REFUSING_NODE = new NodeId("remote-refusing");
    private static final NodeId SILENT_NODE = new NodeId("remote-silent");
    private static final TimeSpan LONG_TIMEOUT = TimeSpan.timeSpan(30).seconds();
    private static final TimeSpan BOUND = TimeSpan.timeSpan(3).seconds();
    private static final DHTConfig FULL_QUORUM = new DHTConfig(3, 3, 3, LONG_TIMEOUT);

    private DistributedDHTClient client;

    @BeforeEach
    void setUp() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(LOCAL_NODE);
        ring.addNode(REFUSING_NODE);
        ring.addNode(SILENT_NODE);
        var node = dhtNode(LOCAL_NODE, memoryStorageEngine(), ring, FULL_QUORUM);

        client = distributedDHTClient(node, new RefusingOneReplica(), FULL_QUORUM);
    }

    @Test
    void put_oneReplicaRefusesWithBackpressure_failsFastWithQuorumNotReachedBeforeTheOpTimeout() {
        client.put(bytes("k1"), bytes("v1"))
              .await(BOUND)
              .onSuccess(_ -> fail("Expected QuorumNotReached failure"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.QuorumNotReached.class));
    }

    @Test
    void get_oneReplicaRefusesWithBackpressure_failsFastWithQuorumNotReachedBeforeTheOpTimeout() {
        client.get(bytes("k1"))
              .await(BOUND)
              .onSuccess(_ -> fail("Expected QuorumNotReached failure"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.QuorumNotReached.class));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /// Every peer is live; the refusing replica's frames are refused locally, the other's are accepted and never answered.
    private static final class RefusingOneReplica implements DHTNetwork {
        @Override
        public Promise<WriteOutcome> send(NodeId target, ProtocolMessage message) {
            return Promise.success(outcomeFor(target));
        }

        private static WriteOutcome outcomeFor(NodeId target) {
            return target.equals(REFUSING_NODE)
                   ? new WriteOutcome.BackpressureRefused(target)
                   : new WriteOutcome.Sent(target);
        }

        @Override
        public Set<NodeId> livePeers() {
            return Set.of(LOCAL_NODE, REFUSING_NODE, SILENT_NODE);
        }
    }
}
