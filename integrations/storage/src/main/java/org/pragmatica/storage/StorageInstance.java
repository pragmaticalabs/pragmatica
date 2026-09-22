package org.pragmatica.storage;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Promise.resolved;
import static org.pragmatica.lang.Unit.unit;


/// Hierarchical storage instance with write-through and tier-waterfall reads.
/// Each instance has its own name, tier configuration, and metadata tracking.
public interface StorageInstance {
    /// Store content -- computes SHA-256, deduplicates, writes through tiers.
    Promise<BlockId> put(byte[] content);
    /// Store content with explicit metadata.
    Promise<BlockId> put(byte[] content, BlockMetadata metadata);
    /// Read content by block ID -- waterfall through tiers by latency.
    Promise<Option<byte[]>> get(BlockId id);
    /// Check if a block exists in any tier.
    Promise<Boolean> exists(BlockId id);
    /// Adds a named reference to a block this instance ALREADY holds, crediting one reference for the
    /// new name. This is the aliasing primitive -- a second (third, ...) name for a block that some
    /// other reference is already keeping alive.
    ///
    /// NEVER pair it with [#put] for the same block. [#put] already credits the block it writes -- a
    /// fresh write starts at refCount 1, a deduplicating write increments an existing one -- so
    /// `put`-then-`createRef` leaves refCount 2 for ONE logical reference, and an explicit
    /// [#deleteRef] afterwards only brings it back to 1. The block never reaches zero, never reports
    /// [BlockLifecycle#isOrphaned], and [StorageGarbageCollector] can never collect it (#812). To
    /// write content and name it, use [#putRef], which credits exactly once.
    Promise<Unit> createRef(String name, BlockId id);
    /// Resolve a named reference to its block ID.
    Option<BlockId> resolveRef(String name);
    /// Delete a named reference.
    Promise<Unit> deleteRef(String name);
    /// Writes (or deduplicates) `content` and points `name` at the resulting block -- the write-and-ref
    /// primitive, and the only correct way to store content under a name.
    ///
    /// Credits the block EXACTLY ONE reference, for the one named reference it creates, and releases
    /// whatever `name` previously pointed to. Both halves are load-bearing: crediting twice ([#put]
    /// followed by [#createRef]) produces a block that can never reach refCount 0 (#812), and failing
    /// to release the displaced target leaks that block's count forever. Creating and replacing are
    /// therefore the same operation -- creating replaces nothing -- so `name` never has to be known to
    /// be absent for this to be correct, and re-storing the SAME content under the same name is a
    /// no-op on the count rather than a leak.
    ///
    /// Never leaves `name` absent: it resolves to the old target or the new one at every instant,
    /// unlike [#deleteRef] followed by [#createRef] (#264).
    Promise<BlockId> putRef(String name, byte[] content);

    /// Replaces what `name` points to. The replace-oriented name for [#putRef] -- the same operation
    /// with the same counting -- kept because #737's caller (cursor commits) expresses replacement
    /// rather than creation.
    ///
    /// Routing the default through [#putRef] is what keeps it honest on every implementor: the
    /// previous default composed [#put] with [#createRef] and so carried two independent counting
    /// defects onto anything that did not override it -- it never decremented the superseded block,
    /// AND it double-counted the new one (#812).
    ///
    /// [DefaultStorageInstance]'s composite: the metadata pointer swap itself is atomic, but the new
    /// block's credit and the displaced block's decrement are only ordered, not atomic together -- a
    /// crash between them over-counts the displaced block (#737).
    default Promise<BlockId> replaceRef(String name, byte[] content) {
        return putRef(name, content);
    }

    /// Delete a block from all tiers and remove its lifecycle metadata. Used by explicit content/manifest
    /// deletion and stream retention -- never by GC, which uses [#deleteFromPrivateTiers] (#250).
    Promise<Unit> delete(BlockId id);

    /// Collect `orphan` -- the lifecycle record exactly as the caller's scan saw it -- from
    /// node-private tiers. A tier reporting [StorageTier#isShared] is skipped -- this node's local
    /// refcount belief is not authoritative for a cluster-shared tier, so orphan-driven garbage
    /// collection must never issue a delete against it. Used by [StorageGarbageCollector];
    /// callers that legitimately need "delete everywhere" (explicit content/manifest
    /// deletion, stream retention) must keep using [#delete].
    ///
    /// Resolves to `true` when the block was collected and `false` when it was NOT, because its
    /// record no longer equals `orphan` -- something (a deduplicating [#put], a read, a
    /// [#createRef]) touched it after the scan, and the scan's orphan verdict is stale (#801).
    /// Only the scanned record is a valid argument: a caller that constructs one gets `false`.
    ///
    /// Default falls back to [#delete] -- correct for any implementation with no
    /// tier-sharing concept (e.g. test doubles). Only [DefaultStorageInstance], where the
    /// real hazard exists, overrides this with tier-filtered, record-conditional deletion.
    default Promise<Boolean> deleteFromPrivateTiers(BlockLifecycle orphan) {
        return delete(orphan.blockId()).map(_ -> true);
    }

    /// Instance name.
    String name();
    /// Tier utilization info.
    List<TierInfo> tierInfo();

    record TierInfo(TierLevel level, long usedBytes, long maxBytes) {
        static TierInfo tierInfo(TierLevel level, long usedBytes, long maxBytes) {
            return new TierInfo(level, usedBytes, maxBytes);
        }
    }

    /// Graceful shutdown — drains pending writes (write-behind) and releases resources.
    @Contract
    void shutdown();

    /// Create a storage instance with write-through policy and in-memory metadata store.
    static StorageInstance storageInstance(String name, List<StorageTier> tiers) {
        return storageInstance(name, tiers, WritePolicy.WRITE_THROUGH);
    }

    /// Create a storage instance with specified write policy and in-memory metadata store.
    static StorageInstance storageInstance(String name, List<StorageTier> tiers, WritePolicy writePolicy) {
        return storageInstance(name, tiers, InMemoryMetadataStore.inMemoryMetadataStore(name), writePolicy);
    }

    /// Create a storage instance with a custom metadata store and write-through policy.
    static StorageInstance storageInstance(String name, List<StorageTier> tiers, MetadataStore metadataStore) {
        return storageInstance(name, tiers, metadataStore, WritePolicy.WRITE_THROUGH);
    }

    /// Create a storage instance with a custom metadata store and write policy.
    static StorageInstance storageInstance(String name,
                                           List<StorageTier> tiers,
                                           MetadataStore metadataStore,
                                           WritePolicy writePolicy) {
        return new DefaultStorageInstance(name, tiers, metadataStore, writePolicy);
    }
}

final class DefaultStorageInstance implements StorageInstance {
    private static final Logger log = LoggerFactory.getLogger(DefaultStorageInstance.class);
    private static final long PROMOTION_FAILURE_WARN_EVERY = 1_000;

    private final String name;
    private final List<StorageTier> tiers;
    private final MetadataStore metadataStore;
    private final WritePolicy writePolicy;
    private final Option<WriteBehindQueue> writeBehindQueue;
    /// Non-capacity promotion failures per cache tier, for the WARN-once-then-every-N policy (#910).
    private final Map<TierLevel, AtomicLong> promotionFailures = new ConcurrentHashMap<>();
    private final SingleFlightCache readCache = SingleFlightCache.singleFlightCache();
    /// Blocks whose private-tier bytes GC is deleting right now, keyed by id, each resolving when that
    /// deletion has finished. A [#put] whose claim succeeds while its id is here has claimed the slot
    /// GC just vacated and must not write until GC's tier deletes are done, or GC deletes the bytes
    /// it has just written (#801). One collection per id at a time: the only caller is the single
    /// scheduled maintenance tick, which awaits each block in turn, so `putIfAbsent` never loses --
    /// it is there so that a second collector for the same id would wait on the first's promise
    /// rather than replace it.
    private final Map<BlockId, Promise<Unit>> collecting = new ConcurrentHashMap<>();

    DefaultStorageInstance(String name, List<StorageTier> tiers, MetadataStore metadataStore, WritePolicy writePolicy) {
        this.name = name;
        this.tiers = List.copyOf(tiers);
        this.metadataStore = metadataStore;
        this.writePolicy = writePolicy;
        this.writeBehindQueue = writePolicy == WritePolicy.WRITE_BEHIND
                                ? some(WriteBehindQueue.writeBehindQueue())
                                : none();
        writeBehindQueue.onPresent(WriteBehindQueue::activate);
        log.info("Storage instance '{}' created with {} tier(s), policy={}", name, tiers.size(), writePolicy);
    }

    @Override
    public Promise<BlockId> put(byte[] content) {
        return put(content, BlockMetadata.blockMetadata(content.length));
    }

    @Override
    public Promise<BlockId> put(byte[] content, BlockMetadata metadata) {
        return BlockId.blockId(content)
                      .async()
                      .flatMap(id -> handlePut(id, content));
    }

    @Override
    public Promise<Option<byte[]>> get(BlockId id) {
        return readCache.deduplicate(id,
                                     () -> waterfallRead(id))
                        .onSuccess(opt -> opt.onPresent(_ -> recordAccess(id)));
    }

    @Override
    public Promise<Boolean> exists(BlockId id) {
        return metadataStore.containsBlock(id)
               ? Promise.success(true)
               : checkTiersForExistence(id, 0);
    }

    @Override
    public Promise<Unit> createRef(String refName, BlockId id) {
        metadataStore.putRef(refName, id);
        metadataStore.computeLifecycle(id, BlockLifecycle::withRefCountIncremented);

        return Promise.success(unit());
    }

    @Override
    public Option<BlockId> resolveRef(String refName) {
        return metadataStore.resolveRef(refName);
    }

    @Override
    public Promise<Unit> deleteRef(String refName) {
        metadataStore.removeRef(refName)
                     .onPresent(id -> metadataStore.computeLifecycle(id, BlockLifecycle::withRefCountDecremented));

        return Promise.success(unit());
    }

    @Override
    public Promise<BlockId> putRef(String refName, byte[] content) {
        return BlockId.blockId(content)
                      .async()
                      .flatMap(id -> handlePut(id, content))
                      .map(id -> repointRef(refName, id));
    }

    @Override
    public Promise<Unit> delete(BlockId id) {
        return deleteFromAllTiers(id, 0).onSuccess(_ -> removeLifecycleMetadata(id, "deleted from all tiers"));
    }

    /// #801: the scan's verdict is only good until something touches the record, so the record is
    /// taken FIRST, by compare-and-remove against the scanned value -- a deduplicating put, a read
    /// or a ref that landed after the scan has changed it, the remove fails and nothing is deleted.
    /// Once the record is gone no put can deduplicate onto this block (its claim succeeds instead);
    /// `collecting` makes that claimant wait for the tier deletes so they cannot wipe its fresh
    /// write. A tier delete that fails puts the scanned record back (if no claimant has taken the
    /// slot) so the next cycle retries it, as it did when the record was removed last -- and does so
    /// INSIDE the resolution of the failed delete, before the claimant is released and before the
    /// returned promise resolves, never as an `onFailure` side effect (those run on the executor,
    /// after the caller has already seen the result).
    @Override
    public Promise<Boolean> deleteFromPrivateTiers(BlockLifecycle orphan) {
        var id = orphan.blockId();
        var done = Promise.<Unit> promise();

        collecting.putIfAbsent(id, done);
        if (!metadataStore.releaseClaim(id, orphan)) {
            finishCollecting(id, done);
            log.debug("Block {} touched since the GC scan, not collected", id);

            return Promise.success(false);
        }

        return deleteFromPrivateTiers(id, 0).fold(result -> collected(orphan, done, result));
    }

    private Promise<Boolean> collected(BlockLifecycle orphan, Promise<Unit> done, Result<Unit> result) {
        var id = orphan.blockId();

        result.onFailure(_ -> metadataStore.claimBlock(id, orphan));
        finishCollecting(id, done);
        result.onSuccess(_ -> log.debug("Block {} deleted from private tiers; shared copy retained", id));

        return resolved(result.map(_ -> true));
    }

    private void finishCollecting(BlockId id, Promise<Unit> done) {
        collecting.remove(id, done);
        done.succeed(unit());
    }

    @Override
    @Contract
    public void shutdown() {
        writeBehindQueue.onPresent(WriteBehindQueue::deactivate);
        log.info("Storage instance '{}' shut down", name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<TierInfo> tierInfo() {
        return tiers.stream()
                    .map(DefaultStorageInstance::toTierInfo)
                    .toList();
    }

    // --- Write flow ---
    private Promise<BlockId> handlePut(BlockId id, byte[] content) {
        var sentinel = sentinelFor(id);

        return metadataStore.claimBlock(id, sentinel)
               ? afterCollection(id).flatMap(_ -> writeThroughTiers(id, content))
                                .onFailure(_ -> metadataStore.releaseClaim(id, sentinel))
               : deduplicateBlock(id, content);
    }

    /// #801: a claim that succeeded because GC has just compare-and-removed this id's orphan record
    /// must let GC's in-flight tier deletes finish before writing. Registered before the remove, so
    /// a claimant that observed the removal observes the registration too.
    private Promise<Unit> afterCollection(BlockId id) {
        return option(collecting.get(id)).or(Promise.success(unit()));
    }

    /// Points `refName` at `newId`, decrementing whatever it previously pointed to. `newId`'s own
    /// reference count was already accounted for by [#handlePut] -- a fresh block starts at refCount
    /// 1, deduplication already incremented an existing one -- so this only has to release the
    /// superseded target. The metadata pointer swap itself is atomic; the credit (already applied by
    /// [#handlePut], before this call) and the decrement (applied here, after the swap) are only
    /// ordered, not atomic together -- a crash between the swap and the decrement over-counts the
    /// displaced block (never decremented, stays live). The swap-then-decrement order is still load-
    /// bearing for a different hazard: a concurrent GC scan can never observe a floor-clamped
    /// transient zero on a block that is, at that same instant, still genuinely live (#737).
    private BlockId repointRef(String refName, BlockId newId) {
        metadataStore.replaceRef(refName, newId)
                     .onPresent(oldId -> metadataStore.computeLifecycle(oldId, BlockLifecycle::withRefCountDecremented));

        return newId;
    }

    /// The claim IS the block's lifecycle record -- there is no second one. It names the tier the
    /// active policy writes first, and [#trackNewBlock] finalizes it in place once the write lands.
    private BlockLifecycle sentinelFor(BlockId id) {
        return BlockLifecycle.blockLifecycle(id, initialTier());
    }

    private TierLevel initialTier() {
        return writePolicy == WritePolicy.WRITE_BEHIND
               ? tiers.getFirst()
                      .level()
               : tiers.getLast()
                      .level();
    }

    /// The increment is the claim on an existing block, and it only counts if it LANDED: an empty
    /// result means the record vanished between the failed claim and this call -- GC compare-and-
    /// removed it (#801) -- so the id must not be handed out and the put goes round again, where its
    /// claim now succeeds and [#afterCollection] orders its write behind GC's tier deletes.
    private Promise<BlockId> deduplicateBlock(BlockId id, byte[] content) {
        return metadataStore.computeLifecycle(id, BlockLifecycle::withRefCountIncremented)
                            .fold(() -> handlePut(id, content),
                                  lc -> Promise.success(deduplicated(lc.blockId())));
    }

    private static BlockId deduplicated(BlockId id) {
        log.debug("Block {} already stored, incremented refCount", id);

        return id;
    }

    private Promise<BlockId> writeThroughTiers(BlockId id, byte[] content) {
        return writePolicy == WritePolicy.WRITE_BEHIND
               ? writeBehindToTiers(id, content)
               : writeToAllTiers(id, content);
    }

    private Promise<BlockId> writeToAllTiers(BlockId id, byte[] content) {
        var durableTier = tiers.getLast();

        return durableTier.put(id, content)
                          .flatMap(_ -> promoteToCacheTiers(id, content, durableTier))
                          .map(_ -> trackNewBlock(id,
                                                  durableTier.level()));
    }

    private Promise<BlockId> writeBehindToTiers(BlockId id, byte[] content) {
        var fastTier = tiers.getFirst();

        return fastTier.put(id, content)
                       .flatMap(_ -> enqueueRemainingTiers(id, content, fastTier))
                       .map(_ -> trackNewBlock(id,
                                               fastTier.level()));
    }

    private Promise<Unit> enqueueRemainingTiers(BlockId id, byte[] content, StorageTier fastTier) {
        var remaining = tiers.stream().filter(t -> t != fastTier).toList();

        return writeBehindQueue.fold(() -> Promise.success(unit()),
                                     queue -> enqueueNextTier(queue, id, content, remaining, 0));
    }

    private Promise<Unit> enqueueNextTier(WriteBehindQueue queue,
                                          BlockId id,
                                          byte[] content,
                                          List<StorageTier> remaining,
                                          int index) {
        if (index >= remaining.size()) {
            return Promise.success(unit());
        }

        return queue.enqueue(id,
                             content,
                             remaining.get(index))
                    .flatMap(_ -> enqueueNextTier(queue, id, content, remaining, index + 1));
    }

    private Promise<Unit> promoteToCacheTiers(BlockId id, byte[] content, StorageTier durableTier) {
        var cacheTiers = tiers.stream().filter(t -> t != durableTier).toList();

        if (cacheTiers.isEmpty()) {
            return Promise.success(unit());
        }

        return promoteToNextCacheTier(id, content, cacheTiers, 0);
    }

    private Promise<Unit> promoteToNextCacheTier(BlockId id, byte[] content, List<StorageTier> cacheTiers, int index) {
        if (index >= cacheTiers.size()) {
            return Promise.success(unit());
        }

        var tier = cacheTiers.get(index);
        // The durable write has already succeeded by the time a cache tier is asked; a cache-tier
        // failure is recovered, not propagated, or the caller is told its durably stored data
        // failed (#910). The old chain logged "skipped" and then flatMapped the failure through.
        return tier.put(id, content)
                   .onSuccess(_ -> recordTierPresence(id,
                                                      tier.level()))
                   .fold(result -> result.fold(cause -> discardFailedPromotion(tier, id, cause),
                                               Promise::success))
                   .flatMap(_ -> promoteToNextCacheTier(id, content, cacheTiers, index + 1));
    }

    /// A cache tier that failed MID-WRITE can hold a truncated copy, and the read waterfall stops
    /// at the first tier that returns bytes — the corrupt copy would then fail every read with
    /// `IntegrityError` while the durable copy sits unreachable behind it (review of #1095, B-1,
    /// reproduced on a real `LocalDiskTier` under ENOSPC). So the failed promotion is followed by a
    /// best-effort `delete` on that tier, itself absorbed, before the chain moves on. A tier that
    /// refused BEFORE writing (`TierFull`) wrote nothing to discard, and the id may already hold a
    /// valid copy there from an earlier promotion — deleting it would evict a good cache entry on
    /// every re-promotion against a full tier (r3, a).
    private Promise<Unit> discardFailedPromotion(StorageTier tier, BlockId id, Cause cause) {
        logPromotionFailure(tier, id, cause);
        if (cause instanceof StorageError.TierFull) {
            return Promise.success(unit());
        }

        return tier.delete(id)
                   .recover(deleteCause -> discardFailed(tier, id, deleteCause));
    }

    private static Unit discardFailed(StorageTier tier, BlockId id, Cause cause) {
        log.warn("Cache tier {} could not discard the failed write of {}; a partial copy may remain and reads of it will fail their integrity check: {}",
                 tier.level(),
                 id,
                 cause.message());

        return unit();
    }

    /// `TierFull` is steady state on a hot tier and logs at DEBUG (a per-put WARN there is the
    /// #718 flood). Any OTHER cause means the tier is not working: the FIRST such failure per
    /// tier logs at WARN, then every `PROMOTION_FAILURE_WARN_EVERY`th with the running count, the
    /// rest at DEBUG — a dead cache tier is visible at INFO without flooding it (review of #1095,
    /// SF-1).
    private void logPromotionFailure(StorageTier tier, BlockId id, Cause cause) {
        if (cause instanceof StorageError.TierFull) {
            log.debug("Cache promotion to {} skipped for {}: tier full ({})", tier.level(), id, cause.message());

            return;
        }

        var failures = promotionFailures.computeIfAbsent(tier.level(), _ -> new AtomicLong()).incrementAndGet();

        if (failures == 1 || failures % PROMOTION_FAILURE_WARN_EVERY == 0) {
            log.warn("Cache promotion to {} FAILED for {} (failure #{} on this tier; the block is durable and reads fall through to the durable tier; further failures at DEBUG, next WARN at #{}): {}",
                     tier.level(),
                     id,
                     failures,
                     failures + PROMOTION_FAILURE_WARN_EVERY - failures % PROMOTION_FAILURE_WARN_EVERY,
                     cause.message());
        } else {
            log.debug("Cache promotion to {} failed for {} (failure #{}): {}",
                      tier.level(),
                      id,
                      failures,
                      cause.message());
        }
    }

    /// Finalizes the record that [#handlePut]'s claim already created -- an UPDATE, never a re-create.
    /// On the write-through path the cache-tier promotions have by now accumulated their presence
    /// onto that record via [#recordTierPresence]; the previous unconditional `createLifecycle`
    /// (a plain `put`) overwrote it with a durable-only record, so a block that was written and
    /// never read sat physically in the memory tier while `listBlocksByTier(MEMORY)` could not
    /// see it, and demotion was blind to it however far over its watermark the tier was (#886).
    /// The add is idempotent by construction (the claim names the same tier); the fallback keeps
    /// the pre-existing post-condition that a successful put always leaves a record, for the one
    /// case where the claim can vanish mid-write (a metadata restore that clears the map).
    private BlockId trackNewBlock(BlockId id, TierLevel initialTier) {
        metadataStore.computeLifecycle(id,
                                       lc -> lc.withTierAdded(initialTier))
                     .onEmpty(() -> metadataStore.createLifecycle(BlockLifecycle.blockLifecycle(id, initialTier)));
        log.debug("Block {} stored in tier {}", id, initialTier);

        return id;
    }

    // --- Read flow ---
    private Promise<Option<byte[]>> waterfallRead(BlockId id) {
        return waterfallReadFromTier(id, 0);
    }

    private Promise<Option<byte[]>> waterfallReadFromTier(BlockId id, int tierIndex) {
        if (tierIndex >= tiers.size()) {
            return Promise.success(none());
        }

        var tier = tiers.get(tierIndex);

        return tier.get(id)
                   .flatMap(opt -> handleTierReadResult(opt, id, tierIndex, tier));
    }

    private Promise<Option<byte[]>> handleTierReadResult(Option<byte[]> opt,
                                                         BlockId id,
                                                         int tierIndex,
                                                         StorageTier tier) {
        return opt.fold(() -> waterfallReadFromTier(id, tierIndex + 1),
                        content -> verifyAndReturn(id, content, tier));
    }

    private Promise<Option<byte[]>> verifyAndReturn(BlockId id, byte[] content, StorageTier tier) {
        return BlockId.blockId(content)
                      .async()
                      .flatMap(computedId -> completeVerification(computedId, id, content, tier));
    }

    private Promise<Option<byte[]>> completeVerification(BlockId computedId,
                                                         BlockId expectedId,
                                                         byte[] content,
                                                         StorageTier tier) {
        if (!computedId.equals(expectedId)) {
            log.warn("Integrity check failed in tier {} for block {}", tier.level(), expectedId);

            return StorageError.IntegrityError.integrityError(expectedId, computedId).promise();
        }

        recordTierPresence(expectedId, tier.level());

        return Promise.success(some(content));
    }

    // --- Existence check ---
    private Promise<Boolean> checkTiersForExistence(BlockId id, int tierIndex) {
        if (tierIndex >= tiers.size()) {
            return Promise.success(false);
        }

        return tiers.get(tierIndex)
                    .exists(id)
                    .flatMap(found -> found
                                      ? Promise.success(true)
                                      : checkTiersForExistence(id, tierIndex + 1));
    }

    // --- Lifecycle helpers ---
    private void recordAccess(BlockId id) {
        metadataStore.computeLifecycle(id, BlockLifecycle::withAccessTimestamp);
    }

    private void recordTierPresence(BlockId id, TierLevel tier) {
        metadataStore.computeLifecycle(id, lc -> lc.withTierAdded(tier));
    }

    private static TierInfo toTierInfo(StorageTier tier) {
        return TierInfo.tierInfo(tier.level(), tier.usedBytes(), tier.maxBytes());
    }

    // --- Delete flow ---
    private Promise<Unit> deleteFromAllTiers(BlockId id, int tierIndex) {
        if (tierIndex >= tiers.size()) {
            return Promise.success(unit());
        }

        return tiers.get(tierIndex)
                    .delete(id)
                    .flatMap(_ -> deleteFromAllTiers(id, tierIndex + 1));
    }

    private Promise<Unit> deleteFromPrivateTiers(BlockId id, int tierIndex) {
        if (tierIndex >= tiers.size()) {
            return Promise.success(unit());
        }

        var tier = tiers.get(tierIndex);

        return (tier.isShared()
                ? Promise.<Unit> success(unit())
                : tier.delete(id)).flatMap(_ -> deleteFromPrivateTiers(id, tierIndex + 1));
    }

    /// #250 review: `delete` and `deleteFromPrivateTiers` diverge in what actually happened to the
    /// shared (DHT) tier -- one message for both hid that a "private tiers" deletion leaves the
    /// cluster-shared copy alive, which reads as data loss it is not.
    private void removeLifecycleMetadata(BlockId id, String outcome) {
        metadataStore.removeLifecycle(id);
        log.debug("Block {} {}", id, outcome);
    }
}
