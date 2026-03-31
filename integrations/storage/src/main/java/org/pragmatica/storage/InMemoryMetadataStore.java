package org.pragmatica.storage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;

/// In-memory implementation of MetadataStore backed by ConcurrentHashMaps.
/// Suitable for testing and single-node deployments.
final class InMemoryMetadataStore implements MetadataStore {
    private final String instanceName;
    private final ConcurrentHashMap<BlockId, BlockLifecycle> lifecycle = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BlockId> refs = new ConcurrentHashMap<>();
    private final AtomicLong epoch = new AtomicLong();

    private InMemoryMetadataStore(String instanceName) {
        this.instanceName = instanceName;
    }

    static InMemoryMetadataStore inMemoryMetadataStore(String instanceName) {
        return new InMemoryMetadataStore(instanceName);
    }

    @Override
    public Option<BlockLifecycle> getLifecycle(BlockId blockId) {
        return option(lifecycle.get(blockId));
    }

    @Override
    public void createLifecycle(BlockLifecycle blockLifecycle) {
        lifecycle.put(blockLifecycle.blockId(), blockLifecycle);
        epoch.incrementAndGet();
    }

    @Override
    public boolean claimBlock(BlockId blockId, BlockLifecycle sentinel) {
        var claimed = lifecycle.putIfAbsent(blockId, sentinel) == null;

        if (claimed) {
            epoch.incrementAndGet();
        }

        return claimed;
    }

    @Override
    public Option<BlockLifecycle> computeLifecycle(BlockId blockId, UnaryOperator<BlockLifecycle> updater) {
        var result = option(lifecycle.computeIfPresent(blockId, (_, lc) -> updater.apply(lc)));
        result.onPresent(_ -> epoch.incrementAndGet());
        return result;
    }

    @Override
    public void removeLifecycle(BlockId blockId) {
        lifecycle.remove(blockId);
        epoch.incrementAndGet();
    }

    @Override
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
    public boolean containsBlock(BlockId blockId) {
        return lifecycle.containsKey(blockId);
    }

    @Override
    public String instanceName() {
        return instanceName;
    }

    @Override
    public List<BlockLifecycle> listBlocksByTier(TierLevel tier) {
        return lifecycle.values().stream()
                        .filter(lc -> lc.presentIn().contains(tier))
                        .toList();
    }

    @Override
    public List<BlockLifecycle> listAllLifecycles() {
        return List.copyOf(lifecycle.values());
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
    public void restoreLifecycles(List<BlockLifecycle> entries) {
        lifecycle.clear();
        entries.forEach(lc -> lifecycle.put(lc.blockId(), lc));
        epoch.incrementAndGet();
    }

    @Override
    public void restoreRefs(Map<String, BlockId> newRefs) {
        refs.clear();
        refs.putAll(newRefs);
        epoch.incrementAndGet();
    }
}
