package org.pragmatica.storage;

import java.nio.file.Path;

import org.pragmatica.lang.Option;


/// Configuration for metadata snapshot manager.
///
/// @param snapshotPath directory where snapshot files are stored
/// @param mutationThreshold number of mutations before triggering a snapshot
/// @param maxIntervalMillis maximum time between snapshots
/// @param retentionCount number of old snapshots to retain
/// @param nodeId the node identity for snapshot attribution
/// @param volumeSentinel #1473: a file OUTSIDE the volume that holds the id of the volume the snapshot
///        directory lives on, present only when the disk tier is armed (a degraded tier has no
///        durable volume to lose). Once the sentinel exists, an empty snapshot directory without the
///        matching `.volume-id` inside it is a different (or unmounted) volume, not a first boot.
public record SnapshotConfig(Path snapshotPath,
                             int mutationThreshold,
                             long maxIntervalMillis,
                             int retentionCount,
                             String nodeId,
                             Option<Path> volumeSentinel) {
    public static SnapshotConfig snapshotConfig(Path snapshotPath, String nodeId) {
        return new SnapshotConfig(snapshotPath, 1000, 60_000, 5, nodeId, Option.none());
    }

    public static SnapshotConfig snapshotConfig(Path snapshotPath,
                                                int mutationThreshold,
                                                long maxIntervalMillis,
                                                int retentionCount,
                                                String nodeId) {
        return new SnapshotConfig(snapshotPath,
                                  mutationThreshold,
                                  maxIntervalMillis,
                                  retentionCount,
                                  nodeId,
                                  Option.none());
    }

    public SnapshotConfig withVolumeSentinel(Option<Path> sentinel) {
        return new SnapshotConfig(snapshotPath, mutationThreshold, maxIntervalMillis, retentionCount, nodeId, sentinel);
    }
}
