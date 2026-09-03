package org.pragmatica.storage;

import java.util.List;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
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
    /// Create a named reference to a block.
    Promise<Unit> createRef(String name, BlockId id);
    /// Resolve a named reference to its block ID.
    Option<BlockId> resolveRef(String name);
    /// Delete a named reference.
    Promise<Unit> deleteRef(String name);

    /// Replaces what `name` points to: writes (or deduplicates) the block for `content`, then points
    /// `name` at it. Never leaves `name` absent -- unlike `deleteRef` then `createRef`, it resolves to
    /// the old target or the new one at every instant. [DefaultStorageInstance]'s composite: the
    /// metadata pointer swap itself is atomic, but the new block's credit and the displaced block's
    /// decrement are only ordered, not atomic together -- a crash between them over-counts the
    /// displaced block (#737).
    ///
    /// Default falls back to [#put] + [#createRef], which has two independent counting defects: it
    /// never decrements the superseded block (leaks its refcount forever -- only
    /// [DefaultStorageInstance] reclaims it), AND it double-counts the NEW block -- [#put] already
    /// credits it (fresh write or dedup, +1), then [#createRef] credits it again (+1), so it sits at
    /// refCount 2 for one logical reference. Even an explicit [#deleteRef] afterward only brings it to
    /// 1; it never reaches zero and is never GC-eligible.
    default Promise<BlockId> replaceRef(String name, byte[] content) {
        return put(content).flatMap(id -> createRef(name, id).map(_ -> id));
    }

    /// Delete a block from all tiers and remove its lifecycle metadata. Used by GC.
    Promise<Unit> delete(BlockId id);

    /// Delete a block from node-private tiers only, then remove its lifecycle metadata.
    /// A tier reporting [StorageTier#isShared] is skipped -- this node's local refcount
    /// belief is not authoritative for a cluster-shared tier, so orphan-driven garbage
    /// collection must never issue a delete against it. Used by [StorageGarbageCollector];
    /// callers that legitimately need "delete everywhere" (explicit content/manifest
    /// deletion, stream retention) must keep using [#delete].
    ///
    /// Default falls back to [#delete] -- correct for any implementation with no
    /// tier-sharing concept (e.g. test doubles). Only [DefaultStorageInstance], where the
    /// real hazard exists, overrides this with tier-filtered deletion.
    default Promise<Unit> deleteFromPrivateTiers(BlockId id) {
        return delete(id);
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

    private final String name;
    private final List<StorageTier> tiers;
    private final MetadataStore metadataStore;
    private final WritePolicy writePolicy;
    private final Option<WriteBehindQueue> writeBehindQueue;
    private final SingleFlightCache readCache = SingleFlightCache.singleFlightCache();

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
    public Promise<BlockId> replaceRef(String refName, byte[] content) {
        return BlockId.blockId(content)
                      .async()
                      .flatMap(id -> handlePut(id, content))
                      .map(id -> repointRef(refName, id));
    }

    @Override
    public Promise<Unit> delete(BlockId id) {
        return deleteFromAllTiers(id, 0).onSuccess(_ -> removeLifecycleMetadata(id, "deleted from all tiers"));
    }

    @Override
    public Promise<Unit> deleteFromPrivateTiers(BlockId id) {
        return deleteFromPrivateTiers(id, 0).onSuccess(_ -> removeLifecycleMetadata(id,
                                                                                    "deleted from private tiers; shared copy retained"));
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
               ? writeThroughTiers(id, content).onFailure(_ -> metadataStore.releaseClaim(id, sentinel))
               : deduplicateBlock(id);
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

    private BlockLifecycle sentinelFor(BlockId id) {
        return BlockLifecycle.blockLifecycle(id,
                                             tiers.getLast().level());
    }

    private Promise<BlockId> deduplicateBlock(BlockId id) {
        metadataStore.computeLifecycle(id, BlockLifecycle::withRefCountIncremented);
        log.debug("Block {} already stored, incremented refCount", id);

        return Promise.success(id);
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

        return tier.put(id, content)
                   .onSuccess(_ -> recordTierPresence(id,
                                                      tier.level()))
                   .onFailure(cause -> log.debug("Cache promotion to {} skipped for {}: {}",
                                                 tier.level(),
                                                 id,
                                                 cause.message()))
                   .flatMap(_ -> promoteToNextCacheTier(id, content, cacheTiers, index + 1));
    }

    private BlockId trackNewBlock(BlockId id, TierLevel initialTier) {
        metadataStore.createLifecycle(BlockLifecycle.blockLifecycle(id, initialTier));
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
