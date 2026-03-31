package org.pragmatica.aether.stream.replication;

/// Composite key identifying a specific stream partition.
public record PartitionKey(String streamName, int partition) {

    public static PartitionKey partitionKey(String streamName, int partition) {
        return new PartitionKey(streamName, partition);
    }
}
