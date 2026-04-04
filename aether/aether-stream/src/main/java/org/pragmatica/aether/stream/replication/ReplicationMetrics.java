package org.pragmatica.aether.stream.replication;

public record ReplicationMetrics(String streamName,
                                 int partition,
                                 long governorOffset,
                                 long minReplicaOffset,
                                 int replicaCount) {
    public static ReplicationMetrics replicationMetrics(String streamName,
                                                        int partition,
                                                        long governorOffset,
                                                        long minReplicaOffset,
                                                        int replicaCount) {
        return new ReplicationMetrics(streamName, partition, governorOffset, minReplicaOffset, replicaCount);
    }

    public long maxLag() {
        return governorOffset - minReplicaOffset;
    }
}
