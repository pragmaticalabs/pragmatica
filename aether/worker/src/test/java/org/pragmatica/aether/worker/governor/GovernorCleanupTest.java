package org.pragmatica.aether.worker.governor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.AetherMaps;
import org.pragmatica.aether.dht.MapSubscription;
import org.pragmatica.aether.dht.ReplicatedMap;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpNodeRouteValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Unit.unit;

class GovernorCleanupTest {
    private static final NodeId DEAD_NODE = NodeId.nodeId("dead-worker-1").unwrap();
    private static final NodeId ALIVE_NODE = NodeId.nodeId("alive-worker-1").unwrap();

    private StubEndpointMap endpointMap;
    private StubSliceNodeMap sliceNodeMap;
    private StubHttpRouteMap httpRouteMap;
    private GovernorCleanup cleanup;

    @BeforeEach
    void setUp() {
        endpointMap = new StubEndpointMap();
        sliceNodeMap = new StubSliceNodeMap();
        httpRouteMap = new StubHttpRouteMap();
        var aetherMaps = new StubAetherMaps(endpointMap, sliceNodeMap, httpRouteMap);
        cleanup = GovernorCleanup.governorCleanup(aetherMaps);
    }

    @Nested
    class CleanupDeadNode {
        @Test
        void cleanupDeadNode_removesAllTrackedEndpoints() {
            var key1 = endpointKey("endpoints/com.example:svc:1.0.0/doWork:0");
            var key2 = endpointKey("endpoints/com.example:svc:1.0.0/doWork:1");
            cleanup.trackEndpoint(DEAD_NODE, key1);
            cleanup.trackEndpoint(DEAD_NODE, key2);

            var result = cleanup.cleanupDeadNode(DEAD_NODE).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(endpointMap.removedKeys).containsExactlyInAnyOrder(key1, key2);
        }

        @Test
        void cleanupDeadNode_removesAllTrackedSliceNodes() {
            var key1 = sliceNodeKey("slices/dead-worker-1/com.example:svc:1.0.0");
            var key2 = sliceNodeKey("slices/dead-worker-1/com.example:svc:2.0.0");
            cleanup.trackSliceNode(DEAD_NODE, key1);
            cleanup.trackSliceNode(DEAD_NODE, key2);

            var result = cleanup.cleanupDeadNode(DEAD_NODE).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(sliceNodeMap.removedKeys).containsExactlyInAnyOrder(key1, key2);
        }

        @Test
        void cleanupDeadNode_removesAllTrackedHttpRoutes() {
            var key = HttpNodeRouteKey.httpNodeRouteKey("GET", "/api/v1/", DEAD_NODE);
            cleanup.trackHttpRoute(DEAD_NODE, key);

            var result = cleanup.cleanupDeadNode(DEAD_NODE).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(httpRouteMap.removedKeys).containsExactly(key);
        }

        @Test
        void cleanupDeadNode_noEntries_isNoOp() {
            var result = cleanup.cleanupDeadNode(DEAD_NODE).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(endpointMap.removedKeys).isEmpty();
            assertThat(sliceNodeMap.removedKeys).isEmpty();
            assertThat(httpRouteMap.removedKeys).isEmpty();
        }

        @Test
        void cleanupDeadNode_doesNotAffectOtherNodes() {
            var deadKey = endpointKey("endpoints/com.example:svc:1.0.0/doWork:0");
            var aliveKey = endpointKey("endpoints/com.example:svc:1.0.0/doWork:1");
            cleanup.trackEndpoint(DEAD_NODE, deadKey);
            cleanup.trackEndpoint(ALIVE_NODE, aliveKey);

            var result = cleanup.cleanupDeadNode(DEAD_NODE).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(endpointMap.removedKeys).containsExactly(deadKey);
        }

        @Test
        void cleanupDeadNode_removesAllThreeTypes() {
            var epKey = endpointKey("endpoints/com.example:svc:1.0.0/handle:0");
            var snKey = sliceNodeKey("slices/dead-worker-1/com.example:svc:1.0.0");
            var hrKey = HttpNodeRouteKey.httpNodeRouteKey("POST", "/api/v1/", DEAD_NODE);
            cleanup.trackEndpoint(DEAD_NODE, epKey);
            cleanup.trackSliceNode(DEAD_NODE, snKey);
            cleanup.trackHttpRoute(DEAD_NODE, hrKey);

            var result = cleanup.cleanupDeadNode(DEAD_NODE).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(endpointMap.removedKeys).containsExactly(epKey);
            assertThat(sliceNodeMap.removedKeys).containsExactly(snKey);
            assertThat(httpRouteMap.removedKeys).containsExactly(hrKey);
        }
    }

    @Nested
    class CleanupDeadNodes {
        @Test
        void cleanupDeadNodes_removesOnlyDeadNodeEntries() {
            var deadKey = endpointKey("endpoints/com.example:svc:1.0.0/doWork:0");
            var aliveKey = endpointKey("endpoints/com.example:svc:1.0.0/doWork:1");
            cleanup.trackEndpoint(DEAD_NODE, deadKey);
            cleanup.trackEndpoint(ALIVE_NODE, aliveKey);

            var result = cleanup.cleanupDeadNodes(Set.of(ALIVE_NODE)).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(endpointMap.removedKeys).containsExactly(deadKey);
        }

        @Test
        void cleanupDeadNodes_noDeadNodes_isNoOp() {
            cleanup.trackEndpoint(ALIVE_NODE, endpointKey("endpoints/com.example:svc:1.0.0/doWork:0"));

            var result = cleanup.cleanupDeadNodes(Set.of(ALIVE_NODE)).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(endpointMap.removedKeys).isEmpty();
        }

        @Test
        void cleanupDeadNodes_emptyIndex_isNoOp() {
            var result = cleanup.cleanupDeadNodes(Set.of(ALIVE_NODE)).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(endpointMap.removedKeys).isEmpty();
        }
    }

    @Nested
    class TrackingAndUntracking {
        @Test
        void untrackEndpoint_removesFromIndex_beforeCleanup() {
            var key = endpointKey("endpoints/com.example:svc:1.0.0/doWork:0");
            cleanup.trackEndpoint(DEAD_NODE, key);
            cleanup.untrackEndpoint(DEAD_NODE, key);

            var result = cleanup.cleanupDeadNode(DEAD_NODE).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(endpointMap.removedKeys).isEmpty();
        }

        @Test
        void untrackSliceNode_removesFromIndex_beforeCleanup() {
            var key = sliceNodeKey("slices/dead-worker-1/com.example:svc:1.0.0");
            cleanup.trackSliceNode(DEAD_NODE, key);
            cleanup.untrackSliceNode(DEAD_NODE, key);

            var result = cleanup.cleanupDeadNode(DEAD_NODE).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(sliceNodeMap.removedKeys).isEmpty();
        }

        @Test
        void untrackHttpRoute_removesFromIndex_beforeCleanup() {
            var key = HttpNodeRouteKey.httpNodeRouteKey("GET", "/api/", DEAD_NODE);
            cleanup.trackHttpRoute(DEAD_NODE, key);
            cleanup.untrackHttpRoute(DEAD_NODE, key);

            var result = cleanup.cleanupDeadNode(DEAD_NODE).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(httpRouteMap.removedKeys).isEmpty();
        }
    }

    @Nested
    class RebuildIndex {
        private DHTNode dhtNode;
        private MemoryStorageEngine storage;

        @BeforeEach
        void setUpDht() {
            storage = MemoryStorageEngine.memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(ALIVE_NODE);
            dhtNode = DHTNode.dhtNode(ALIVE_NODE, storage, ring, DHTConfig.DEFAULT);
        }

        @Test
        void rebuildIndex_populatesEndpointIndex() {
            putStorageEntry("endpoints/endpoints/com.example:svc:1.0.0/doWork:0",
                            DEAD_NODE.id());

            var result = cleanup.rebuildIndex(dhtNode).await();

            result.onFailure(_ -> fail("Expected success"));
            // Verify the index has the entry by cleaning up the dead node
            var cleanupResult = cleanup.cleanupDeadNode(DEAD_NODE).await();
            cleanupResult.onFailure(_ -> fail("Expected success"));
            assertThat(endpointMap.removedKeys).hasSize(1);
        }

        @Test
        void rebuildIndex_populatesSliceNodeIndex() {
            putStorageEntry("slice-nodes/slices/" + DEAD_NODE.id() + "/com.example:svc:1.0.0",
                            "RUNNING||false");

            var result = cleanup.rebuildIndex(dhtNode).await();

            result.onFailure(_ -> fail("Expected success"));
            var cleanupResult = cleanup.cleanupDeadNode(DEAD_NODE).await();
            cleanupResult.onFailure(_ -> fail("Expected success"));
            assertThat(sliceNodeMap.removedKeys).hasSize(1);
        }

        @Test
        void rebuildIndex_populatesHttpRouteIndex() {
            putStorageEntry("http-routes/http-node-routes/GET:/api/v1/:" + DEAD_NODE.id(),
                            "com.example:svc:1.0.0|doWork|ACTIVE|100|0");

            var result = cleanup.rebuildIndex(dhtNode).await();

            result.onFailure(_ -> fail("Expected success"));
            var cleanupResult = cleanup.cleanupDeadNode(DEAD_NODE).await();
            cleanupResult.onFailure(_ -> fail("Expected success"));
            assertThat(httpRouteMap.removedKeys).hasSize(1);
        }

        @Test
        void rebuildIndex_clearsExistingIndex() {
            // Track an entry manually
            var key = endpointKey("endpoints/com.example:svc:1.0.0/doWork:0");
            cleanup.trackEndpoint(DEAD_NODE, key);

            // Rebuild with empty storage
            var result = cleanup.rebuildIndex(dhtNode).await();

            result.onFailure(_ -> fail("Expected success"));
            // The manually tracked entry should be gone
            var cleanupResult = cleanup.cleanupDeadNode(DEAD_NODE).await();
            cleanupResult.onFailure(_ -> fail("Expected success"));
            assertThat(endpointMap.removedKeys).isEmpty();
        }

        private void putStorageEntry(String key, String value) {
            storage.put(key.getBytes(StandardCharsets.UTF_8),
                        value.getBytes(StandardCharsets.UTF_8))
                   .await();
        }
    }

    // -- Helpers --

    private static EndpointKey endpointKey(String raw) {
        return EndpointKey.endpointKey(raw).unwrap();
    }

    private static SliceNodeKey sliceNodeKey(String raw) {
        return SliceNodeKey.sliceNodeKey(raw).unwrap();
    }

    // -- Stubs --

    record StubAetherMaps(StubEndpointMap endpointMap,
                          StubSliceNodeMap sliceNodeMap,
                          StubHttpRouteMap httpRouteMap) implements AetherMaps {
        @Override
        public ReplicatedMap<EndpointKey, EndpointValue> endpoints() {
            return endpointMap;
        }

        @Override
        public ReplicatedMap<SliceNodeKey, SliceNodeValue> sliceNodes() {
            return sliceNodeMap;
        }

        @Override
        public ReplicatedMap<HttpNodeRouteKey, HttpNodeRouteValue> httpRoutes() {
            return httpRouteMap;
        }
    }

    static class StubEndpointMap implements ReplicatedMap<EndpointKey, EndpointValue> {
        final CopyOnWriteArrayList<EndpointKey> removedKeys = new CopyOnWriteArrayList<>();

        @Override
        public Promise<Unit> put(EndpointKey key, EndpointValue value) {
            return Promise.success(unit());
        }

        @Override
        public Promise<Option<EndpointValue>> get(EndpointKey key) {
            return Promise.success(Option.none());
        }

        @Override
        public Promise<Boolean> remove(EndpointKey key) {
            removedKeys.add(key);
            return Promise.success(true);
        }

        @Override
        public ReplicatedMap<EndpointKey, EndpointValue> subscribe(MapSubscription<EndpointKey, EndpointValue> subscription) {
            return this;
        }

        @Override
        public String name() {
            return "test-endpoints";
        }
    }

    static class StubSliceNodeMap implements ReplicatedMap<SliceNodeKey, SliceNodeValue> {
        final CopyOnWriteArrayList<SliceNodeKey> removedKeys = new CopyOnWriteArrayList<>();

        @Override
        public Promise<Unit> put(SliceNodeKey key, SliceNodeValue value) {
            return Promise.success(unit());
        }

        @Override
        public Promise<Option<SliceNodeValue>> get(SliceNodeKey key) {
            return Promise.success(Option.none());
        }

        @Override
        public Promise<Boolean> remove(SliceNodeKey key) {
            removedKeys.add(key);
            return Promise.success(true);
        }

        @Override
        public ReplicatedMap<SliceNodeKey, SliceNodeValue> subscribe(MapSubscription<SliceNodeKey, SliceNodeValue> subscription) {
            return this;
        }

        @Override
        public String name() {
            return "test-slice-nodes";
        }
    }

    static class StubHttpRouteMap implements ReplicatedMap<HttpNodeRouteKey, HttpNodeRouteValue> {
        final CopyOnWriteArrayList<HttpNodeRouteKey> removedKeys = new CopyOnWriteArrayList<>();

        @Override
        public Promise<Unit> put(HttpNodeRouteKey key, HttpNodeRouteValue value) {
            return Promise.success(unit());
        }

        @Override
        public Promise<Option<HttpNodeRouteValue>> get(HttpNodeRouteKey key) {
            return Promise.success(Option.none());
        }

        @Override
        public Promise<Boolean> remove(HttpNodeRouteKey key) {
            removedKeys.add(key);
            return Promise.success(true);
        }

        @Override
        public ReplicatedMap<HttpNodeRouteKey, HttpNodeRouteValue> subscribe(MapSubscription<HttpNodeRouteKey, HttpNodeRouteValue> subscription) {
            return this;
        }

        @Override
        public String name() {
            return "test-http-routes";
        }
    }
}
