package org.pragmatica.storage;

import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// A cluster-shared tier double: one instance handed to several storage instances plays the DHT, so what
/// one "node" writes the others read. Counts the calls that matter to a fetch.
final class SharedTestTier implements StorageTier {
    private final MemoryTier backing = MemoryTier.memoryTier(Long.MAX_VALUE / 4, TierLevel.REMOTE);
    private final AtomicInteger gets = new AtomicInteger();
    private final AtomicInteger deletes = new AtomicInteger();

    int gets() {
        return gets.get();
    }

    int deletes() {
        return deletes.get();
    }

    @Override
    public Promise<Option<byte[]>> get(BlockId id) {
        gets.incrementAndGet();

        return backing.get(id);
    }

    @Override
    public Promise<Unit> put(BlockId id, byte[] content) {
        return backing.put(id, content);
    }

    @Override
    public Promise<Unit> delete(BlockId id) {
        deletes.incrementAndGet();

        return backing.delete(id);
    }

    @Override
    public Promise<Boolean> exists(BlockId id) {
        return backing.exists(id);
    }

    @Override
    public TierLevel level() {
        return TierLevel.REMOTE;
    }

    @Override
    public long usedBytes() {
        return backing.usedBytes();
    }

    @Override
    public long maxBytes() {
        return backing.maxBytes();
    }

    @Override
    public boolean isShared() {
        return true;
    }
}
