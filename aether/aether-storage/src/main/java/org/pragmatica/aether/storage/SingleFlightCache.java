package org.pragmatica.aether.storage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

/// Deduplicates concurrent reads to the same block.
/// If a read is already in progress for a given BlockId,
/// subsequent readers share the same Promise instead of issuing duplicate fetches.
public final class SingleFlightCache {
    private final ConcurrentHashMap<BlockId, Promise<Option<byte[]>>> inFlight = new ConcurrentHashMap<>();

    private SingleFlightCache() {}

    public static SingleFlightCache singleFlightCache() {
        return new SingleFlightCache();
    }

    /// Execute the loader only if no read is in flight for this block.
    /// Returns the shared Promise for all concurrent callers.
    /// Uses computeIfAbsent for atomic single-flight guarantee.
    /// Cleanup registration happens outside the map operation to avoid
    /// recursive ConcurrentHashMap updates when promises resolve synchronously.
    public Promise<Option<byte[]>> deduplicate(BlockId id, Supplier<Promise<Option<byte[]>>> loader) {
        boolean[] created = {false};
        var promise = inFlight.computeIfAbsent(id, _ -> {
            created[0] = true;
            return loader.get();
        });

        if (created[0]) {
            promise.onResultRun(() -> inFlight.remove(id));
        }

        return promise;
    }
}
