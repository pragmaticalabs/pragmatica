package org.pragmatica.aether.stream.replication;

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
}
