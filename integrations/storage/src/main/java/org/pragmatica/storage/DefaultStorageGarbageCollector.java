package org.pragmatica.storage;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// Default garbage collector implementation.
/// Scans all lifecycle entries for orphaned blocks past their grace period,
/// deletes them in batches, and tracks cumulative statistics.
final class DefaultStorageGarbageCollector implements StorageGarbageCollector {
    private static final Logger log = LoggerFactory.getLogger(DefaultStorageGarbageCollector.class);

    private final StorageInstance instance;
    private final MetadataStore metadataStore;
    private final GarbageCollectorConfig config;
    private final AtomicReference<GCStats> stats = new AtomicReference<>(GCStats.empty());
    private volatile boolean active = false;

    DefaultStorageGarbageCollector(StorageInstance instance,
                                   MetadataStore metadataStore,
                                   GarbageCollectorConfig config) {
        this.instance = instance;
        this.metadataStore = metadataStore;
        this.config = config;
    }

    @Override
    public Result<Unit> activate() {
        active = true;

        return Result.success(unit());
    }

    @Override
    public Result<Unit> deactivate() {
        active = false;

        return Result.success(unit());
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public int collectGarbage() {
        if (!active) {
            return 0;
        }

        var now = System.currentTimeMillis();
        var cutoff = now - config.gracePeriodMs();
        // #737 fix round 2: grace runs from orphanedAt (when refCount reached 0), not
        // lastAccessedAt (when it was last read) -- the two diverge for a long-idle-but-still-
        // referenced block, which must get a full grace period from the moment it is orphaned.
        var collected = metadataStore.listAllLifecycles()
                                     .stream()
                                     .filter(BlockLifecycle::isOrphaned)
                                     .filter(lc -> lc.orphanedAt() <= cutoff)
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

    /// Synchronous block deletion. Uses .await() because GC runs on a dedicated
    /// background thread, not on the hot path. Blocking here is intentional.
    ///
    /// #250: uses [StorageInstance#deleteFromPrivateTiers], never [StorageInstance#delete] --
    /// orphan status here comes from THIS node's local metadata, which is not authoritative
    /// for a cluster-shared tier (another node may still hold a live reference).
    private int deleteBlock(BlockId blockId) {
        return instance.deleteFromPrivateTiers(blockId)
                       .await()
                       .fold(_ -> 0, _ -> 1);
    }
}
