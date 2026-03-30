package org.pragmatica.aether.storage;

import org.pragmatica.lang.Option;

/// Manages automatic metadata snapshots to local disk.
/// Runs on each node independently -- dual-condition trigger (mutation count OR time interval).
public interface SnapshotManager {

    /// Check if a snapshot is needed and take one if so.
    /// Called after metadata mutations.
    void maybeSnapshot();

    /// Force a snapshot regardless of triggers.
    void forceSnapshot();

    /// Restore metadata from the latest local disk snapshot.
    /// Returns the restored snapshot, or none if no snapshot exists.
    Option<MetadataSnapshot> restoreFromLatest();

    /// Current snapshot epoch.
    long lastSnapshotEpoch();

    /// Factory method.
    static SnapshotManager snapshotManager(MetadataStore metadataStore, SnapshotConfig config) {
        return new DefaultSnapshotManager(metadataStore, config);
    }
}
