package org.pragmatica.aether.storage;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StorageBlockKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StorageRefKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StorageBlockValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StorageRefValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static org.pragmatica.aether.slice.kvstore.AetherKey.StorageBlockKey.storageBlockKey;
import static org.pragmatica.aether.slice.kvstore.AetherKey.StorageRefKey.storageRefKey;
import static org.pragmatica.aether.slice.kvstore.AetherValue.StorageBlockValue.storageBlockValue;
import static org.pragmatica.aether.slice.kvstore.AetherValue.StorageRefValue.storageRefValue;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// KV-Store backed implementation of MetadataStore.
/// Uses consensus KV-Store for cluster-wide block lifecycle and reference metadata.
/// Local ConcurrentHashMap provides atomic claim semantics for write deduplication.
final class KVStoreMetadataStore implements MetadataStore {
    private final String instanceName;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final ConcurrentHashMap<String, Boolean> claimedBlocks = new ConcurrentHashMap<>();
    private final AtomicLong epoch = new AtomicLong();

    private KVStoreMetadataStore(String instanceName, KVStore<AetherKey, AetherValue> kvStore) {
        this.instanceName = instanceName;
        this.kvStore = kvStore;
    }

    static KVStoreMetadataStore kvStoreMetadataStore(String instanceName, KVStore<AetherKey, AetherValue> kvStore) {
        return new KVStoreMetadataStore(instanceName, kvStore);
    }

    @Override
    public Option<BlockLifecycle> getLifecycle(BlockId blockId) {
        return kvStore.get(blockKey(blockId))
                      .flatMap(value -> toBlockValue(value).map(bv -> toDomain(blockId, bv)));
    }

    @Override
    public void createLifecycle(BlockLifecycle lifecycle) {
        kvStore.process(new KVCommand.Put<>(blockKey(lifecycle.blockId()), toStorageValue(lifecycle)));
        epoch.incrementAndGet();
    }

    @Override
    public boolean claimBlock(BlockId blockId, BlockLifecycle sentinel) {
        var hex = blockId.hexString();

        if (claimedBlocks.putIfAbsent(hex, Boolean.TRUE) != null) {
            return false;
        }

        kvStore.process(new KVCommand.Put<>(blockKey(blockId), toStorageValue(sentinel)));
        epoch.incrementAndGet();
        return true;
    }

    @Override
    public Option<BlockLifecycle> computeLifecycle(BlockId blockId, UnaryOperator<BlockLifecycle> updater) {
        return getLifecycle(blockId)
            .map(updater::apply)
            .onPresent(this::createLifecycle);
    }

    @Override
    public void removeLifecycle(BlockId blockId) {
        claimedBlocks.remove(blockId.hexString());
        kvStore.process(new KVCommand.Remove<>(blockKey(blockId)));
        epoch.incrementAndGet();
    }

    @Override
    public void putRef(String refName, BlockId blockId) {
        kvStore.process(new KVCommand.Put<>(refKey(refName), storageRefValue(blockId.hexString())));
        epoch.incrementAndGet();
    }

    @Override
    public Option<BlockId> resolveRef(String refName) {
        return kvStore.get(refKey(refName))
                      .flatMap(KVStoreMetadataStore::toRefValue)
                      .flatMap(rv -> BlockId.fromHex(rv.blockIdHex()).option());
    }

    @Override
    public Option<BlockId> removeRef(String refName) {
        var previous = resolveRef(refName);
        kvStore.process(new KVCommand.Remove<>(refKey(refName)));
        epoch.incrementAndGet();
        return previous;
    }

    @Override
    public boolean containsBlock(BlockId blockId) {
        return kvStore.get(blockKey(blockId))
                      .filter(StorageBlockValue.class::isInstance)
                      .isPresent();
    }

    @Override
    public String instanceName() {
        return instanceName;
    }

    @Override
    public List<BlockLifecycle> listAllLifecycles() {
        var result = new ArrayList<BlockLifecycle>();
        kvStore.forEach(StorageBlockKey.class, StorageBlockValue.class, (key, value) -> collectLifecycle(key, value, result));
        return List.copyOf(result);
    }

    @Override
    public Map<String, BlockId> listAllRefs() {
        var result = new HashMap<String, BlockId>();
        kvStore.forEach(StorageRefKey.class, StorageRefValue.class, (key, value) -> collectRef(key, value, result));
        return Map.copyOf(result);
    }

    @Override
    public long currentEpoch() {
        return epoch.get();
    }

    @Override
    public void restoreLifecycles(List<BlockLifecycle> entries) {
        entries.forEach(this::createLifecycle);
    }

    @Override
    public void restoreRefs(Map<String, BlockId> refs) {
        refs.forEach(this::putRef);
    }

    private void collectLifecycle(StorageBlockKey key, StorageBlockValue value, List<BlockLifecycle> target) {
        if (key.instanceName().equals(instanceName)) {
            BlockId.fromHex(key.blockIdHex())
                   .onSuccess(id -> target.add(toDomain(id, value)));
        }
    }

    private void collectRef(StorageRefKey key, StorageRefValue value, Map<String, BlockId> target) {
        if (key.instanceName().equals(instanceName)) {
            BlockId.fromHex(value.blockIdHex())
                   .onSuccess(id -> target.put(key.referenceName(), id));
        }
    }

    // --- Key construction ---

    private AetherKey blockKey(BlockId blockId) {
        return storageBlockKey(instanceName, blockId.hexString());
    }

    private AetherKey refKey(String refName) {
        return storageRefKey(instanceName, refName);
    }

    // --- Domain-to-KV conversion ---

    private static StorageBlockValue toStorageValue(BlockLifecycle lifecycle) {
        var tierNames = lifecycle.presentIn().stream()
                                .map(TierLevel::name)
                                .collect(Collectors.toSet());

        return storageBlockValue(
            lifecycle.blockId().hexString(),
            tierNames,
            lifecycle.refCount(),
            lifecycle.lastAccessedAt(),
            lifecycle.createdAt()
        );
    }

    // --- KV-to-domain conversion ---

    private static BlockLifecycle toDomain(BlockId blockId, StorageBlockValue value) {
        var tiers = value.presentIn().stream()
                         .map(TierLevel::valueOf)
                         .collect(Collectors.toCollection(() -> EnumSet.noneOf(TierLevel.class)));

        return BlockLifecycle.blockLifecycle(blockId, tiers, value.refCount(), value.lastAccessedAt(), value.createdAt());
    }

    private static Option<StorageBlockValue> toBlockValue(AetherValue value) {
        return value instanceof StorageBlockValue bv ? some(bv) : none();
    }

    private static Option<StorageRefValue> toRefValue(AetherValue value) {
        return value instanceof StorageRefValue rv ? some(rv) : none();
    }
}
