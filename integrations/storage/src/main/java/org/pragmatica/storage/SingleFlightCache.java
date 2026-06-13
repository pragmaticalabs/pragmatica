package org.pragmatica.storage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Deduplicates concurrent reads to the same block.
/// If a read is already in progress for a given BlockId,
/// subsequent readers share the same Promise instead of issuing duplicate fetches.
///
/// In-flight entries are BOUNDED: the deduplicated promise carries an aggregate timeout, so a
/// loader that never resolves (e.g. a durable tier whose underlying transport silently drops the
/// read) is forcibly resolved with `CoreError.Timeout` after `inFlightBound` and evicted via the
/// `onResultRun` cleanup. Without this bound a single hung loader PERMANENTLY POISONS its BlockId:
/// every subsequent caller joins the dead promise (the entry is only removed `onResultRun`, which
/// for a never-resolving loader never fires). Healthy loads resolve well within the bound, so
/// dedup semantics for concurrent callers are unchanged — joiners still share the one in-flight
/// promise.
public final class SingleFlightCache {
    /// Default upper bound on how long a single in-flight load may remain unresolved before it
    /// is evicted. Generous relative to the artifact-store resolve budget (the durable tier's
    /// own retry/timeouts fire well before this), so it only catches the pathological
    /// never-resolving loader, not slow-but-healthy reads.
    private static final TimeSpan DEFAULT_IN_FLIGHT_BOUND = timeSpan(150).seconds();

    private final ConcurrentHashMap<BlockId, Promise<Option<byte[]>>> inFlight = new ConcurrentHashMap<>();
    private final TimeSpan inFlightBound;

    private SingleFlightCache(TimeSpan inFlightBound) {
        this.inFlightBound = inFlightBound;
    }

    public static SingleFlightCache singleFlightCache() {
        return new SingleFlightCache(DEFAULT_IN_FLIGHT_BOUND);
    }

    /// Variant with an explicit in-flight bound. Used by tests that drive the hung-loader
    /// eviction with a short `TimeSpan`.
    public static SingleFlightCache singleFlightCache(TimeSpan inFlightBound) {
        return new SingleFlightCache(inFlightBound);
    }

    /// Execute the loader only if no read is in flight for this block.
    /// Returns the shared Promise for all concurrent callers.
    /// Uses computeIfAbsent for atomic single-flight guarantee.
    /// Cleanup registration happens outside the map operation to avoid
    /// recursive ConcurrentHashMap updates when promises resolve synchronously.
    public Promise<Option<byte[]>> deduplicate(BlockId id, Supplier<Promise<Option<byte[]>>> loader) {
        boolean[] created = {false};
        var promise = inFlight.computeIfAbsent(id, _ -> boundedLoad(loader, created));

        if (created[0]) {
            promise.onResultRun(() -> inFlight.remove(id));
        }

        return promise;
    }

    private Promise<Option<byte[]>> boundedLoad(Supplier<Promise<Option<byte[]>>> loader, boolean[] created) {
        created[0] = true;

        return loader.get().timeout(inFlightBound);
    }
}
