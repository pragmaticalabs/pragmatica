package org.pragmatica.dht;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.DHTMessage.PutRequest;
import org.pragmatica.dht.DHTMessage.PutResponse;
import org.pragmatica.dht.storage.DhtPrefixAccounting;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.lang.Cause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2258 spec 3.2 item 7, the edges of the writer's answer and of the replica's refusal: a capacity refusal beside a timeout,
/// a stale-epoch refusal
/// that is still a fence, and the defaults of the two messages.
class DHTCapacityEdgesTest {
    private static final NodeId LOCAL = new NodeId("local");

    private record Sent(NodeId target, ProtocolMessage message) {}

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private DistributedDHTClient client(int nodes, int quorum, long timeoutMillis) {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(LOCAL);
        for (var i = 1; i < nodes; i++) {
            ring.addNode(new NodeId("remote-" + i));
        }
        var config = new DHTConfig(nodes, quorum, quorum, timeSpan(timeoutMillis).millis());
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, config);

        return distributedDHTClient(node, (target, message) -> sent.add(new Sent(target, message)), config);
    }

    private Sent requestTo(String replica) {
        return sent.stream()
                   .filter(s -> s.message() instanceof PutRequest && s.target().equals(new NodeId(replica)))
                   .findFirst()
                   .orElseThrow();
    }

    private static String idOf(Sent request) {
        return ((PutRequest) request.message()).requestId();
    }

    private static Cause failureOf(org.pragmatica.lang.Promise<?> promise) {
        return promise.await().<Cause>fold(cause -> cause, _ -> null);
    }

    @Test
    void aCapacityRefusal_beside_aReplicaThatNeverAnswers_failsReplicaFull_notWithTheTimeout() {
        var client = client(3, 2, 300L);
        var result = client.put(bytes("stream-segments/block"), new byte[10]);

        client.onPutResponse(PutResponse.capacityRefusal(idOf(requestTo("remote-1")), new NodeId("remote-1"), 100L, 128L));

        assertThat(failureOf(result)).isInstanceOf(DHTError.ReplicaFull.class);
    }

    @Test
    void aReplicaBehindTheOwnerEpochFence_refusesAsAFence_notAsCapacity() {
        var gate = new OwnerEpochGate() {
            @Override
            public boolean isStale(byte[] key, long epochIncarnation, long epochTerm, long epochCounter) {
                return true;
            }

            @Override
            public void advance(byte[] key, long epochIncarnation, long epochTerm, long epochCounter) {}
        };
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(LOCAL);
        var node = dhtNode(LOCAL, memoryStorageEngine(gate, DhtPrefixAccounting.dhtPrefixAccounting(100L)), ring, DHTConfig.SINGLE_NODE);
        var response = new AtomicReference<PutResponse>();

        node.handlePutRequest(new PutRequest("req", LOCAL, bytes("stream-segments/a"), new byte[10], 5L, 1L, 1L, 1L, DHTNode.NO_CHANGE),
                              response::set);

        assertThat(response.get().success()).isFalse();
        assertThat(response.get().fenced()).isTrue();
        assertThat(response.get().capacityExceeded()).isFalse();
    }

    @Test
    void aPutRequestWithoutTheRepairComponent_isAClientPut() {
        var request = new PutRequest("req", LOCAL, bytes("k"), bytes("v"), 1L, 0L, 0L, 0L, DHTNode.NO_CHANGE);

        assertThat(request.repair()).isFalse();
    }
}
