package org.pragmatica.storage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// What reads did to blocks since this process booted: an in-memory, approximate counter, kept apart from the
/// block records on purpose (C-08). A read is not a metadata mutation, so no reader writes the structure a
/// snapshot captures, the snapshot's bytes depend only on writes, and demotion and promotion have exactly one
/// source for "how recently / how often was this block read".
///
/// It is never snapshotted: after a restart every block's access is UNKNOWN (no entry here, and the record
/// predates the boot). Demotion ranks unknown blocks after the ones it has seen read until the warm-up window
/// has passed, so a restart does not look like a moment when nothing was ever read and demote the working set
/// first. A tier above its high watermark still demotes them: the hold-back orders, it never blocks.
///
/// Approximate: concurrent reads of one block may be counted once, and nothing here survives the process.
public final class AccessTracker {
    /// Window after boot during which a block read-untouched since boot is demoted last, not for being cold.
    public static final long DEFAULT_WARM_UP_MS = 15 * 60_000L;

    /// The reads of one block since boot: when the last was and how many there were.
    public record Access(long lastAccessedAt, int count) {}

    private final ConcurrentHashMap<BlockId, Access> seen = new ConcurrentHashMap<>();

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final LongSupplier clock;
    private final LongSupplier monotonicNanos;
    private final long bootedAt;
    private final long bootedAtNanos;
    private final long warmUpMs;

    private AccessTracker(LongSupplier clock, LongSupplier monotonicNanos, long warmUpMs) {
        this.clock = clock;
        this.monotonicNanos = monotonicNanos;
        this.bootedAt = clock.getAsLong();
        this.bootedAtNanos = monotonicNanos.getAsLong();
        this.warmUpMs = warmUpMs;
    }

    public static AccessTracker accessTracker() {
        return accessTracker(System::currentTimeMillis, System::nanoTime);
    }

    /// The production window, on the given sources; `accessTracker()` is this with the system's.
    static AccessTracker accessTracker(LongSupplier clock, LongSupplier monotonicNanos) {
        return accessTracker(DEFAULT_WARM_UP_MS, clock, monotonicNanos);
    }

    /// The warm-up window is measured on `monotonicNanos` (a nanoTime-style source), never on the wall clock
    /// `clock`: a clock step cannot end the window early or stretch it. `clock` stamps reads and is compared
    /// with block creation times, which are wall times.
    static AccessTracker accessTracker(long warmUpMs, LongSupplier clock, LongSupplier monotonicNanos) {
        return new AccessTracker(clock, monotonicNanos, warmUpMs);
    }

    /// Tests that drive one fake clock: the window follows it, in milliseconds.
    public static AccessTracker accessTracker(long warmUpMs, LongSupplier clock) {
        return accessTracker(warmUpMs, clock, () -> clock.getAsLong() * NANOS_PER_MILLI);
    }

    /// A read found this block.
    @Contract
    public void record(BlockId id) {
        var now = clock.getAsLong();

        seen.merge(id,
                   new Access(now, 1),
                   (old, _) -> new Access(now,
                                          old.count() == Integer.MAX_VALUE
                                          ? Integer.MAX_VALUE
                                          : old.count() + 1));
    }

    /// The block is gone; its reads are no longer of interest.
    @Contract
    public void forget(BlockId id) {
        seen.remove(id);
    }

    public Option<Access> of(BlockId id) {
        return Option.option(seen.get(id));
    }

    /// No read since boot, and the record predates the boot: nothing in this process says how hot the block is.
    public boolean isUnknown(BlockLifecycle lifecycle) {
        return ! seen.containsKey(lifecycle.blockId()) && lifecycle.createdAt() < bootedAt;
    }

    /// Whether demotion ranks this block last now: its access is unknown and the warm-up window is open.
    public boolean isHeldBack(BlockLifecycle lifecycle) {
        return isUnknown(lifecycle) && (monotonicNanos.getAsLong() - bootedAtNanos) / NANOS_PER_MILLI < warmUpMs;
    }

    /// When the block was last read here; a block not read since boot reads as untouched since its creation. The record's
    /// own `lastAccessedAt` is frozen at creation and is no input.
    public long lastAccessedAt(BlockLifecycle lifecycle) {
        return of(lifecycle.blockId()).map(Access::lastAccessedAt)
                 .or(lifecycle.createdAt());
    }

    /// How many times the block was read here since boot. The record's own `accessCount` is frozen at creation and is no input.
    public int accessCount(BlockLifecycle lifecycle) {
        return of(lifecycle.blockId()).map(Access::count)
                 .or(0);
    }
}
