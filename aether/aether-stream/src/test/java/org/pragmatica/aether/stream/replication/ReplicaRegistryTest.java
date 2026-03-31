package org.pragmatica.aether.stream.replication;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;

class ReplicaRegistryTest {

    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId NODE_A = NodeId.randomNodeId();
    private static final NodeId NODE_B = NodeId.randomNodeId();

    private ReplicaRegistry registry;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
    }

    @Nested
    class RegistrationTests {

        @Test
        void registerReplica_thenLookup_findsIt() {
            registry.registerReplica(STREAM, PARTITION, NODE_A);

            var replicas = registry.replicasFor(STREAM, PARTITION);

            assertThat(replicas).hasSize(1);
            assertThat(replicas.getFirst().nodeId()).isEqualTo(NODE_A);
            assertThat(replicas.getFirst().state()).isEqualTo(ReplicationState.SYNCING);
            assertThat(replicas.getFirst().confirmedOffset()).isEqualTo(-1L);
        }

        @Test
        void registerMultipleReplicas_allFound() {
            registry.registerReplica(STREAM, PARTITION, NODE_A);
            registry.registerReplica(STREAM, PARTITION, NODE_B);

            var replicas = registry.replicasFor(STREAM, PARTITION);

            assertThat(replicas).hasSize(2);
            assertThat(replicas).extracting(ReplicaDescriptor::nodeId)
                                .containsExactlyInAnyOrder(NODE_A, NODE_B);
        }

        @Test
        void unregisterReplica_removesFromLookup() {
            registry.registerReplica(STREAM, PARTITION, NODE_A);
            registry.registerReplica(STREAM, PARTITION, NODE_B);

            registry.unregisterReplica(STREAM, PARTITION, NODE_A);

            var replicas = registry.replicasFor(STREAM, PARTITION);

            assertThat(replicas).hasSize(1);
            assertThat(replicas.getFirst().nodeId()).isEqualTo(NODE_B);
        }

        @Test
        void unregisterReplica_unknownNode_noEffect() {
            registry.registerReplica(STREAM, PARTITION, NODE_A);

            registry.unregisterReplica(STREAM, PARTITION, NODE_B);

            assertThat(registry.replicasFor(STREAM, PARTITION)).hasSize(1);
        }

        @Test
        void replicasFor_unknownPartition_returnsEmpty() {
            var replicas = registry.replicasFor("nonexistent", 99);

            assertThat(replicas).isEmpty();
        }
    }

    @Nested
    class WatermarkTests {

        @Test
        void updateWatermark_updatesDescriptor() {
            registry.registerReplica(STREAM, PARTITION, NODE_A);

            registry.updateWatermark(STREAM, PARTITION, NODE_A, 42L);

            var replicas = registry.replicasFor(STREAM, PARTITION);

            assertThat(replicas.getFirst().confirmedOffset()).isEqualTo(42L);
            assertThat(replicas.getFirst().state()).isEqualTo(ReplicationState.CAUGHT_UP);
        }

        @Test
        void updateWatermark_unknownNode_noEffect() {
            registry.registerReplica(STREAM, PARTITION, NODE_A);

            registry.updateWatermark(STREAM, PARTITION, NODE_B, 42L);

            assertThat(registry.replicasFor(STREAM, PARTITION).getFirst().confirmedOffset()).isEqualTo(-1L);
        }

        @Test
        void minConfirmedOffset_multipleReplicas_returnsMinimum() {
            registry.registerReplica(STREAM, PARTITION, NODE_A);
            registry.registerReplica(STREAM, PARTITION, NODE_B);
            registry.updateWatermark(STREAM, PARTITION, NODE_A, 100L);
            registry.updateWatermark(STREAM, PARTITION, NODE_B, 50L);

            var result = registry.minConfirmedOffset(STREAM, PARTITION);

            assertThat(result.isPresent()).isTrue();
            result.onPresent(offset -> assertThat(offset).isEqualTo(50L));
        }

        @Test
        void minConfirmedOffset_noReplicas_returnsNone() {
            var result = registry.minConfirmedOffset(STREAM, PARTITION);

            assertThat(result.isEmpty()).isTrue();
        }
    }

    @Nested
    class WatermarkStoreTests {

        @Test
        void updateWatermark_callsWatermarkStore() {
            var callCount = new AtomicInteger(0);
            var lastOffset = new AtomicLong(-1);
            var lastNodeId = new AtomicReference<NodeId>();

            WatermarkStore store = (stream, partition, nodeId, offset) -> {
                callCount.incrementAndGet();
                lastOffset.set(offset);
                lastNodeId.set(nodeId);
            };

            var reg = replicaRegistry(store);
            reg.registerReplica(STREAM, PARTITION, NODE_A);

            reg.updateWatermark(STREAM, PARTITION, NODE_A, 77L);

            assertThat(callCount.get()).isEqualTo(1);
            assertThat(lastOffset.get()).isEqualTo(77L);
            assertThat(lastNodeId.get()).isEqualTo(NODE_A);
        }

        @Test
        void updateWatermark_multipleUpdates_callsStoreEachTime() {
            var callCount = new AtomicInteger(0);
            WatermarkStore store = (_, _, _, _) -> callCount.incrementAndGet();

            var reg = replicaRegistry(store);
            reg.registerReplica(STREAM, PARTITION, NODE_A);

            reg.updateWatermark(STREAM, PARTITION, NODE_A, 10L);
            reg.updateWatermark(STREAM, PARTITION, NODE_A, 20L);
            reg.updateWatermark(STREAM, PARTITION, NODE_A, 30L);

            assertThat(callCount.get()).isEqualTo(3);
        }

        @Test
        void noopFactory_doesNotCallStore() {
            var reg = replicaRegistry();
            reg.registerReplica(STREAM, PARTITION, NODE_A);

            reg.updateWatermark(STREAM, PARTITION, NODE_A, 42L);

            // Just verify the update worked without store — no exception.
            assertThat(reg.replicasFor(STREAM, PARTITION).getFirst().confirmedOffset()).isEqualTo(42L);
        }
    }
}
