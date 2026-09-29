package org.pragmatica.storage;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Unit.unit;


/// Garbage collector for orphaned storage blocks.
/// Scans metadata for blocks with zero references past their grace period
/// and deletes them from all tiers.
///
/// This interface provides the collection logic only. Scheduling is the caller's responsibility.
///
/// In a clustered deployment, GC must run on exactly ONE node per storage instance. For an instance every
/// node shares a view of, that is the leader: it activates GC on election and deactivates on demotion. An
/// instance that exists once PER NODE over that node's own disk (the `streams` instance) is its node's alone,
/// so each node runs its own (#1604).
public interface StorageGarbageCollector {
    /// Run one GC cycle. Returns the number of blocks collected.
    /// No-ops when not active.
    int collectGarbage();
    /// Accumulated collection statistics.
    GCStats stats();
    /// Activate the garbage collector, allowing collectGarbage() to process blocks.
    Result<Unit> activate();
    /// Deactivate the garbage collector. Subsequent collectGarbage() calls will no-op.
    Result<Unit> deactivate();
    /// Whether the garbage collector is currently active.
    boolean isActive();

    /// Collect, now and regardless of the grace period, the orphans whose orphaning `durable` -- a metadata
    /// snapshot already on disk -- records (#1604): blocks with no reference in that snapshot AND none now.
    /// The grace period exists so a block is not deleted before the ref drop that orphaned it is durable; for
    /// these it is, by the snapshot's content -- an ORDER bound, no clock is compared. A block re-referenced
    /// since is not orphaned now and is kept; one re-referenced and dropped again after the snapshot is still
    /// safe to delete, because the restored snapshot names no ref to it. Each deletion is the usual
    /// compare-and-remove against the record scanned now (#801). No-ops when not active.
    ///
    /// Only the snapshot's LIFECYCLES are consulted, not its refs. That is sound because the snapshot captures
    /// lifecycles before refs and every writer on `streams` credits a block before naming it (`putRef` and
    /// dedup increment, then repoint), so a ref to X can appear in the snapshot only with X already credited.
    /// `createRef` names first and credits after; it has no caller on `streams` today. A future caller of it on
    /// an instance using this method breaks the condition and must check the snapshot's refs as well.
    default int collectOrphansDurableIn(MetadataSnapshot durable) {
        return 0;
    }

    /// Statistics for garbage collection activity.
    record GCStats(int blocksCollected, long lastRunMs) {
        static GCStats empty() {
            return new GCStats(0, 0);
        }

        GCStats withCollected(int count, long runTimestamp) {
            return new GCStats(blocksCollected + count, runTimestamp);
        }
    }

    /// Create a garbage collector for the given storage instance and metadata store.
    static StorageGarbageCollector storageGarbageCollector(StorageInstance instance,
                                                           MetadataStore metadataStore,
                                                           GarbageCollectorConfig config) {
        return new DefaultStorageGarbageCollector(instance, metadataStore, config);
    }
}
