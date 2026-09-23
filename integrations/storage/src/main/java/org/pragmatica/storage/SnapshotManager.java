package org.pragmatica.storage;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// Manages automatic metadata snapshots to local disk.
/// Runs on each node independently -- dual-condition trigger (mutation count OR time interval).
public interface SnapshotManager {
    /// Check if a snapshot is needed and take one if so.
    /// Called after metadata mutations.
    @Contract
    void maybeSnapshot();

    /// Force a snapshot regardless of triggers.
    @Contract
    void forceSnapshot();

    /// Restore metadata from the latest local disk snapshot. Three outcomes, kept apart (#1013):
    /// success with the snapshot when one restored (#1353: possibly an older retained one, WARNed);
    /// success with none when NOTHING is on disk -- no `LATEST` and no snapshot file, established
    /// by looking, never inferred from a read that failed; failure when something is on disk and
    /// none of it restores. A caller that signals readiness must not do so on the third.
    Result<Option<MetadataSnapshot>> restoreFromLatest();
    /// Current snapshot epoch.
    long lastSnapshotEpoch();
    /// Timestamp of the last snapshot in milliseconds since epoch.
    long lastSnapshotTimestamp();

    /// Factory method.
    static SnapshotManager snapshotManager(MetadataStore metadataStore, SnapshotConfig config) {
        return new DefaultSnapshotManager(metadataStore, config);
    }
}
