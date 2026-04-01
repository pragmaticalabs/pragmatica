package org.pragmatica.storage;

/// Garbage collector for orphaned storage blocks.
/// Scans metadata for blocks with zero references past their grace period
/// and deletes them from all tiers.
///
/// This interface provides the collection logic only. Scheduling is the caller's responsibility.
///
/// In a clustered deployment, GC must run on exactly ONE node per storage instance (the leader).
/// Use the dormant/active lifecycle (activate/deactivate) to enforce single-leader coordination:
/// the leader activates GC on election and deactivates on demotion.
public interface StorageGarbageCollector {

    /// Run one GC cycle. Returns the number of blocks collected.
    /// No-ops when not active.
    int collectGarbage();

    /// Accumulated collection statistics.
    GCStats stats();

    /// Activate the garbage collector, allowing collectGarbage() to process blocks.
    void activate();

    /// Deactivate the garbage collector. Subsequent collectGarbage() calls will no-op.
    void deactivate();

    /// Whether the garbage collector is currently active.
    boolean isActive();

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
