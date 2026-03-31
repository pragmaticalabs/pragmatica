package org.pragmatica.storage;

/// Garbage collector for orphaned storage blocks.
/// Scans metadata for blocks with zero references past their grace period
/// and deletes them from all tiers.
///
/// This interface provides the collection logic only. Scheduling is the caller's responsibility.
public interface StorageGarbageCollector {

    /// Run one GC cycle. Returns the number of blocks collected.
    int collectGarbage();

    /// Accumulated collection statistics.
    GCStats stats();

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
