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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Async-callback probe for the write path's bookkeeping: the stale-writer clock and the write-pending mark are
/// settled by the operation's own resolution, so a caller that continues past the operation must already find them
/// settled. The replies here arrive LATER, from another thread, as they do on a real network; replies delivered
/// inline would resolve the operation on the caller's thread and run a detached callback inline, hiding the race.
/// Each scenario repeats, because a detached callback loses the race most of the time, not every time.
class DistributedDHTClientLateReplyTest {
    private static final NodeId LOCAL = new NodeId("local");
    private static final NodeId REPLICA = new NodeId("replica-1");
    private static final DHTConfig SINGLE_REPLICA = new DHTConfig(1, 1, 1, timeSpan(5).seconds());
    private static final DHTConfig ALL_THREE = new DHTConfig(3, 3, 3, timeSpan(5).seconds());
    private static final long REPLY_DELAY_MS = 3;
    private static final int TRIALS = 20;

    private final CopyOnWriteArrayList<ProtocolMessage> sent = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clearSent() {
        sent.clear();
    }

    @Test
    void put_clearsStaleWriterClockBeforeContinuation_whenReplyArrivesLaterOnAnotherThread() {
        var node = remoteOnlyNode();
        var client = distributedDHTClient(node, network(), SINGLE_REPLICA);
        var stillStale = 0;

        for (var trial = 0; trial < TRIALS; trial++) {
            node.noteStaleRefusal(DHTNode.NO_CHANGE, 1_000L);
            var op = client.put(keyOf(trial), new byte[]{9});

            replyLater(() -> client.onPutResponse(new DHTMessage.PutResponse(lastRequest(DHTMessage.PutRequest.class).requestId(),
                                                                             REPLICA,
                                                                             true,
                                                                             false,
                                                                             false,
                                                                             false,
                                                                             false)));
            assertThat(op.await().isSuccess()).isTrue();
            stillStale += node.staleRefusal().isPresent() ? 1 : 0;
        }

        assertThat(stillStale).as("trials where the caller continued before the accepted write ended the stale-writer clock")
                              .isZero();
    }

    @Test
    void remove_clearsStaleWriterClockBeforeContinuation_whenReplyArrivesLaterOnAnotherThread() {
        var node = remoteOnlyNode();
        var client = distributedDHTClient(node, network(), SINGLE_REPLICA);
        var stillStale = 0;

        for (var trial = 0; trial < TRIALS; trial++) {
            node.noteStaleRefusal(DHTNode.NO_CHANGE, 1_000L);
            var op = client.remove(keyOf(trial));

            replyLater(() -> client.onRemoveResponse(new DHTMessage.RemoveResponse(lastRequest(DHTMessage.RemoveRequest.class).requestId(),
                                                                                   REPLICA,
                                                                                   true)));
            assertThat(op.await().isSuccess()).isTrue();
            stillStale += node.staleRefusal().isPresent() ? 1 : 0;
        }

        assertThat(stillStale).as("trials where the caller continued before the accepted remove ended the stale-writer clock")
                              .isZero();
    }

    @Test
    void put_recordsStaleWriterClockBeforeContinuation_whenRefusalArrivesLaterOnAnotherThread() {
        var node = remoteOnlyNode();
        var client = distributedDHTClient(node, network(), SINGLE_REPLICA);
        var notStale = 0;

        for (var trial = 0; trial < TRIALS; trial++) {
            node.clearStaleRefusal();
            var op = client.put(keyOf(trial), new byte[]{9});

            replyLater(() -> client.onPutResponse(new DHTMessage.PutResponse(lastRequest(DHTMessage.PutRequest.class).requestId(),
                                                                             REPLICA,
                                                                             false,
                                                                             false,
                                                                             false,
                                                                             true,
                                                                             false,
                                                                             false)));
            assertThat(op.await().isFailure()).as("a replica that refuses the write as stale fails it").isTrue();
            notStale += node.staleRefusal().isPresent() ? 0 : 1;
        }

        assertThat(notStale).as("trials where the caller saw the refused write before the stale-writer clock started")
                            .isZero();
    }

    @Test
    void remove_recordsStaleWriterClockBeforeContinuation_whenRefusalArrivesLaterOnAnotherThread() {
        var node = remoteOnlyNode();
        var client = distributedDHTClient(node, network(), SINGLE_REPLICA);
        var notStale = 0;

        for (var trial = 0; trial < TRIALS; trial++) {
            node.clearStaleRefusal();
            var op = client.remove(keyOf(trial));

            replyLater(() -> client.onRemoveResponse(new DHTMessage.RemoveResponse(lastRequest(DHTMessage.RemoveRequest.class).requestId(),
                                                                                   REPLICA,
                                                                                   false,
                                                                                   false,
                                                                                   true,
                                                                                   false,
                                                                                   false)));
            assertThat(op.await().isFailure()).as("a replica that refuses the remove as stale fails it").isTrue();
            notStale += node.staleRefusal().isPresent() ? 0 : 1;
        }

        assertThat(notStale).as("trials where the caller saw the refused remove before the stale-writer clock started")
                            .isZero();
    }

    @Test
    void put_endsWritePendingMarkBeforeContinuation_whenRepliesArriveLaterOnAnotherThread() {
        var node = localInRingNode();
        var client = distributedDHTClient(node, network(), ALL_THREE);
        var stillPending = 0;

        for (var trial = 0; trial < TRIALS; trial++) {
            var key = keyOf(trial);

            sent.clear();
            var op = client.put(key, new byte[]{9});
            var requests = List.copyOf(sent);

            replyLater(() -> replyToEveryPut(client, requests));
            var result = op.await();

            assertThat(result.isSuccess()).as("put outcome: %s", result).isTrue();
            stillPending += node.localWritePending(key) ? 1 : 0;
        }

        assertThat(stillPending).as("trials where the caller continued before the put ended its write-pending mark")
                                .isZero();
    }

    @Test
    void remove_endsWritePendingMarkBeforeContinuation_whenRepliesArriveLaterOnAnotherThread() {
        var node = localInRingNode();
        var client = distributedDHTClient(node, network(), ALL_THREE);
        var stillPending = 0;

        for (var trial = 0; trial < TRIALS; trial++) {
            var key = keyOf(trial);

            sent.clear();
            var op = client.remove(key);
            var requests = List.copyOf(sent);

            replyLater(() -> replyToEveryRemove(client, requests));
            var result = op.await();

            assertThat(result.isSuccess()).as("remove outcome: %s", result).isTrue();
            stillPending += node.localWritePending(key) ? 1 : 0;
        }

        assertThat(stillPending).as("trials where the caller continued before the remove ended its write-pending mark")
                                .isZero();
    }

    private static void replyToEveryPut(DistributedDHTClient client, List<ProtocolMessage> requests) {
        requests.stream()
            .filter(DHTMessage.PutRequest.class::isInstance)
            .map(DHTMessage.PutRequest.class::cast)
            .forEach(request -> client.onPutResponse(new DHTMessage.PutResponse(request.requestId(),
                                                                                REPLICA,
                                                                                true,
                                                                                false,
                                                                                false,
                                                                                false,
                                                                                false)));
    }

    private static void replyToEveryRemove(DistributedDHTClient client, List<ProtocolMessage> requests) {
        requests.stream()
            .filter(DHTMessage.RemoveRequest.class::isInstance)
            .map(DHTMessage.RemoveRequest.class::cast)
            .forEach(request -> client.onRemoveResponse(new DHTMessage.RemoveResponse(request.requestId(), REPLICA, true)));
    }

    private <R extends DHTMessage> R lastRequest(Class<R> type) {
        return sent.stream()
                   .filter(type::isInstance)
                   .map(type::cast)
                   .reduce((_, last) -> last)
                   .orElseThrow();
    }

    private static void replyLater(Runnable reply) {
        Thread.ofVirtual()
              .start(() -> deliverAfterDelay(reply));
    }

    private static void deliverAfterDelay(Runnable reply) {
        try {
            Thread.sleep(REPLY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        reply.run();
    }

    private static byte[] keyOf(int trial) {
        return new byte[]{1, 2, (byte) trial};
    }

    private static DHTNode remoteOnlyNode() {
        var ring = ConsistentHashRing.<NodeId> consistentHashRing();

        for (var i = 1; i <= 6; i++) {
            ring.addNode(new NodeId("replica-" + i));
        }

        return dhtNode(LOCAL, memoryStorageEngine(), ring, SINGLE_REPLICA);
    }

    private static DHTNode localInRingNode() {
        var ring = ConsistentHashRing.<NodeId> consistentHashRing();

        ring.addNode(LOCAL);
        ring.addNode(new NodeId("replica-1"));
        ring.addNode(new NodeId("replica-2"));

        return dhtNode(LOCAL, memoryStorageEngine(), ring, ALL_THREE);
    }

    private DHTNetwork network() {
        return new DHTNetwork() {
            @Override
            public void send(NodeId nodeId, ProtocolMessage message) {
                sent.add(message);
            }

            @Override
            public Promise<WriteOutcome> sendOutcome(NodeId target, ProtocolMessage message) {
                send(target, message);
                return Promise.success(new WriteOutcome.Sent(target));
            }
        };
    }
}
