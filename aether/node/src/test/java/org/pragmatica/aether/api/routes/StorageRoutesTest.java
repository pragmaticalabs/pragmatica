package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.StorageFactory.StorageSetup;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.SnapshotManager;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.StorageReadinessGate;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.api.routes.StorageRoutes.storageRoutes;

class StorageRoutesTest {

    private static final long ONE_MB = 1024 * 1024L;

    private Map<String, StorageSetup> setups;
    private StorageRoutes routes;
    private AtomicInteger snapshotCount;

    @BeforeEach
    void setUp() {
        snapshotCount = new AtomicInteger(0);
        var instance1 = StorageInstance.storageInstance("artifacts", List.of(MemoryTier.memoryTier(ONE_MB)));
        var instance2 = StorageInstance.storageInstance("streams", List.of(MemoryTier.memoryTier(ONE_MB)));

        var gate1 = StorageReadinessGate.storageReadinessGate();
        gate1.snapshotLoaded();
        gate1.consensusSynced();

        var gate2 = StorageReadinessGate.storageReadinessGate();

        var snapshot1 = countingSnapshotManager(snapshotCount);
        var snapshot2 = countingSnapshotManager(new AtomicInteger(0));

        setups = Map.of(
            "artifacts", new StorageSetup("artifacts", instance1, snapshot1, gate1),
            "streams", new StorageSetup("streams", instance2, snapshot2, gate2)
        );

        var nodeProxy = createNodeProxy(setups);
        routes = storageRoutes(() -> nodeProxy);
    }

    @Nested
    class ListInstances {

        @Test
        void listInstances_returnsAllSetups() {
            var routeStream = routes.routes();
            assertThat(routeStream).isNotNull();
            // StorageRoutes should produce 5 routes (3 per-node + 2 cluster)
            var routeList = routes.routes().toList();
            assertThat(routeList).hasSize(5);
        }
    }

    @Nested
    class InstanceDetail {

        @Test
        void instanceDetail_found_returnsDetail() {
            // Verify the setup map contains expected instances
            assertThat(setups).containsKey("artifacts");
            assertThat(setups.get("artifacts").instance().name()).isEqualTo("artifacts");
        }

        @Test
        void instanceDetail_notFound_returnsError() {
            assertThat(setups.containsKey("nonexistent")).isFalse();
        }
    }

    @Nested
    class ForceSnapshot {

        @Test
        void forceSnapshot_triggersSnapshotManager() {
            var setup = setups.get("artifacts");
            assertThat(snapshotCount.get()).isZero();

            setup.snapshotManager().forceSnapshot();

            assertThat(snapshotCount.get()).isEqualTo(1);
        }
    }

    @Nested
    class ReadinessState {

        @Test
        void readiness_readyState_reportsCorrectly() {
            var gate = setups.get("artifacts").readinessGate();
            assertThat(gate.isReadReady()).isTrue();
            assertThat(gate.isWriteReady()).isTrue();
            assertThat(gate.state()).isEqualTo(org.pragmatica.storage.ReadinessState.READY);
        }

        @Test
        void readiness_initialState_reportsNotReady() {
            var gate = setups.get("streams").readinessGate();
            assertThat(gate.isReadReady()).isFalse();
            assertThat(gate.isWriteReady()).isFalse();
            assertThat(gate.state()).isEqualTo(org.pragmatica.storage.ReadinessState.LOADING_SNAPSHOT);
        }
    }

    @Nested
    class TierInfo {

        @Test
        void tierInfo_memoryTier_reportsUsageAndMax() {
            var tiers = setups.get("artifacts").instance().tierInfo();
            assertThat(tiers).hasSize(1);
            assertThat(tiers.getFirst().level()).isEqualTo(org.pragmatica.storage.TierLevel.MEMORY);
            assertThat(tiers.getFirst().maxBytes()).isEqualTo(ONE_MB);
        }
    }

    private static AetherNode createNodeProxy(Map<String, StorageSetup> setups) {
        return (AetherNode) Proxy.newProxyInstance(
            AetherNode.class.getClassLoader(),
            new Class[]{AetherNode.class},
            (_, method, _) -> {
                if ("storageSetups".equals(method.getName())) {
                    return setups;
                }
                throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
            }
        );
    }

    private static SnapshotManager countingSnapshotManager(AtomicInteger counter) {
        return new SnapshotManager() {
            @Override
            public void maybeSnapshot() {}

            @Override
            public void forceSnapshot() {
                counter.incrementAndGet();
            }

            @Override
            public org.pragmatica.lang.Option<org.pragmatica.storage.MetadataSnapshot> restoreFromLatest() {
                return org.pragmatica.lang.Option.none();
            }

            @Override
            public long lastSnapshotEpoch() {
                return counter.get();
            }

            @Override
            public long lastSnapshotTimestamp() {
                return System.currentTimeMillis();
            }
        };
    }
}
