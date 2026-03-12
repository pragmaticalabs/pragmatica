package org.pragmatica.aether.worker.governor;

import org.junit.jupiter.api.BeforeEach;
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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Unit.unit;

class GovernorReconciliationTest {
    private static final NodeId DEAD_NODE = NodeId.nodeId("dead-worker-1").unwrap();
    private static final NodeId ALIVE_NODE = NodeId.nodeId("alive-worker-1").unwrap();

    private StubEndpointMap endpointMap;
    private GovernorCleanup cleanup;

    @BeforeEach
    void setUp() {
        endpointMap = new StubEndpointMap();
        var sliceNodeMap = new StubSliceNodeMap();
        var httpRouteMap = new StubHttpRouteMap();
        var aetherMaps = new StubAetherMaps(endpointMap, sliceNodeMap, httpRouteMap);
        cleanup = GovernorCleanup.governorCleanup(aetherMaps);
    }

    @Test
    void reconcile_removesDeadNodeEntries() {
        var deadKey = EndpointKey.endpointKey("endpoints/com.example:svc:1.0.0/doWork:0").unwrap();
        cleanup.trackEndpoint(DEAD_NODE, deadKey);

        var result = GovernorReconciliation.reconcile(Set.of(ALIVE_NODE), cleanup).await();

        result.onFailure(_ -> fail("Expected success"));
        assertThat(endpointMap.removedKeys).containsExactly(deadKey);
    }

    @Test
    void reconcile_emptyIndex_succeeds() {
        var result = GovernorReconciliation.reconcile(Set.of(ALIVE_NODE), cleanup).await();

        result.onFailure(_ -> fail("Expected success"));
        assertThat(endpointMap.removedKeys).isEmpty();
    }

    @Test
    void reconcile_aliveNodesNotRemoved() {
        var aliveKey = EndpointKey.endpointKey("endpoints/com.example:svc:1.0.0/doWork:0").unwrap();
        cleanup.trackEndpoint(ALIVE_NODE, aliveKey);

        var result = GovernorReconciliation.reconcile(Set.of(ALIVE_NODE), cleanup).await();

        result.onFailure(_ -> fail("Expected success"));
        assertThat(endpointMap.removedKeys).isEmpty();
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

        @Override
        public void dispatchRemotePut(byte[] rawKey, byte[] rawValue) {}

        @Override
        public void dispatchRemoteRemove(byte[] rawKey) {}
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
