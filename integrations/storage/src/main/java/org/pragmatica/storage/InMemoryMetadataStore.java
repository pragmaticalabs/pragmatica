package org.pragmatica.storage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;


/// In-memory implementation of MetadataStore backed by ConcurrentHashMaps.
/// Suitable for testing and single-node deployments.
final class InMemoryMetadataStore implements MetadataStore {
    private final String instanceName;
    private final ConcurrentHashMap<BlockId, BlockLifecycle> lifecycle;
    private final ConcurrentHashMap<String, BlockId> refs = new ConcurrentHashMap<>();
    private final Set<BlockId> unacknowledged = ConcurrentHashMap.newKeySet();
    private final AtomicLong epoch = new AtomicLong();
    private final AccessTracker accessTracker;

    private InMemoryMetadataStore(String instanceName,
                                  AccessTracker accessTracker,
                                  ConcurrentHashMap<BlockId, BlockLifecycle> lifecycle) {
        this.instanceName = instanceName;
        this.accessTracker = accessTracker;
        this.lifecycle = lifecycle;
    }

    static InMemoryMetadataStore inMemoryMetadataStore(String instanceName) {
        return inMemoryMetadataStore(instanceName, AccessTracker.accessTracker());
    }

    static InMemoryMetadataStore inMemoryMetadataStore(String instanceName, AccessTracker accessTracker) {
        return inMemoryMetadataStore(instanceName, accessTracker, new ConcurrentHashMap<>());
    }

    /// The record map is supplied so a test can observe the instant right after a record is inserted.
    static InMemoryMetadataStore inMemoryMetadataStore(String instanceName,
                                                       AccessTracker accessTracker,
                                                       ConcurrentHashMap<BlockId, BlockLifecycle> lifecycle) {
        return new InMemoryMetadataStore(instanceName, accessTracker, lifecycle);
    }

    @Override
    public AccessTracker accessTracker() {
        return accessTracker;
    }

    @Override
    public Option<BlockLifecycle> getLifecycle(BlockId blockId) {
        return option(lifecycle.get(blockId));
    }

    @Override
    @Contract
    public void createLifecycle(BlockLifecycle blockLifecycle) {
        lifecycle.put(blockLifecycle.blockId(), blockLifecycle);
        epoch.incrementAndGet();
    }

    @Override
    public boolean claimBlock(BlockId blockId, BlockLifecycle sentinel) {
        var claimed = lifecycle.putIfAbsent(blockId, sentinel) == null || replaceCacheMarker(blockId, sentinel);

        if (claimed && !sentinel.cacheOnly()) {
            epoch.incrementAndGet();
        }

        return claimed;
    }

    /// The id is marked BEFORE the record exists, so a capture can never list the record unmarked. A claim
    /// that loses unmarks only what it marked: the record it lost to belongs to someone else. While it holds
    /// its mark, a loser can keep an acknowledged record out of ONE concurrent capture; that is no new
    /// exposure, since any record newer than the last snapshot is absent from it until the next one.
    @Override
    public boolean claimUnacknowledged(BlockId blockId, BlockLifecycle sentinel) {
        var marked = unacknowledged.add(blockId);
        var claimed = claimBlock(blockId, sentinel);

        if (!claimed && marked) {
            unacknowledged.remove(blockId);
        }

        return claimed;
    }

    @Override
    @Contract
    public void acknowledgeClaim(BlockId blockId) {
        if (unacknowledged.remove(blockId)) {
            epoch.incrementAndGet();
        }
    }

    @Override
    public boolean isUnacknowledged(BlockId blockId) {
        return unacknowledged.contains(blockId);
    }

    /// A claim for a real block replaces a cache-only marker (atomically, against the marker as read: a
    /// read that touched it since loses the compare and the claimant sees an owned or fresh record next time
    /// round). A claim for a marker never does.
    private boolean replaceCacheMarker(BlockId blockId, BlockLifecycle sentinel) {
        var existing = lifecycle.get(blockId);

        return ! sentinel.cacheOnly()
               && existing != null
               && existing.cacheOnly()
               && lifecycle.replace(blockId, existing, sentinel.withPresenceOf(existing));
    }

    @Override
    public boolean releaseClaim(BlockId blockId, BlockLifecycle sentinel) {
        var released = lifecycle.remove(blockId, sentinel);

        if (released) {
            unacknowledged.remove(blockId);
            accessTracker.forget(blockId);
        }

        if (released && !sentinel.cacheOnly()) {
            epoch.incrementAndGet();
        }

        return released;
    }

    @Override
    public Option<BlockLifecycle> computeLifecycle(BlockId blockId, UnaryOperator<BlockLifecycle> updater) {
        var result = option(lifecycle.computeIfPresent(blockId, (_, lc) -> updater.apply(lc)));

        result.onPresent(_ -> epoch.incrementAndGet());

        return result;
    }

    @Override
    public Option<BlockLifecycle> observeLifecycle(BlockId blockId, UnaryOperator<BlockLifecycle> updater) {
        return option(lifecycle.computeIfPresent(blockId, (_, lc) -> updater.apply(lc)));
    }

    @Override
    @Contract
    public void removeLifecycle(BlockId blockId) {
        lifecycle.remove(blockId);
        unacknowledged.remove(blockId);
        accessTracker.forget(blockId);
        epoch.incrementAndGet();
    }

    @Override
    @Contract
    public void putRef(String refName, BlockId blockId) {
        refs.put(refName, blockId);
        epoch.incrementAndGet();
    }

    @Override
    public Option<BlockId> resolveRef(String refName) {
        return option(refs.get(refName));
    }

    @Override
    public Option<BlockId> removeRef(String refName) {
        var result = option(refs.remove(refName));

        result.onPresent(_ -> epoch.incrementAndGet());

        return result;
    }

    @Override
    public Option<BlockId> replaceRef(String refName, BlockId blockId) {
        var result = option(refs.put(refName, blockId));

        epoch.incrementAndGet();

        return result;
    }

    @Override
    public boolean containsBlock(BlockId blockId) {
        return lifecycle.containsKey(blockId);
    }

    @Override
    public String instanceName() {
        return instanceName;
    }

    @Override
    public List<BlockLifecycle> listBlocksByTier(TierLevel tier) {
        return lifecycle.values()
                        .stream()
                        .filter(lc -> lc.presentIn()
                                        .contains(tier))
                        .toList();
    }

    @Override
    public List<BlockLifecycle> listAllLifecycles() {
        return lifecycle.values()
                        .stream()
                        .filter(lc -> !unacknowledged.contains(lc.blockId()))
                        .toList();
    }

    @Override
    public Map<String, BlockId> listAllRefs() {
        return Map.copyOf(refs);
    }

    @Override
    public long currentEpoch() {
        return epoch.get();
    }

    @Override
    @Contract
    public void restoreLifecycles(List<BlockLifecycle> entries) {
        lifecycle.clear();
        unacknowledged.clear();
        entries.forEach(lc -> lifecycle.put(lc.blockId(), lc));
        epoch.incrementAndGet();
    }

    @Override
    @Contract
    public void restoreRefs(Map<String, BlockId> newRefs) {
        refs.clear();
        refs.putAll(newRefs);
        epoch.incrementAndGet();
    }

    @Override
    @Contract
    public void restoreEpoch(long snapshotEpoch) {
        epoch.updateAndGet(current -> Math.max(current, snapshotEpoch));
    }
}
