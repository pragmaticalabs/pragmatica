package org.pragmatica.aether.stream.replication;

public record PartitionKey(String streamName, int partition) {
    public static PartitionKey partitionKey(String streamName, int partition) {
        return new PartitionKey(streamName, partition);
    }
}
