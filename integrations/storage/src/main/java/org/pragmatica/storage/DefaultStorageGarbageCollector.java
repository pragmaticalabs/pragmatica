package org.pragmatica.storage;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
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
        // A cache-only marker is never orphaned (BlockLifecycle#isOrphaned): this node only read or prefetched
        // the block, so there is no reference of its own to lose and nothing here for GC to collect.
        // #737 fix round 2: grace runs from orphanedAt (when refCount reached 0), not
        // lastAccessedAt (when it was last read) -- the two diverge for a long-idle-but-still-
        // referenced block, which must get a full grace period from the moment it is orphaned.
        // #2258: an orphan with shared-delete authority is collectSharedDeletes's, which deletes the shared copy too; this
        // tick would delete the private copies and drop the record, and the shared copy would leak for good.
        var collected = metadataStore.listAllLifecycles()
                                     .stream()
                                     .filter(BlockLifecycle::isOrphaned)
                                     .filter(lc -> lc.sharedDelete() == SharedDelete.NONE)
                                     .filter(lc -> lc.orphanedAt() <= cutoff)
                                     .limit(config.batchSize())
                                     .map(this::deleteBlock)
                                     .reduce(0, Integer::sum);

        stats.updateAndGet(s -> s.withCollected(collected, now));
        log.debug("GC cycle completed: {} block(s) collected", collected);

        return collected;
    }

    @Override
    public int collectOrphansDurableIn(MetadataSnapshot durable) {
        if (!active) {
            return 0;
        }

        var durableOrphans = durableOrphansOf(durable);
        // #2258 (review R32D-01): an orphan with shared-delete authority goes every tier, but only when its authority is in
        // the durable snapshot too; one whose authority is not yet durable is left for collectSharedDeletes rather than
        // deleted privately, which would drop its record and leak its shared copy.
        var collected = metadataStore.listAllLifecycles()
                                     .stream()
                                     .filter(BlockLifecycle::isOrphaned)
                                     .filter(lc -> option(durableOrphans.get(lc.blockId())).filter(saved -> saved.sharedDelete() == lc.sharedDelete())
                                                                                     .isPresent())
                                     .limit(config.batchSize())
                                     .map(this::deleteFlaggedOrPrivate)
                                     .reduce(0, Integer::sum);

        stats.updateAndGet(s -> s.withCollected(collected, System.currentTimeMillis()));
        log.debug("Pressure GC collected {} block(s) orphaned in snapshot epoch {}", collected, durable.epoch());

        return collected;
    }

    @Override
    public SharedDeleteRun collectSharedDeletes(SnapshotManager snapshots, SharedDeleteLimits limits) {
        if (!active) {
            return SharedDeleteRun.NOTHING;
        }

        var cutoff = System.currentTimeMillis() - limits.graceMs();
        var flagged = flaggedOrphans();

        if (flagged.isEmpty()) {
            return SharedDeleteRun.NOTHING;
        }

        return snapshots.snapshotNow()
                        .map(durable -> collectDurablyFlagged(flagged, durableOrphansOf(durable), cutoff, limits))
                        .onFailure(cause -> log.warn("Shared-tier collection deferred: the metadata snapshot that makes its "
                                                    + "authority durable could not be written: {}",
                                                     cause.message()))
                        .or(new SharedDeleteRun(0, 0, flagged.size()));
    }

    private List<BlockLifecycle> flaggedOrphans() {
        return metadataStore.listAllLifecycles()
                            .stream()
                            .filter(BlockLifecycle::isOrphaned)
                            .filter(lc -> lc.sharedDelete() != SharedDelete.NONE)
                            .toList();
    }

    private SharedDeleteRun collectDurablyFlagged(List<BlockLifecycle> flagged,
                                                  Map<BlockId, BlockLifecycle> durable,
                                                  long cutoff,
                                                  SharedDeleteLimits limits) {
        var ready = flagged.stream()
                           .filter(lc -> lc.orphanedAt() <= cutoff)
                           .filter(lc -> option(durable.get(lc.blockId())).filter(saved -> saved.sharedDelete() == lc.sharedDelete())
                                                                     .isPresent())
                           .limit(limits.maxBlocks())
                           .toList();
        var deadline = System.currentTimeMillis() + limits.maxDurationMs();
        var deleted = 0;
        var failed = 0;
        var started = 0;

        while (started < ready.size() && System.currentTimeMillis() < deadline) {
            var batch = ready.subList(started, Math.min(started + limits.concurrency(), ready.size()));
            var outcomes = Promise.allOf(batch.stream()
                                              .map(instance::deleteEverywhere)
                                              .toList())
                                  .await();

            started += batch.size();
            for (var outcome : outcomes.or(List.of())) {
                if (outcome.isFailure()) {
                    failed++;
                } else if (outcome.or(false)) {
                    deleted++;
                }
            }
        }

        var counted = deleted;

        stats.updateAndGet(s -> s.withCollected(counted, System.currentTimeMillis()));

        return new SharedDeleteRun(deleted, failed, flagged.size() - started);
    }

    private static Map<BlockId, BlockLifecycle> durableOrphansOf(MetadataSnapshot durable) {
        return durable.lifecycles()
                      .stream()
                      .filter(BlockLifecycle::isOrphaned)
                      .collect(Collectors.toMap(BlockLifecycle::blockId, lc -> lc, (first, _) -> first));
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
    ///
    /// #801: passes the record AS SCANNED, not just its id. The scan is a snapshot, and a
    /// deduplicating put can land on the block between the scan and this call; the instance
    /// deletes only if the record still equals the scanned one, and reports `false` otherwise,
    /// which is not a collection.
    private int deleteBlock(BlockLifecycle orphan) {
        return instance.deleteFromPrivateTiers(orphan)
                       .await()
                       .fold(_ -> 0, DefaultStorageGarbageCollector::countOf);
    }

    /// The pressure path: an orphan with durable shared-delete authority goes every tier, any other the private way.
    private int deleteFlaggedOrPrivate(BlockLifecycle orphan) {
        return (orphan.sharedDelete() == SharedDelete.NONE
                ? instance.deleteFromPrivateTiers(orphan)
                : instance.deleteEverywhere(orphan)).await()
                                                    .fold(_ -> 0, DefaultStorageGarbageCollector::countOf);
    }

    private static int countOf(boolean collected) {
        return collected
               ? 1
               : 0;
    }
}
