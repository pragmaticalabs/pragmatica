package org.pragmatica.storage;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;


/// In-memory storage tier using ConcurrentHashMap.
/// Thread-safe, bounded by configurable max bytes. Each block keeps the time it was written or last touched, read from
/// the tier's clock ([#stat], [#touch]).
public final class MemoryTier implements StorageTier {
    private final ConcurrentHashMap<BlockId, Entry> store = new ConcurrentHashMap<>();
    private final AtomicLong usedBytes = new AtomicLong(0);
    private final long maxBytes;
    private final TierLevel level;
    private final LongSupplier clockMillis;

    private record Entry(byte[] content, long writtenAtMillis) {
        Entry touchedAt(long millis) {
            return new Entry(content, millis);
        }
    }

    private MemoryTier(long maxBytes, TierLevel level, LongSupplier clockMillis) {
        this.maxBytes = maxBytes;
        this.level = level;
        this.clockMillis = clockMillis;
    }

    public static MemoryTier memoryTier(long maxBytes) {
        return memoryTier(maxBytes, TierLevel.MEMORY);
    }

    /// Create a memory-backed tier that reports a custom tier level (useful for testing).
    public static MemoryTier memoryTier(long maxBytes, TierLevel level) {
        return memoryTier(maxBytes, level, System::currentTimeMillis);
    }

    /// As above, with the clock that stamps writes and touches (useful for testing).
    public static MemoryTier memoryTier(long maxBytes, TierLevel level, LongSupplier clockMillis) {
        return new MemoryTier(maxBytes, level, clockMillis);
    }

    @Override
    public Promise<Option<byte[]>> get(BlockId id) {
        return Promise.success(option(store.get(id)).map(Entry::content));
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
                return StorageError.TierFull.tierFull(level(), current, maxBytes).promise();
            }
        } while (!usedBytes.compareAndSet(current, updated));
        // Now we have reserved space atomically. Perform the actual put.
        option(store.put(id, new Entry(content, clockMillis.getAsLong()))).onPresent(prev -> usedBytes.addAndGet(-prev.content().length));

        return Promise.success(unit());
    }

    @Override
    public Promise<Unit> delete(BlockId id) {
        option(store.remove(id)).onPresent(removed -> usedBytes.addAndGet(-removed.content().length));

        return Promise.success(unit());
    }

    @Override
    public Promise<Boolean> exists(BlockId id) {
        return Promise.success(store.containsKey(id));
    }

    @Override
    public Promise<Option<BlockStat>> stat(BlockId id) {
        return Promise.success(option(store.get(id)).map(entry -> statOf(id, entry)));
    }

    /// Sorts the shard's blocks on every page: memory tiers are small, and a sweep pages through them rarely.
    @Override
    public Promise<Page<BlockStat>> scan(KeyShard shard, Option<Cursor> after, int limit) {
        return KeyShard.pageLimit(limit)
                       .flatMap(_ -> KeyShard.resumeAfter(after))
                       .map(resume -> KeyShard.pageOf(blocksAfter(shard, resume),
                                                      limit))
                       .async();
    }

    private List<BlockStat> blocksAfter(KeyShard shard, Option<String> resume) {
        return store.entrySet()
                    .stream()
                    .filter(entry -> shard.contains(entry.getKey()))
                    .map(entry -> statOf(entry.getKey(),
                                         entry.getValue()))
                    .filter(stat -> resume.fold(() -> true,
                                                hex -> stat.id()
                                                           .hexString()
                                                           .compareTo(hex) > 0))
                    .sorted(Comparator.comparing(stat -> stat.id()
                                                             .hexString()))
                    .toList();
    }

    @Override
    public Promise<Boolean> touch(BlockId id) {
        var now = clockMillis.getAsLong();

        return Promise.success(option(store.computeIfPresent(id, (_, entry) -> entry.touchedAt(now))).isPresent());
    }

    private static BlockStat statOf(BlockId id, Entry entry) {
        return BlockStat.blockStat(id, entry.content().length, entry.writtenAtMillis());
    }

    @Override
    public TierLevel level() {
        return level;
    }

    @Override
    public long usedBytes() {
        return usedBytes.get();
    }

    @Override
    public long maxBytes() {
        return maxBytes;
    }
}
