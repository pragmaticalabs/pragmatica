package org.pragmatica.aether.storage;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/// In-memory storage tier using ConcurrentHashMap.
/// Thread-safe, bounded by configurable max bytes.
public final class MemoryTier implements StorageTier {
    private final ConcurrentHashMap<BlockId, byte[]> store = new ConcurrentHashMap<>();
    private final AtomicLong usedBytes = new AtomicLong(0);
    private final long maxBytes;

    private MemoryTier(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public static MemoryTier memoryTier(long maxBytes) {
        return new MemoryTier(maxBytes);
    }

    @Override
    public Promise<Option<byte[]>> get(BlockId id) {
        return Promise.success(option(store.get(id)));
    }

    @Override
    public Promise<Unit> put(BlockId id, byte[] content) {
        if (usedBytes.get() + content.length > maxBytes) {
            return new StorageError.TierFull(TierLevel.MEMORY, usedBytes.get(), maxBytes).promise();
        }

        adjustUsedBytes(store.put(id, content), content.length);
        return Promise.success(unit());
    }

    @Override
    public Promise<Unit> delete(BlockId id) {
        adjustUsedBytesOnRemoval(store.remove(id));
        return Promise.success(unit());
    }

    @Override
    public Promise<Boolean> exists(BlockId id) {
        return Promise.success(store.containsKey(id));
    }

    @Override
    public TierLevel level() {
        return TierLevel.MEMORY;
    }

    @Override
    public long usedBytes() {
        return usedBytes.get();
    }

    @Override
    public long maxBytes() {
        return maxBytes;
    }

    private void adjustUsedBytes(byte[] previous, int newSize) {
        var delta = previous != null
                    ? newSize - previous.length
                    : newSize;
        usedBytes.addAndGet(delta);
    }

    private void adjustUsedBytesOnRemoval(byte[] removed) {
        if (removed != null) {
            usedBytes.addAndGet(-removed.length);
        }
    }
}
