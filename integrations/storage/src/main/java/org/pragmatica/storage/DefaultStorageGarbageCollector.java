package org.pragmatica.storage;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Default garbage collector implementation.
/// Scans all lifecycle entries for orphaned blocks past their grace period,
/// deletes them in batches, and tracks cumulative statistics.
final class DefaultStorageGarbageCollector implements StorageGarbageCollector {
    private static final Logger log = LoggerFactory.getLogger(DefaultStorageGarbageCollector.class);

    private final StorageInstance instance;
    private final MetadataStore metadataStore;
    private final GarbageCollectorConfig config;
    private final AtomicReference<GCStats> stats = new AtomicReference<>(GCStats.empty());

    DefaultStorageGarbageCollector(StorageInstance instance,
                                   MetadataStore metadataStore,
                                   GarbageCollectorConfig config) {
        this.instance = instance;
        this.metadataStore = metadataStore;
        this.config = config;
    }

    @Override
    public int collectGarbage() {
        var now = System.currentTimeMillis();
        var cutoff = now - config.gracePeriodMs();

        var collected = metadataStore.listAllLifecycles()
                                     .stream()
                                     .filter(BlockLifecycle::isOrphaned)
                                     .filter(lc -> lc.lastAccessedAt() <= cutoff)
                                     .limit(config.batchSize())
                                     .map(lc -> deleteBlock(lc.blockId()))
                                     .reduce(0, Integer::sum);

        stats.updateAndGet(s -> s.withCollected(collected, now));
        log.debug("GC cycle completed: {} block(s) collected", collected);
        return collected;
    }

    @Override
    public GCStats stats() {
        return stats.get();
    }

    private int deleteBlock(BlockId blockId) {
        instance.delete(blockId).await();
        return 1;
    }
}
