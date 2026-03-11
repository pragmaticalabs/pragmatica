package org.pragmatica.aether.dht;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpNodeRouteValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class AetherMapsTest {

    private RoundTripDHTClient dhtClient;
    private AetherMaps maps;

    @BeforeEach
    void setUp() {
        dhtClient = new RoundTripDHTClient();
        maps = AetherMaps.aetherMaps(dhtClient);
    }

    @Nested
    class EndpointSerializationTests {
        @Test
        void endpoint_putAndGet_roundTrips() {
            var artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
            var methodName = MethodName.methodName("doWork").unwrap();
            var key = new EndpointKey(artifact, methodName, 42);
            var nodeId = NodeId.nodeId("node-1").unwrap();
            var value = new EndpointValue(nodeId);

            maps.endpoints().put(key, value).await()
                .onFailure(_ -> fail("Put should succeed"));

            maps.endpoints().get(key).await()
                .onFailure(_ -> fail("Get should succeed"))
                .onSuccess(opt -> opt.onPresent(v -> assertThat(v.nodeId().id()).isEqualTo("node-1"))
                                     .onEmpty(() -> fail("Value should be present")));
        }

        @Test
        void endpoint_subscriptionNotified_onPut() {
            var artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
            var methodName = MethodName.methodName("doWork").unwrap();
            var key = new EndpointKey(artifact, methodName, 42);
            var nodeId = NodeId.nodeId("node-1").unwrap();
            var value = new EndpointValue(nodeId);

            var receivedKey = new AtomicReference<EndpointKey>();
            var receivedValue = new AtomicReference<EndpointValue>();

            maps.endpoints().subscribe(new MapSubscription<>() {
                @Override
                public void onPut(EndpointKey k, EndpointValue v) {
                    receivedKey.set(k);
                    receivedValue.set(v);
                }

                @Override
                public void onRemove(EndpointKey k) {}
            });

            maps.endpoints().put(key, value).await();

            assertThat(receivedKey.get()).isEqualTo(key);
            assertThat(receivedValue.get().nodeId().id()).isEqualTo("node-1");
        }
    }

    @Nested
    class SliceNodeSerializationTests {
        @Test
        void sliceNode_putAndGet_roundTrips() {
            var artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
            var nodeId = NodeId.nodeId("node-2").unwrap();
            var key = new SliceNodeKey(artifact, nodeId);
            var value = new SliceNodeValue(SliceState.ACTIVE, Option.none(), false);

            maps.sliceNodes().put(key, value).await()
                .onFailure(_ -> fail("Put should succeed"));

            maps.sliceNodes().get(key).await()
                .onFailure(_ -> fail("Get should succeed"))
                .onSuccess(opt -> opt.onPresent(AetherMapsTest::assertActiveSliceNode)
                                     .onEmpty(() -> fail("Value should be present")));
        }

        @Test
        void sliceNode_withFailureReason_roundTrips() {
            var artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
            var nodeId = NodeId.nodeId("node-2").unwrap();
            var key = new SliceNodeKey(artifact, nodeId);
            var value = new SliceNodeValue(SliceState.FAILED, Option.some("ClassNotFound"), true);

            maps.sliceNodes().put(key, value).await()
                .onFailure(_ -> fail("Put should succeed"));

            maps.sliceNodes().get(key).await()
                .onFailure(_ -> fail("Get should succeed"))
                .onSuccess(opt -> opt.onPresent(AetherMapsTest::assertFailedSliceNode)
                                     .onEmpty(() -> fail("Value should be present")));
        }
    }

    @Nested
    class HttpRouteSerializationTests {
        @Test
        void httpRoute_putAndGet_roundTrips() {
            var nodeId = NodeId.nodeId("node-3").unwrap();
            var key = HttpNodeRouteKey.httpNodeRouteKey("GET", "/api/test", nodeId);
            var value = new HttpNodeRouteValue("org.example:my-slice:1.0.0", "handleGet", "ACTIVE", 100, 1700000000000L);

            maps.httpRoutes().put(key, value).await()
                .onFailure(_ -> fail("Put should succeed"));

            maps.httpRoutes().get(key).await()
                .onFailure(_ -> fail("Get should succeed"))
                .onSuccess(opt -> opt.onPresent(AetherMapsTest::assertHttpRouteValue)
                                     .onEmpty(() -> fail("Value should be present")));
        }
    }

    @Nested
    class MapNaming {
        @Test
        void endpoints_hasCorrectName() {
            assertThat(maps.endpoints().name()).isEqualTo("endpoints");
        }

        @Test
        void sliceNodes_hasCorrectName() {
            assertThat(maps.sliceNodes().name()).isEqualTo("slice-nodes");
        }

        @Test
        void httpRoutes_hasCorrectName() {
            assertThat(maps.httpRoutes().name()).isEqualTo("http-routes");
        }
    }

    // --- Extracted assertion methods (avoid multi-statement lambdas) ---

    @SuppressWarnings("JBCT-RET-01") // Test assertion helper
    private static void assertActiveSliceNode(SliceNodeValue v) {
        assertThat(v.state()).isEqualTo(SliceState.ACTIVE);
        assertThat(v.failureReason().isEmpty()).isTrue();
        assertThat(v.fatal()).isFalse();
    }

    @SuppressWarnings("JBCT-RET-01") // Test assertion helper
    private static void assertFailedSliceNode(SliceNodeValue v) {
        assertThat(v.state()).isEqualTo(SliceState.FAILED);
        assertThat(v.failureReason().or("")).isEqualTo("ClassNotFound");
        assertThat(v.fatal()).isTrue();
    }

    @SuppressWarnings("JBCT-RET-01") // Test assertion helper
    private static void assertHttpRouteValue(HttpNodeRouteValue v) {
        assertThat(v.artifactCoord()).isEqualTo("org.example:my-slice:1.0.0");
        assertThat(v.sliceMethod()).isEqualTo("handleGet");
        assertThat(v.state()).isEqualTo("ACTIVE");
        assertThat(v.weight()).isEqualTo(100);
        assertThat(v.registeredAt()).isEqualTo(1700000000000L);
    }

    /// DHTClient that stores values and returns them on get — for round-trip testing.
    static class RoundTripDHTClient implements DHTClient {
        private final java.util.Map<String, byte[]> storage = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            return Promise.success(Option.option(storage.get(keyString(key))));
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            storage.put(keyString(key), value);
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            return Promise.success(storage.remove(keyString(key)) != null);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return Promise.success(storage.containsKey(keyString(key)));
        }

        @Override
        public Partition partitionFor(byte[] key) {
            return null;
        }

        private static String keyString(byte[] key) {
            return new String(key, StandardCharsets.UTF_8);
        }
    }
}
