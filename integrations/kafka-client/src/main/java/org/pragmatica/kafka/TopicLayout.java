package org.pragmatica.kafka;

import java.util.List;

/// Partition metadata of one topic, from the admin client.
public record TopicLayout(String topic, List<PartitionLayout> partitions) {
    /// One partition: its leader broker id (-1 when none) and replica broker ids.
    public record PartitionLayout(int partition, int leader, List<Integer> replicas) {
        public static PartitionLayout partitionLayout(int partition, int leader, List<Integer> replicas) {
            return new PartitionLayout(partition, leader, List.copyOf(replicas));
        }
    }

    public static TopicLayout topicLayout(String topic, List<PartitionLayout> partitions) {
        return new TopicLayout(topic, List.copyOf(partitions));
    }
}
