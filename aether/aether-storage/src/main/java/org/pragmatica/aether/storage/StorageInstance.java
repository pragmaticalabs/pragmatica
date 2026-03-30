package org.pragmatica.aether.storage;

import java.util.List;

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

    /// Instance name.
    String name();

    /// Tier utilization info.
    List<TierInfo> tierInfo();

    record TierInfo(TierLevel level, long usedBytes, long maxBytes) {}

    /// Create a storage instance with an in-memory metadata store.
    static StorageInstance storageInstance(String name, List<StorageTier> tiers) {
        return storageInstance(name, tiers, InMemoryMetadataStore.inMemoryMetadataStore(name));
    }

    /// Create a storage instance with a custom metadata store.
    static StorageInstance storageInstance(String name, List<StorageTier> tiers, MetadataStore metadataStore) {
        return new DefaultStorageInstance(name, tiers, metadataStore);
    }
}

final class DefaultStorageInstance implements StorageInstance {
    private static final Logger log = LoggerFactory.getLogger(DefaultStorageInstance.class);

    private final String name;
    private final List<StorageTier> tiers;
    private final MetadataStore metadataStore;
    private final SingleFlightCache readCache = SingleFlightCache.singleFlightCache();

    DefaultStorageInstance(String name, List<StorageTier> tiers, MetadataStore metadataStore) {
        this.name = name;
        this.tiers = List.copyOf(tiers);
        this.metadataStore = metadataStore;
        log.info("Storage instance '{}' created with {} tier(s)", name, tiers.size());
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
        return readCache.deduplicate(id, () -> waterfallRead(id))
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
        var sentinel = BlockLifecycle.blockLifecycle(id, tiers.getLast().level());

        if (!metadataStore.claimBlock(id, sentinel)) {
            return deduplicateBlock(id);
        }

        return writeThroughTiers(id, content);
    }

    private Promise<BlockId> deduplicateBlock(BlockId id) {
        metadataStore.computeLifecycle(id, BlockLifecycle::withRefCountIncremented);
        log.debug("Block {} already stored, incremented refCount", id);
        return Promise.success(id);
    }

    private Promise<BlockId> writeThroughTiers(BlockId id, byte[] content) {
        var durableTier = tiers.getLast();

        return durableTier.put(id, content)
                          .flatMap(_ -> promoteToCacheTiers(id, content, durableTier))
                          .map(_ -> trackNewBlock(id, durableTier.level()));
    }

    private Promise<Unit> promoteToCacheTiers(BlockId id, byte[] content, StorageTier durableTier) {
        var cacheTiers = tiers.stream()
                              .filter(t -> t != durableTier)
                              .toList();

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
                   .onSuccess(_ -> recordTierPresence(id, tier.level()))
                   .onFailure(cause -> log.debug("Cache promotion to {} skipped for {}: {}", tier.level(), id, cause.message()))
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

    private Promise<Option<byte[]>> handleTierReadResult(Option<byte[]> opt, BlockId id, int tierIndex, StorageTier tier) {
        return opt.fold(() -> waterfallReadFromTier(id, tierIndex + 1),
                        content -> verifyAndReturn(id, content, tier));
    }

    private Promise<Option<byte[]>> verifyAndReturn(BlockId id, byte[] content, StorageTier tier) {
        return BlockId.blockId(content)
                      .async()
                      .flatMap(computedId -> completeVerification(computedId, id, content, tier));
    }

    private Promise<Option<byte[]>> completeVerification(BlockId computedId, BlockId expectedId, byte[] content, StorageTier tier) {
        if (!computedId.equals(expectedId)) {
            log.warn("Integrity check failed in tier {} for block {}", tier.level(), expectedId);
            return new StorageError.IntegrityError(expectedId, computedId).promise();
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
                    .flatMap(found -> found ? Promise.success(true) : checkTiersForExistence(id, tierIndex + 1));
    }

    // --- Lifecycle helpers ---

    private void recordAccess(BlockId id) {
        metadataStore.computeLifecycle(id, BlockLifecycle::withAccessTimestamp);
    }

    private void recordTierPresence(BlockId id, TierLevel tier) {
        metadataStore.computeLifecycle(id, lc -> lc.withTierAdded(tier));
    }

    private static TierInfo toTierInfo(StorageTier tier) {
        return new TierInfo(tier.level(), tier.usedBytes(), tier.maxBytes());
    }
}
