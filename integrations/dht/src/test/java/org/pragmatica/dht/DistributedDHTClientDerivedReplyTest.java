package org.pragmatica.dht;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1776: a client derived with `scoped()` / `withResolveFallbackObserver()` issues its own requests, but the node
/// routes every DHT reply to ONE instance (the base). Each test delivers the reply through the BASE client's
/// `on*Response`, exactly as the router does, to a read issued on a DERIVED instance.
class DistributedDHTClientDerivedReplyTest {
    private static final NodeId LOCAL = new NodeId("local");
    private static final DHTConfig BASE_CONFIG = new DHTConfig(3, 2, 2, timeSpan(10).seconds());
    private static final DHTConfig SCOPED_CONFIG = new DHTConfig(1, 1, 1, timeSpan(300).millis());

    private final CopyOnWriteArrayList<DHTMessage.GetRequest> gets = new CopyOnWriteArrayList<>();
    private DistributedDHTClient base;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(Option<byte[]> value) {
        return value.map(v -> new String(v, StandardCharsets.UTF_8)).or("");
    }

    @BeforeEach
    void setUp() {
        var ring = ConsistentHashRing.<NodeId> consistentHashRing();

        for (var i = 1; i <= 6; i++) {
            ring.addNode(new NodeId("replica-" + i));
        }
        DHTNetwork network = new DHTNetwork() {
            @Override
            public void send(NodeId nodeId, ProtocolMessage message) {
                if (message instanceof DHTMessage.GetRequest request) {
                    gets.add(request);
                }
            }

            @Override
            public Promise<WriteOutcome> sendOutcome(NodeId target, ProtocolMessage message) {
                send(target, message);
                return Promise.success(new WriteOutcome.Sent(target));
            }
        };
        base = distributedDHTClient(dhtNode(LOCAL, memoryStorageEngine(), ring, BASE_CONFIG), network, BASE_CONFIG);
    }

    private void replyViaBase(DHTMessage.GetRequest request, String value) {
        base.onGetResponse(new DHTMessage.GetResponse(request.requestId(), new NodeId("replica-1"), Option.some(bytes(value))));
    }

    @Test
    void scopedRead_resolves_whenReplyArrivesViaBaseClient() {
        var scoped = base.scoped(SCOPED_CONFIG);
        var read = scoped.get(bytes("k"));

        assertThat(gets).hasSize(1);
        replyViaBase(gets.getFirst(), "v");

        read.await(timeSpan(2).seconds())
            .onFailure(cause -> org.junit.jupiter.api.Assertions.fail("scoped read failed: " + cause.message()))
            .onSuccess(value -> assertThat(text(value)).isEqualTo("v"));
    }

    @Test
    void observerDerivedRead_resolves_whenReplyArrivesViaBaseClient() {
        var derived = base.withResolveFallbackObserver(ResolveFallbackObserver.noop());
        var read = derived.get(bytes("k"));

        assertThat(gets).hasSize(BASE_CONFIG.effectiveReplicationFactor(6));
        gets.forEach(request -> replyViaBase(request, "v"));

        read.await(timeSpan(2).seconds())
            .onFailure(cause -> org.junit.jupiter.api.Assertions.fail("derived read failed: " + cause.message()))
            .onSuccess(value -> assertThat(text(value)).isEqualTo("v"));
    }

    @Test
    void baseRead_resolves_whenReplyArrivesViaBaseClient() {
        var read = base.get(bytes("k"));

        assertThat(gets).hasSize(3);
        gets.forEach(request -> replyViaBase(request, "v"));

        read.await(timeSpan(2).seconds())
            .onFailure(cause -> org.junit.jupiter.api.Assertions.fail("base read failed: " + cause.message()))
            .onSuccess(value -> assertThat(text(value)).isEqualTo("v"));
    }

    @Test
    void scopedClient_keepsItsOwnConfig_notTheBases() {
        var scoped = base.scoped(SCOPED_CONFIG);

        assertThat(scoped.config()).isSameAs(SCOPED_CONFIG);
        assertThat(base.config()).isSameAs(BASE_CONFIG);

        var start = System.nanoTime();
        var read = scoped.get(bytes("k"));

        assertThat(gets).as("scoped RF1 addresses one replica, not the base's three").hasSize(1);

        var result = read.await(timeSpan(5).seconds());
        var elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("unanswered read must not succeed"));
        assertThat(elapsedMillis).as("times out at the scoped 300ms, not the base 10s").isLessThan(3_000);
    }
}
