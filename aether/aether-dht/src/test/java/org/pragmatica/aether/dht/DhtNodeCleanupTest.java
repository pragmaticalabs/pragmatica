package org.pragmatica.aether.dht;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class DhtNodeCleanupTest {
    private static final NodeId DEAD_NODE = NodeId.nodeId("dead-node-1").unwrap();
    private StubEndpointMap endpointMap;

    @BeforeEach
    void setUp() {
        endpointMap = new StubEndpointMap();
    }

    @Nested
    class CleanupDeadNodeEndpoints {
        @Test
        void cleanupDeadNodeEndpoints_withKeys_removesAllEndpoints() {
            var key1 = endpointKey("endpoints/com.example:svc:1.0.0/doWork:0");
            var key2 = endpointKey("endpoints/com.example:svc:1.0.0/doWork:1");

            var result = DhtNodeCleanup.cleanupDeadNodeEndpoints(DEAD_NODE, endpointMap, List.of(key1, key2))
                                       .await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(endpointMap.removedKeys).containsExactly(key1, key2);
        }

        @Test
        void cleanupDeadNodeEndpoints_emptyList_isNoOp() {
            var result = DhtNodeCleanup.cleanupDeadNodeEndpoints(DEAD_NODE, endpointMap, List.of())
                                       .await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(endpointMap.removedKeys).isEmpty();
        }

        @Test
        void cleanupDeadNodeEndpoints_singleKey_removesOne() {
            var key = endpointKey("endpoints/com.example:svc:1.0.0/handle:0");

            var result = DhtNodeCleanup.cleanupDeadNodeEndpoints(DEAD_NODE, endpointMap, List.of(key))
                                       .await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(endpointMap.removedKeys).containsExactly(key);
        }
    }

    private static EndpointKey endpointKey(String raw) {
        return EndpointKey.endpointKey(raw).unwrap();
    }

    /// Stub ReplicatedMap that records remove calls.
    static class StubEndpointMap implements ReplicatedMap<EndpointKey, EndpointValue> {
        final CopyOnWriteArrayList<EndpointKey> removedKeys = new CopyOnWriteArrayList<>();

        @Override
        public Promise<Unit> put(EndpointKey key, EndpointValue value) {
            return Promise.success(Unit.unit());
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
}
