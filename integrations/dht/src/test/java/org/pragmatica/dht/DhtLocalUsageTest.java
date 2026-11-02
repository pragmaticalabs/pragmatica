package org.pragmatica.dht;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTClient.LocalUsage;
import org.pragmatica.dht.storage.DhtPrefixAccounting;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.LocalDHTClient.localDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #2258: what a node reports of its own usage of a prefix (the number a `DhtStorageTier` turns into utilisation), and what
/// the interface defaults answer for a client or an engine that keeps no accounts.
class DhtLocalUsageTest {
    private static final NodeId LOCAL = new NodeId("local");

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private DHTNode node() {
        var storage = memoryStorageEngine(OwnerEpochGate.noOp(), DhtPrefixAccounting.dhtPrefixAccounting(100L));
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(LOCAL);
        storage.putVersioned(bytes("stream-segments/a"), new byte[40], 1L).await();

        return dhtNode(LOCAL, storage, ring, DHTConfig.SINGLE_NODE);
    }

    @Test
    void theLocalAndTheDistributedClient_reportTheNodesLiveBytesAndCap_perPrefix() {
        var node = node();
        var clients = List.<DHTClient>of(localDHTClient(node), distributedDHTClient(node, (target, message) -> {}, DHTConfig.SINGLE_NODE));

        for (var client : clients) {
            assertThat(client.localUsage("stream-segments")).isEqualTo(new LocalUsage(40L, 100L));
            assertThat(client.localUsage("artifacts")).isEqualTo(new LocalUsage(0L, Long.MAX_VALUE));
        }
    }

    @Test
    void aClientWithNoLocalEngine_reportsNothingHeldAndNoCap() {
        var client = (DHTClient) Proxy.newProxyInstance(DHTClient.class.getClassLoader(),
                                                        new Class<?>[]{DHTClient.class},
                                                        (proxy, method, args) -> InvocationHandler.invokeDefault(proxy, method, args));

        assertThat(client.localUsage("stream-segments")).isEqualTo(new LocalUsage(0L, Long.MAX_VALUE));
        assertThat(LocalUsage.UNKNOWN).isEqualTo(new LocalUsage(0L, Long.MAX_VALUE));
    }

    @Test
    void anEngineThatKeepsNoAccounts_answersZeroUnboundedAndNoPrefixes_andPassesWritesThroughWithoutTheRepairFlag() {
        var calls = new ArrayList<List<Object>>();
        var engine = (StorageEngine) Proxy.newProxyInstance(StorageEngine.class.getClassLoader(),
                                                            new Class<?>[]{StorageEngine.class},
                                                            (proxy, method, args) -> {
                                                                var sixArgWrite = method.getParameterCount() == 6
                                                                                  && method.getName().startsWith("putVersioned");

                                                                if (sixArgWrite) {
                                                                    calls.add(List.of(method.getName(), List.of(args)));
                                                                    return Promise.success(true);
                                                                }
                                                                if (method.isDefault()) {
                                                                    return InvocationHandler.invokeDefault(proxy, method, args);
                                                                }
                                                                return Promise.success(true);
                                                            });

        assertThat(engine.usedBytes("stream-segments")).isZero();
        assertThat(engine.tombstoneBytes("stream-segments")).isZero();
        assertThat(engine.maxBytes("stream-segments")).isEqualTo(Long.MAX_VALUE);
        assertThat(engine.accountedPrefixes()).isEmpty();
        assertThat(engine.refusedPuts()).isZero();
        engine.capacityListener(null);

        engine.putVersioned(bytes("k"), bytes("v"), 5L, 1L, 2L, 3L, true);
        engine.putVersionedDisplacing(bytes("k"), bytes("v"), 5L, 1L, 2L, 3L, true);

        assertThat(calls).hasSize(2);
        calls.forEach(call -> {
            @SuppressWarnings("unchecked") var args = (List<Object>) call.get(1);

            assertThat(args).as("%s: the 6-argument form, nothing added to the epoch", call.get(0)).hasSize(6);
            assertThat(args.subList(2, 6)).containsExactly(5L, 1L, 2L, 3L);
        });
    }
}
