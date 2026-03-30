package org.pragmatica.aether.storage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;

/// In-memory implementation of MetadataStore backed by ConcurrentHashMaps.
/// Suitable for testing and single-node deployments.
final class InMemoryMetadataStore implements MetadataStore {
    private final String instanceName;
    private final ConcurrentHashMap<BlockId, BlockLifecycle> lifecycle = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BlockId> refs = new ConcurrentHashMap<>();

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
    }

    @Override
    public boolean claimBlock(BlockId blockId, BlockLifecycle sentinel) {
        return lifecycle.putIfAbsent(blockId, sentinel) == null;
    }

    @Override
    public Option<BlockLifecycle> computeLifecycle(BlockId blockId, UnaryOperator<BlockLifecycle> updater) {
        return option(lifecycle.computeIfPresent(blockId, (_, lc) -> updater.apply(lc)));
    }

    @Override
    public void removeLifecycle(BlockId blockId) {
        lifecycle.remove(blockId);
    }

    @Override
    public void putRef(String refName, BlockId blockId) {
        refs.put(refName, blockId);
    }

    @Override
    public Option<BlockId> resolveRef(String refName) {
        return option(refs.get(refName));
    }

    @Override
    public Option<BlockId> removeRef(String refName) {
        return option(refs.remove(refName));
    }

    @Override
    public boolean containsBlock(BlockId blockId) {
        return lifecycle.containsKey(blockId);
    }

    @Override
    public String instanceName() {
        return instanceName;
    }
}
