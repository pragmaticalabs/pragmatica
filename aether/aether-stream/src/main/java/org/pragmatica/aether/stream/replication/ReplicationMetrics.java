package org.pragmatica.aether.stream.replication;
public record ReplicationMetrics( String streamName,
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

    /// Maximum replication lag across all replicas (governor offset minus slowest replica).
    public long maxLag() {
        return governorOffset - minReplicaOffset;
    }
}
