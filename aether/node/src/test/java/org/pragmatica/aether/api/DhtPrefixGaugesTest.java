package org.pragmatica.aether.api;

import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.stubbing.Answer;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.TlsProbe;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.dht.storage.DhtPrefixAccounting;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #2258: the DHT engine's byte accounting on the Prometheus surface, read from the scrape of a started management server over a
/// node whose engine holds known bytes, a tombstone and one refusal. Reads the registered meters, not the code that registers them.
class DhtPrefixGaugesTest {
    private static final TimeSpan BOUND = TimeSpan.timeSpan(60).seconds();

    private static final Answer<Object> NONE_OR_MOCK = new Answer<>() {
        @Override
        public Object answer(org.mockito.invocation.InvocationOnMock invocation) throws Throwable {
            var type = invocation.getMethod().getReturnType();

            if (type == Option.class) {
                return Option.none();
            }
            var fallback = RETURNS_DEFAULTS.answer(invocation);

            if (fallback != null || type.isPrimitive() || type.isSealed() || Modifier.isFinal(type.getModifiers())) {
                return fallback;
            }

            return mock(type, this);
        }
    };

    private static DHTNode dht() {
        var accounting = DhtPrefixAccounting.dhtPrefixAccounting(1_000L);

        accounting.register("orders-blocks");
        var storage = memoryStorageEngine(OwnerEpochGate.noOp(), accounting);
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        var self = new NodeId("n1");

        ring.addNode(self);
        storage.putVersioned(bytes("stream-segments/a"), new byte[400], 1L).await();
        storage.putVersioned(bytes("stream-segments/b"), new byte[700], 2L).await();
        storage.removeVersioned(bytes("stream-segments/a"), 3L, 0L, 0L, 0L).await();
        storage.putVersioned(bytes("artifacts/x"), new byte[7], 4L).await();
        storage.putVersioned(bytes("orders-blocks/y"), new byte[11], 5L).await();
        storage.put(bytes("cache:z"), new byte[13]).await();
        storage.putVersioned(bytes("stream-segments/c"), new byte[900], 6L).await();

        return dhtNode(self, storage, ring, DHTConfig.SINGLE_NODE);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static ManagementServer serverOver(DHTNode dht) {
        var node = mock(ManageableNode.class, NONE_OR_MOCK);

        org.mockito.Mockito.when(node.streamPartitionManager())
                           .thenReturn(mock(org.pragmatica.aether.stream.StreamPartitionManager.class, NONE_OR_MOCK));
        org.mockito.Mockito.when(node.dhtNode()).thenReturn(Option.some(dht));

        return ManagementServer.managementServer(0,
                                                 () -> node,
                                                 mock(EntityCheckpointDriver.class, NONE_OR_MOCK),
                                                 mock(AlertManager.class, NONE_OR_MOCK),
                                                 mock(ObservabilityConfigRegistry.class, NONE_OR_MOCK),
                                                 mock(InvocationTraceStore.class, NONE_OR_MOCK),
                                                 mock(LogLevelRegistry.class, NONE_OR_MOCK),
                                                 Option.none(),
                                                 mock(ScheduledTaskRegistry.class, NONE_OR_MOCK),
                                                 mock(ScheduledTaskManager.class, NONE_OR_MOCK),
                                                 mock(SliceInvoker.class, NONE_OR_MOCK),
                                                 mock(ScheduledTaskStateRegistry.class, NONE_OR_MOCK),
                                                 Option.none(),
                                                 mock(SecurityValidator.class, NONE_OR_MOCK),
                                                 false,
                                                 Map::of,
                                                 Option.none(),
                                                 Option.none(),
                                                 HttpProtocol.H1,
                                                 ForwardingTimeouts.forwardingTimeouts(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 _ -> {},
                                                 Set::of,
                                                 org.pragmatica.aether.api.routes.NodeLifecycleRoutes.SliceFloor.sliceFloor((_, _) -> java.util.List.of(),
                                                                                                                         org.pragmatica.utility.warning.OperatorWarningSink.logOnly()));
    }

    private static String sample(String scrape, String metric, String label) {
        return scrape.lines()
                     .filter(line -> line.startsWith(metric + "{") || line.startsWith(metric + " "))
                     .filter(line -> label == null || line.contains(label))
                     .findFirst()
                     .map(line -> line.substring(line.lastIndexOf(' ') + 1))
                     .orElse("ABSENT");
    }

    @Test
    @Timeout(120)
    void theScrape_carriesLiveBytes_tombstoneBytes_theCap_andTheRefusals_perPrefix() throws Exception {
        var server = serverOver(dht());

        try {
            TlsProbe.requireStarted(server.start().await(BOUND), "management listener");
            var http = HttpClient.newHttpClient();
            var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.boundPort().or(0) + "/api/v1/metrics/prometheus")).build(),
                                     HttpResponse.BodyHandlers.ofString());
            var scrape = response.body();

            assertThat(response.statusCode()).as(scrape).isEqualTo(200);
            assertThat(sample(scrape, "aether_dht_prefix_bytes", "prefix=\"stream-segments\"")).isEqualTo("900.0");
            assertThat(sample(scrape, "aether_dht_prefix_bytes", "prefix=\"artifacts\"")).isEqualTo("7.0");
            assertThat(sample(scrape, "aether_dht_prefix_bytes", "prefix=\"orders-blocks\"")).isEqualTo("11.0");
            assertThat(sample(scrape, "aether_dht_prefix_bytes", "prefix=\"other\"")).isEqualTo("13.0");
            assertThat(sample(scrape, "aether_dht_prefix_tombstone_bytes", "prefix=\"stream-segments\"")).isEqualTo("81.0");
            assertThat(sample(scrape, "aether_dht_prefix_tombstone_bytes", "prefix=\"artifacts\"")).isEqualTo("0.0");
            assertThat(sample(scrape, "aether_dht_prefix_max_bytes", "prefix=\"stream-segments\"")).isEqualTo("1000.0");
            assertThat(sample(scrape, "aether_dht_prefix_refused_puts", null)).isEqualTo("1.0");
        } finally {
            server.stop().await(BOUND);
        }
    }
}
