package org.pragmatica.aether.stream.consumer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerGroupKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerGroupValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry.consumerGroupRegistry;


class ConsumerGroupRegistryTest {
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final NodeId NODE_C = new NodeId("node-c");
    private static final String GROUP = "my-group";
    private static final String STREAM = "events";

    @Nested
    class AssignmentTracking {

        @Test
        void assignmentsFor_emptyRegistry_returnsEmptyMap() {
            var registry = consumerGroupRegistry();

            var assignments = registry.assignmentsFor(GROUP, STREAM);

            assertThat(assignments).isEmpty();
        }

        @Test
        void assignmentsFor_afterPut_returnsAssignment() {
            var registry = consumerGroupRegistry();
            registry.onConsumerGroupPut(putEvent(GROUP, STREAM, 0, NODE_A, "c1"));

            var assignments = registry.assignmentsFor(GROUP, STREAM);

            assertThat(assignments).hasSize(1);
            assertThat(assignments.get(0).assignedTo()).isEqualTo(NODE_A);
            assertThat(assignments.get(0).consumerId()).isEqualTo("c1");
        }

        @Test
        void assignmentsFor_multiplePartitions_returnsAll() {
            var registry = consumerGroupRegistry();
            registry.onConsumerGroupPut(putEvent(GROUP, STREAM, 0, NODE_A, "c1"));
            registry.onConsumerGroupPut(putEvent(GROUP, STREAM, 1, NODE_B, "c2"));
            registry.onConsumerGroupPut(putEvent(GROUP, STREAM, 2, NODE_A, "c1"));

            var assignments = registry.assignmentsFor(GROUP, STREAM);

            assertThat(assignments).hasSize(3);
        }

        @Test
        void assignmentsFor_differentGroups_areIsolated() {
            var registry = consumerGroupRegistry();
            registry.onConsumerGroupPut(putEvent(GROUP, STREAM, 0, NODE_A, "c1"));
            registry.onConsumerGroupPut(putEvent("other-group", STREAM, 0, NODE_B, "c2"));

            assertThat(registry.assignmentsFor(GROUP, STREAM)).hasSize(1);
            assertThat(registry.assignmentsFor("other-group", STREAM)).hasSize(1);
        }
    }

    @Nested
    class PartitionLookup {

        @Test
        void partitionsFor_returnsPartitionsAssignedToNode() {
            var registry = consumerGroupRegistry();
            registry.onConsumerGroupPut(putEvent(GROUP, STREAM, 0, NODE_A, "c1"));
            registry.onConsumerGroupPut(putEvent(GROUP, STREAM, 1, NODE_B, "c2"));
            registry.onConsumerGroupPut(putEvent(GROUP, STREAM, 2, NODE_A, "c1"));
            registry.onConsumerGroupPut(putEvent(GROUP, STREAM, 3, NODE_C, "c3"));

            var partitions = registry.partitionsFor(GROUP, STREAM, NODE_A);

            assertThat(partitions).containsExactly(0, 2);
        }

        @Test
        void partitionsFor_nodeWithNoPartitions_returnsEmpty() {
            var registry = consumerGroupRegistry();
            registry.onConsumerGroupPut(putEvent(GROUP, STREAM, 0, NODE_A, "c1"));

            var partitions = registry.partitionsFor(GROUP, STREAM, NODE_B);

            assertThat(partitions).isEmpty();
        }
    }

    @Nested
    class RemoveHandling {

        @Test
        void onRemove_removesPartitionAssignment() {
            var registry = consumerGroupRegistry();
            registry.onConsumerGroupPut(putEvent(GROUP, STREAM, 0, NODE_A, "c1"));
            registry.onConsumerGroupPut(putEvent(GROUP, STREAM, 1, NODE_B, "c2"));

            registry.onConsumerGroupRemove(removeEvent(GROUP, STREAM, 0));

            var assignments = registry.assignmentsFor(GROUP, STREAM);
            assertThat(assignments).hasSize(1);
            assertThat(assignments).doesNotContainKey(0);
            assertThat(assignments.get(1).assignedTo()).isEqualTo(NODE_B);
        }

        @Test
        void onRemove_lastPartition_cleansUpGroupEntry() {
            var registry = consumerGroupRegistry();
            registry.onConsumerGroupPut(putEvent(GROUP, STREAM, 0, NODE_A, "c1"));

            registry.onConsumerGroupRemove(removeEvent(GROUP, STREAM, 0));

            assertThat(registry.assignmentsFor(GROUP, STREAM)).isEmpty();
        }

        @Test
        void onRemove_nonExistentPartition_noError() {
            var registry = consumerGroupRegistry();

            registry.onConsumerGroupRemove(removeEvent(GROUP, STREAM, 5));

            assertThat(registry.assignmentsFor(GROUP, STREAM)).isEmpty();
        }
    }

    @Nested
    class Reassignment {

        @Test
        void onPut_overwritesPreviousAssignment() {
            var registry = consumerGroupRegistry();
            registry.onConsumerGroupPut(putEvent(GROUP, STREAM, 0, NODE_A, "c1"));

            registry.onConsumerGroupPut(putEvent(GROUP, STREAM, 0, NODE_B, "c2"));

            var assignments = registry.assignmentsFor(GROUP, STREAM);
            assertThat(assignments.get(0).assignedTo()).isEqualTo(NODE_B);
            assertThat(assignments.get(0).consumerId()).isEqualTo("c2");
        }
    }

    private static ValuePut<ConsumerGroupKey, ConsumerGroupValue> putEvent(String groupId,
                                                                           String streamName,
                                                                           int partition,
                                                                           NodeId nodeId,
                                                                           String consumerId) {
        var key = ConsumerGroupKey.consumerGroupKey(groupId, streamName, partition);
        var value = ConsumerGroupValue.consumerGroupValue(nodeId, consumerId);
        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static ValueRemove<ConsumerGroupKey, ConsumerGroupValue> removeEvent(String groupId,
                                                                                  String streamName,
                                                                                  int partition) {
        var key = ConsumerGroupKey.consumerGroupKey(groupId, streamName, partition);
        return new ValueRemove<>(new KVCommand.Remove<>(key), Option.none());
    }
}
