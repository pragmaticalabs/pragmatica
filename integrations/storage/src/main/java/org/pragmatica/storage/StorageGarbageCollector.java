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
/// Local refcount GC deletes only from node-private tiers and a node's refcounts describe its own holdings, so it
/// runs on EVERY node over that node's own disk: nothing about it is leader-only. A block on a SHARED tier is deleted only
/// on the authority its last released reference carried ([SharedDelete], #2258), by [#collectSharedDeletes].
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

    /// The orphans whose last released reference carried shared-delete authority (#2258, [SharedDelete]), collected from
    /// EVERY tier, the shared tier first ([StorageInstance#deleteEverywhere]). An authority is acted on only once it is
    /// durable: the run forces a metadata snapshot through `snapshots` and deletes only orphans whose record in THAT
    /// snapshot carries the same authority, and that were orphaned at least `limits.graceMs()` ago. Before the snapshot a
    /// restart restores the reference with its block intact; after it the authority survives the restart and the next run
    /// completes the delete. The tick ([#collectGarbage]) leaves these orphans alone, so a DHT outage stalls only this run,
    /// which is bounded by `limits`. No snapshot is forced when there is nothing to collect. No-ops when not active.
    default SharedDeleteRun collectSharedDeletes(SnapshotManager snapshots, SharedDeleteLimits limits) {
        return SharedDeleteRun.NOTHING;
    }

    /// The bounds of one [#collectSharedDeletes] run: the grace after the release, the deletes in flight at once, and the
    /// blocks and time after which the run stops and leaves the rest to the next.
    record SharedDeleteLimits(long graceMs, int concurrency, int maxBlocks, long maxDurationMs) {
        /// 2 min of grace (above the 30 s metadata snapshot interval), 8 at a time, at most 10,000 blocks or 60 s a run.
        public static final SharedDeleteLimits DEFAULT = new SharedDeleteLimits(120_000L, 8, 10_000, 60_000L);
    }

    /// What one [#collectSharedDeletes] run did: blocks deleted from every tier, deletes that failed (their record is back
    /// for the next run), and the flagged orphans it left for a later run (not yet durable, not past the grace, or past
    /// the run's bounds).
    record SharedDeleteRun(int deleted, int failed, int deferred) {
        public static final SharedDeleteRun NOTHING = new SharedDeleteRun(0, 0, 0);
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
