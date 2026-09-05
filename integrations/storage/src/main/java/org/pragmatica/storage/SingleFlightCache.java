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
/// read) is forcibly resolved with `CoreError.Timeout` after `inFlightBound`. Without this bound a
/// single hung loader PERMANENTLY POISONS its BlockId: every subsequent caller joins the dead
/// promise forever. Eviction is enforced on the READ side (a resolved map entry is treated as
/// absent, see `deduplicate`), not only by the `onResultRun` removal — the removal itself runs
/// asynchronously off-thread, so relying on it alone would leave a window where a caller arriving
/// right after the bound fires still observes the stale settled entry. Healthy loads resolve well
/// within the bound, so dedup semantics for concurrent callers are unchanged — joiners still share
/// the one in-flight promise.
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
    /// Uses compute (not computeIfAbsent) so a RESOLVED entry is treated as absent: eviction
    /// removes a settled entry from the map asynchronously (`onResultRun` dispatches off-thread,
    /// see below), so between "bound fires" and "removal executes" the map can still hold an
    /// already-resolved promise. Without the `isResolved` check, a caller arriving in that window
    /// would inherit the stale result via computeIfAbsent instead of getting a fresh load — for a
    /// hung loader that means inheriting the OLD Timeout forever-non-deterministically, defeating
    /// the bound's own purpose. Checking resolution makes eviction race-free instead of merely
    /// narrowing the window.
    /// Cleanup registration happens outside the map operation to avoid
    /// recursive ConcurrentHashMap updates when promises resolve synchronously.
    /// Cleanup removes by (id, promise) — the atomic two-argument compare-and-remove — not by id
    /// alone. Treating a resolved entry as absent (above) means a settled promise A's map slot can
    /// legitimately be superseded by a fresh promise B for the same id BEFORE A's own asynchronous
    /// cleanup has run. A key-only `remove(id)` would delete whatever currently occupies the slot,
    /// which after supersession is B — a live, in-flight promise — breaking the single-flight
    /// guarantee itself (a third caller then starts a duplicate load C, invisibly, since every
    /// caller still resolves correctly from its own promise reference). Removing by value as well
    /// makes the cleanup a no-op once the slot no longer holds the promise that scheduled it.
    public Promise<Option<byte[]>> deduplicate(BlockId id, Supplier<Promise<Option<byte[]>>> loader) {
        boolean[] created = {false};
        var promise = inFlight.compute(id, (_, existing) ->
                existing == null || existing.isResolved() ? boundedLoad(loader, created) : existing);

        if (created[0]) {
            promise.onResultRun(() -> inFlight.remove(id, promise));
        }

        return promise;
    }

    private Promise<Option<byte[]>> boundedLoad(Supplier<Promise<Option<byte[]>>> loader, boolean[] created) {
        created[0] = true;

        return loader.get()
                     .timeout(inFlightBound);
    }
}
