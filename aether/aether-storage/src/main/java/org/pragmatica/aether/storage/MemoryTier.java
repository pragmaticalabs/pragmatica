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
        // Atomic capacity reservation using CAS loop to prevent TOCTOU race.
        long current;
        long updated;

        do {
            current = usedBytes.get();
            // For overwrites, we don't know old size yet — reserve full content.length.
            // Overcount is corrected after the actual put.
            updated = current + content.length;

            if (updated > maxBytes) {
                return new StorageError.TierFull(TierLevel.MEMORY, current, maxBytes).promise();
            }
        } while (!usedBytes.compareAndSet(current, updated));

        // Now we have reserved space atomically. Perform the actual put.
        var previous = store.put(id, content);

        // If overwrite, return the over-reserved bytes for the old value.
        if (previous != null) {
            usedBytes.addAndGet(-previous.length);
        }

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

    private void adjustUsedBytesOnRemoval(byte[] removed) {
        if (removed != null) {
            usedBytes.addAndGet(-removed.length);
        }
    }
}
