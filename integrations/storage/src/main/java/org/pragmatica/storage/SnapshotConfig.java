package org.pragmatica.storage;

import java.nio.file.Path;

/// Configuration for metadata snapshot manager.
///
/// @param snapshotPath directory where snapshot files are stored
/// @param mutationThreshold number of mutations before triggering a snapshot
/// @param maxIntervalMillis maximum time between snapshots
/// @param retentionCount number of old snapshots to retain
/// @param nodeId the node identity for snapshot attribution
public record SnapshotConfig(Path snapshotPath,
                             int mutationThreshold,
                             long maxIntervalMillis,
                             int retentionCount,
                             String nodeId) {

    public static SnapshotConfig snapshotConfig(Path snapshotPath, String nodeId) {
        return new SnapshotConfig(snapshotPath, 1000, 60_000, 5, nodeId);
    }

    public static SnapshotConfig snapshotConfig(Path snapshotPath, int mutationThreshold,
                                                long maxIntervalMillis, int retentionCount,
                                                String nodeId) {
        return new SnapshotConfig(snapshotPath, mutationThreshold, maxIntervalMillis, retentionCount, nodeId);
    }
}
