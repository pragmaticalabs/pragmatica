// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.TierAwareRetention;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;


/// Off-heap ring of `capacity` index slots over a growable data region, on a shared arena.
///
/// Concurrency model (#1340): readers take no lock. They snapshot tail/head, copy, and validate the copy
/// against the tail AFTER copying ([#retainedAfterCopy]); the writer publishes an advanced tail with a
/// store-store fence before overwriting the freed slot ([#evictOldest]) and a new head with a release
/// fence after the slot's stores ([#updateHeaderAfterAppend]). That protocol is sound for ONE appender
/// per ring at a time: two concurrent `append`s would both read the same tail in
/// [#countEvictionsForSpace], both store `tail + 1`, lose one eviction, and land the second write on a
/// slot the tail still claims — a torn read the post-copy check cannot see. The single appender is
/// `appendLock` (#1258): every header-writing path — `append`, `appendOrdered`, `appendBatch`,
/// `appendOrderedAt`, `seedHead` and the retention sweeps via [#guardedSweep] — runs inside `synchronized (appendLock)`,
/// and `StreamPartitionManager` reaches the ring only through `appendOrdered` and `appendOrderedAt`.
public final class OffHeapRingBuffer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OffHeapRingBuffer.class);
    /// Empty-ring encoding reported when a native read is refused (#999): allocation seeds
    /// `headOffset = -1`, and a DECLARED-but-not-materialized partition already reports `(-1, -1, 0)` via
    /// `StreamPartitionManager.partitionInfoFor`. Refusing with these exact values is what lets every
    /// existing caller treat a released ring as the absent partition it has become.
    private static final long NO_OFFSET = -1L;
    private static final long NO_EVENTS = 0L;
    private static final long HEADER_HEAD_OFFSET = 0;
    private static final long HEADER_TAIL_OFFSET = 8;
    private static final long HEADER_EVENT_COUNT = 16;
    private static final long HEADER_DATA_WRITE_POS = 24;
    private static final long HEADER_DATA_SIZE = 32;
    private static final long HEADER_CAPACITY = 40;
    /// Absolute (never wrapped) data position of the tail record's first byte; advances by the evicted
    /// record's length in [#evictOldest]. `dataWritePos() - dataTailPos()` is the exact live byte count
    /// (#1340 review M-1: reconstructing it from two ring-relative positions reads an EXACTLY full ring,
    /// `headEnd == tailDataPos`, as empty).
    private static final long HEADER_DATA_TAIL_POS = 48;
    private static final long HEADER_SIZE = 64;
    private static final long INDEX_ENTRY_SIZE = 24;
    private static final long INDEX_DATA_OFFSET = 0;
    private static final long INDEX_DATA_LENGTH = 8;
    private static final long INDEX_TIMESTAMP = 16;
    /// Fixed data-segment granule. The data region grows one segment at a time toward the cap
    /// (`dataRegionSize`). The last logical segment is clamped so total allocated data never
    /// exceeds the cap. See spec §4.2.
    static final long DEFAULT_SEGMENT_BYTES = 256 * 1024L;
    /// Always-admit growth predicate (default seam — standalone buffers grow without budget gating).
    private static final LongPredicate ALWAYS_ADMIT = _ -> true;

    /// No-op release (default seam).
    private static final LongConsumer NOOP_RELEASE = _ -> {};

    /// No-op read-window probe (default seam — production never parks a reader).
    private static final Runnable NO_READ_WINDOW_PROBE = () -> {};

    /// Test-only floor-allocation fault-injection seam (bug #6 partial-construction coverage). Consulted
    /// by the GUARDED seam factory with each buffer's partition index BEFORE the native floor allocation;
    /// when it returns false the factory behaves exactly as a native floor OOM would — it closes the
    /// just-opened arena and returns `STREAM_MEMORY_EXCEEDED` — letting a multi-partition build exercise
    /// the "partition k fails, siblings closed, budget released" path deterministically (real native OOM
    /// cannot be triggered on demand). Default admits every partition. Production never touches it.
    private static volatile java.util.function.IntPredicate floorAllocAdmit = _ -> true;

    @Contract
    static void floorAllocFaultInjector(java.util.function.IntPredicate admit) {
        floorAllocAdmit = admit;
    }

    @Contract
    static void clearFloorAllocFaultInjector() {
        floorAllocAdmit = _ -> true;
    }

    private final Arena arena;
    private final MemorySegment controlSegment;
    private final List<MemorySegment> dataSegments;
    private final long capacity;
    private final long dataRegionSize;
    private final long indexStart;
    private final String streamName;
    private final int partition;
    private final EvictionListener listener;
    private final EvictionPolicy evictionPolicy;
    private final LongPredicate reserve;
    private final LongConsumer release;
    /// Live count of allocated data bytes (Σ dataSegments sizes), always <= dataRegionSize cap.
    private long allocatedDataBytes;
    /// Once a DROP_OLDEST stream is refused growth and starts wrapping within its allocated bytes,
    /// the data ring is frozen at that size: it must never grow again, otherwise an already-wrapped
    /// event (laid out under the smaller ring) would be misread under a larger modulus. This matches
    /// the spec's "behaves like a fixed ring at its current allocated size" + no-reclamation
    /// decisions (§4.2, §10).
    private boolean growthFrozen = false;
    /// Bytes accounted against the seam over this buffer's lifetime (released on close()).
    private long accountedBytes;
    private final List<LongConsumer> appendListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /// Reads refused because the arena was closed UNDER an in-flight reader (#999) — the genuine race, not
    /// the benign late arrival the `closed` fast path absorbs.
    private final AtomicLong closedUnderReader = new AtomicLong(0);
    /// Native accesses refused because index or offset arithmetic went out of bounds (#1247) — a ring
    /// defect, never the close race.
    private final AtomicLong indexCorruption = new AtomicLong(0);
    /// Test-only seam (#1253), run by [#guardedRead] between its `closed` fast-path check and the native read.
    /// Deliberately NOT volatile: it is set before any reader thread starts, and `Thread.start` publishes it.
    private Runnable readWindowProbe = NO_READ_WINDOW_PROBE;
    private volatile long lastSealedOffset = -1;
    /// Serializes every read-modify-write of the header (#1231): offset assignment (`head + 1`), the data
    /// write position, the event count and the tail. Held by the append paths, [#seedHead], the retention
    /// sweeps and [#appendOrdered]; reads stay lock-free. A monitor, not a `ReentrantLock` — the JDK 25
    /// baseline does not pin virtual threads on `synchronized` (JEP 491), and [#appendOrdered] re-enters it.
    private final Object appendLock = new Object();

    /// Sentinel for "no notification pending".
    private static final long NO_PENDING_NOTIFICATION = Long.MIN_VALUE;

    /// The highest offset whose append listeners are still to be notified, or [#NO_PENDING_NOTIFICATION].
    /// Raised (never lowered) when the VISIBLE position advances (#1235, [#advanceVisible]) — never at
    /// append — and delivered by this ring's serial notifier, never by a publisher (#1258 review B1, R2-1). Listeners learn that the ring ADVANCED TO an offset,
    /// so pending notifications coalesce into this one value: a slow listener costs O(1) state, never
    /// one entry per publish (#1258 addendum). Listeners are foreign code — the consumer runtime wakes
    /// its push consumers from them, and a handler may publish again — so they must never run while the
    /// section is held (that deadlocked cross-partition consumers and broke WAL order), nor on a
    /// publisher's thread (one publisher then ran every other publisher's listeners).
    private final AtomicLong pendingNotification = new AtomicLong(NO_PENDING_NOTIFICATION);
    /// Set while this ring's notifier runs: at most one per ring, so notified offsets only move forward.
    private final AtomicBoolean notifying = new AtomicBoolean(false);
    /// Listener invocations that threw, since the ring was built (#1258 review R3-1).
    private final AtomicLong appendListenerFailures = new AtomicLong();
    /// #1235: the three positions of a partition are the header head (APPENDED), this (DURABLE — the
    /// owner's WAL fsync, or a replica's own WAL write) and [#visibleOffset] (VISIBLE — durable AND
    /// acknowledged by the stream's min-sync peers). Both only move forward, and nothing on them takes a lock.
    private final AtomicLong durableOffset = new AtomicLong(NO_OFFSET);
    /// The highest offset a consumer may see: [#read] and [#readSlice] are bounded by it, and the append
    /// listeners are notified when it advances. A plain [#append]/[#appendBatch] advances it at once (a
    /// ring with no durability gate aliases visible to appended); [#appendOrdered] leaves it to the caller.
    private final AtomicLong visibleOffset = new AtomicLong(NO_OFFSET);

    private OffHeapRingBuffer(Arena arena,
                              MemorySegment controlSegment,
                              MemorySegment firstDataSegment,
                              long firstDataSegmentBytes,
                              long capacity,
                              long dataRegionSize,
                              String streamName,
                              int partition,
                              EvictionListener listener,
                              EvictionPolicy evictionPolicy,
                              LongPredicate reserve,
                              LongConsumer release,
                              long accountedBytes) {
        this.arena = arena;
        this.controlSegment = controlSegment;
        this.dataSegments = new ArrayList<>();
        this.dataSegments.add(firstDataSegment);
        this.allocatedDataBytes = firstDataSegmentBytes;
        this.capacity = capacity;
        this.dataRegionSize = dataRegionSize;
        this.indexStart = HEADER_SIZE;
        this.streamName = streamName;
        this.partition = partition;
        this.listener = listener;
        this.evictionPolicy = evictionPolicy;
        this.reserve = reserve;
        this.release = release;
        this.accountedBytes = accountedBytes;
    }

    public static OffHeapRingBuffer offHeapRingBuffer(long capacity, long dataRegionSize) {
        return offHeapRingBuffer("", 0, capacity, dataRegionSize, EvictionListener.NOOP, EvictionPolicy.DROP_OLDEST);
    }

    public static OffHeapRingBuffer offHeapRingBuffer(String streamName,
                                                      int partition,
                                                      long capacity,
                                                      long dataRegionSize,
                                                      EvictionListener listener) {
        return offHeapRingBuffer(streamName, partition, capacity, dataRegionSize, listener, EvictionPolicy.DROP_OLDEST);
    }

    /// Convenience (test/standalone) overload returning a raw buffer. These build tiny in-test
    /// allocations that do not gate against any budget and are not realistically exposed to native-OOM,
    /// so they construct the floor directly (the seam factory below carries the guarded `Result` path
    /// used in production). Standalone buffers grow without budget gating. See spec §4.3 / bug #6.
    public static OffHeapRingBuffer offHeapRingBuffer(String streamName,
                                                      int partition,
                                                      long capacity,
                                                      long dataRegionSize,
                                                      EvictionListener listener,
                                                      EvictionPolicy policy) {
        return buildFloor(Arena.ofShared(),
                          streamName,
                          partition,
                          capacity,
                          dataRegionSize,
                          listener,
                          policy,
                          ALWAYS_ADMIT,
                          NOOP_RELEASE);
    }

    /// Floor bytes a buffer reserves at creation: control region (header + index) plus the first
    /// data segment (`min(DEFAULT_SEGMENT_BYTES, maxBytes)`). See spec §4.0.
    public static long floorBytes(long capacity, long maxBytes) {
        return HEADER_SIZE + INDEX_ENTRY_SIZE * capacity + firstSegmentBytes(maxBytes);
    }

    /// Total bytes if the buffer grows to the retention cap: control region plus the full data
    /// region. Equals today's per-partition full allocation. See spec §4.0.
    public static long capBytes(long capacity, long maxBytes) {
        return HEADER_SIZE + INDEX_ENTRY_SIZE * capacity + maxBytes;
    }

    /// Factory with an injected growth-admission seam. `reserve` is consulted (and on success has
    /// already reserved the bytes) before each data-segment allocation; `release` returns bytes to
    /// the pool. The control region and the first data segment (the floor) are accounted against the
    /// seam at construction.
    ///
    /// The floor (`control + firstSegment`) native allocation is GUARDED (bug #6): a native
    /// `OutOfMemoryError` from `arena.allocate` CLOSES the just-opened shared arena (so the partial
    /// floor never leaks) and returns `STREAM_MEMORY_EXCEEDED` instead of escaping the `Result` chain.
    /// The caller (`StreamPartitionManager`) then releases the reserved floor budget and closes any
    /// sibling partitions built before this one. See spec §4.3.
    public static Result<OffHeapRingBuffer> offHeapRingBuffer(String streamName,
                                                              int partition,
                                                              long capacity,
                                                              long dataRegionSize,
                                                              EvictionListener listener,
                                                              EvictionPolicy policy,
                                                              LongPredicate reserve,
                                                              LongConsumer release) {
        var arena = Arena.ofShared();

        return buildFloorGuarded(arena,
                                 streamName,
                                 partition,
                                 capacity,
                                 dataRegionSize,
                                 listener,
                                 policy,
                                 reserve,
                                 release);
    }

    /// JDK boundary for floor allocation (bug #6 sibling of `allocateGuarded`) — one of the four marked
    /// `catch` sites in this file, with [#allocateGuarded], [#guardedAccess] and [#guardedRead]: the
    /// floor `arena.allocate` calls can fail with native `OutOfMemoryError`. On failure the arena is
    /// CLOSED (no leak) and a `Result` failure is returned. On success a fully-initialized buffer is
    /// handed back. See spec §4.3.
    @SuppressWarnings("JBCT-EX-03")
    private static Result<OffHeapRingBuffer> buildFloorGuarded(Arena arena,
                                                               String streamName,
                                                               int partition,
                                                               long capacity,
                                                               long dataRegionSize,
                                                               EvictionListener listener,
                                                               EvictionPolicy policy,
                                                               LongPredicate reserve,
                                                               LongConsumer release) {
        if (!floorAllocAdmit.test(partition)) {
            arena.close();

            return StreamError.General.STREAM_MEMORY_EXCEEDED.result();
        }

        try {
            return success(buildFloor(arena,
                                      streamName,
                                      partition,
                                      capacity,
                                      dataRegionSize,
                                      listener,
                                      policy,
                                      reserve,
                                      release));
        } catch (OutOfMemoryError _) {
            arena.close();

            return StreamError.General.STREAM_MEMORY_EXCEEDED.result();
        }
    }

    private static OffHeapRingBuffer buildFloor(Arena arena,
                                                String streamName,
                                                int partition,
                                                long capacity,
                                                long dataRegionSize,
                                                EvictionListener listener,
                                                EvictionPolicy policy,
                                                LongPredicate reserve,
                                                LongConsumer release) {
        var indexSize = INDEX_ENTRY_SIZE * capacity;
        var controlSize = HEADER_SIZE + indexSize;
        var firstSegmentBytes = firstSegmentBytes(dataRegionSize);
        var controlSegment = arena.allocate(controlSize, 64);
        var firstDataSegment = arena.allocate(firstSegmentBytes, 64);

        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET, -1L);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_TAIL_OFFSET, 0L);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT, 0L);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_DATA_WRITE_POS, 0L);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_DATA_TAIL_POS, 0L);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_DATA_SIZE, dataRegionSize);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_CAPACITY, capacity);

        return new OffHeapRingBuffer(arena,
                                     controlSegment,
                                     firstDataSegment,
                                     firstSegmentBytes,
                                     capacity,
                                     dataRegionSize,
                                     streamName,
                                     partition,
                                     listener,
                                     policy,
                                     reserve,
                                     release,
                                     firstSegmentBytes);
    }

    private static long firstSegmentBytes(long dataRegionSize) {
        return Math.min(DEFAULT_SEGMENT_BYTES, dataRegionSize);
    }

    /// Append and make the event visible at once — the ring has no durability gate of its own. Used by
    /// standalone rings. An owner or replica append that must first become durable goes through
    /// [#appendOrdered], and so does WAL recovery (#1387): a replayed record is durable by construction
    /// but its acks did not survive the restart.
    public Result<Long> append(byte[] payload, long timestamp) {
        return notifyingAfter(appendVisible(payload, timestamp));
    }

    /// The visible advance is queued INSIDE the section, so concurrent plain appends are notified once
    /// each, in offset order (#1258 R2-1); the notifier starts only after the section is released.
    private Result<Long> appendVisible(byte[] payload, long timestamp) {
        synchronized (appendLock) {
            return appendLocked(payload, timestamp).onSuccess(this::queueAppendedVisible);
        }
    }

    private Result<Long> appendLocked(byte[] payload, long timestamp) {
        if (closed.get()) {
            return StreamError.General.BUFFER_CLOSED.result();
        }

        if (payload.length > dataRegionSize) {
            return new StreamError.EventTooLarge(payload.length, dataRegionSize).result();
        }

        synchronized (appendLock) {
            return guardedAccess(() -> ensureGrownFor(payload.length).flatMap(_ -> appendIfFitsAllocated(payload,
                                                                                                         timestamp)));
        }
    }

    /// Append, then run `inOrder` with the assigned offset BEFORE any other append on this ring can be
    /// assigned one (#1231/#1232). This is the partition's ordered append section: whatever `inOrder` does
    /// — the WAL frame write, the replication send — happens in offset order across concurrent callers.
    /// `inOrder` must not block on I/O completion (the WAL fsync belongs outside, after this returns);
    /// every append on this partition waits for it. A failed append skips `inOrder`.
    ///
    /// The event is NOT made visible and no listener is notified for it here (#1235): the caller advances
    /// [#markDurable] and [#advanceVisible] once the event is durable and, on an owner, acknowledged by its
    /// min-sync peers. Listeners then learn the new visible high-water on this ring's serial notifier,
    /// never under the section.
    public <T> Result<T> appendOrdered(byte[] payload, long timestamp, Fn1<Result<T>, Long> inOrder) {
        synchronized (appendLock) {
            return appendLocked(payload, timestamp).flatMap(inOrder);
        }
    }

    /// Offset-addressed sibling of [#appendOrdered] (#1505): lands the event at `offset` and nowhere else,
    /// deciding against the head INSIDE the ordered section, so no other append on this ring can move the
    /// head between the check and the append. Succeeds with `offset` exactly when, on return, the ring holds
    /// this event at `offset`:
    ///   - `offset == head + 1` — appended there; `inOrder` runs with the assigned offset, as in [#appendOrdered];
    ///   - `offset <= head` — already held: succeeds ONLY when the held event's payload and timestamp equal the
    ///     offered ones, and runs no `inOrder` (nothing was written). A different held event is
    ///     [StreamError.ReplicaEntryConflict]; an offset already evicted is [StreamError.CursorExpired];
    ///   - `offset > head + 1` — [StreamError.ReplicaOffsetGap], nothing appended.
    public Result<Long> appendOrderedAt(long offset, byte[] payload, long timestamp, Fn1<Result<Long>, Long> inOrder) {
        if (closed.get()) {
            return StreamError.General.BUFFER_CLOSED.result();
        }

        synchronized (appendLock) {
            return appendAtLocked(offset, payload, timestamp, headOffset() + 1, inOrder);
        }
    }

    private Result<Long> appendAtLocked(long offset,
                                        byte[] payload,
                                        long timestamp,
                                        long nextOffset,
                                        Fn1<Result<Long>, Long> inOrder) {
        if (offset == nextOffset) {
            return appendLocked(payload, timestamp).flatMap(inOrder);
        }

        return offset < nextOffset
               ? verifyHeld(offset, payload, timestamp)
               : new StreamError.ReplicaOffsetGap(streamName, partition, offset, nextOffset).result();
    }

    /// Held offsets are immutable until evicted, so equality of the held record with the offered one is the
    /// whole of "this replica already holds that event there".
    private Result<Long> verifyHeld(long offset, byte[] payload, long timestamp) {
        return readAppended(offset, 1).flatMap(events -> matchHeld(events, offset, payload, timestamp));
    }

    private Result<Long> matchHeld(List<RawEvent> events, long offset, byte[] payload, long timestamp) {
        return Option.from(events.stream().findFirst())
                     .filter(held -> isSameRecord(held, offset, payload, timestamp))
                     .toResult(new StreamError.ReplicaEntryConflict(streamName, partition, offset))
                     .map(RawEvent::offset);
    }

    private static boolean isSameRecord(RawEvent held, long offset, byte[] payload, long timestamp) {
        return held.offset() == offset
               && held.timestamp() == timestamp
               && Arrays.equals(held.data(), payload);
    }

    /// `result` is evaluated by the caller, so `appendLock` is already released here. The publisher only
    /// hands its queued offsets to the notifier; it never runs a listener.
    private <T> Result<T> notifyingAfter(Result<T> result) {
        startNotifierIfIdle();

        return result;
    }

    /// Starts this ring's serial notifier — a virtual thread that delivers the pending high-water offset
    /// and exits once nothing is pending — unless one is already running.
    private void startNotifierIfIdle() {
        if (pendingNotification.get() != NO_PENDING_NOTIFICATION && notifying.compareAndSet(false, true)) {
            Thread.ofVirtual().name("ring-notifier-" + streamName + "-" + partition).start(this::runNotifier);
        }
    }

    /// The `finally` is the notifier's liveness guarantee (#1258 review R3-1): whatever a listener does —
    /// even a `VirtualMachineError` rethrown by [#notifyGuarded] — the flag is cleared and, if an offset
    /// is still pending, a fresh notifier is started, so the ring can never stop notifying.
    @SuppressWarnings("JBCT-EX-01")
    private void runNotifier() {
        try {
            deliverPendingNotifications();
        } finally {
            notifying.set(false);
            startNotifierIfIdle();
        }
    }

    private void deliverPendingNotifications() {
        for (var offset = pendingNotification.getAndSet(NO_PENDING_NOTIFICATION); offset != NO_PENDING_NOTIFICATION; offset = pendingNotification.getAndSet(NO_PENDING_NOTIFICATION)) {
            notifyAppendListeners(offset);
        }
    }

    /// Capacity gate against the **allocated** (post-growth) data bytes — distinct from the cap gate in
    /// `append`. When a DROP_OLDEST/EVENTUAL ring is frozen below cap (`growthFrozen`, growth refused by
    /// the seam) an event with `allocatedDataBytes < length <= dataRegionSize` can never be stored:
    /// growth was already attempted and failed, and eviction can only free up to `allocatedDataBytes`,
    /// so writing `length` bytes into an `allocatedDataBytes` ring would self-overlap (corruption) or
    /// index a non-existent data segment (IndexOutOfBounds). This restores the invariant **never write
    /// more than `allocatedDataBytes` into the data region**:
    ///
    ///   - fits (`length <= allocatedDataBytes`, the common case) — proceed to the normal write.
    ///   - REJECT_WHEN_FULL (STRONG) and does not fit — the same loud `STREAM_MEMORY_EXCEEDED` it returns
    ///     when it cannot make room by growing.
    ///   - DROP_OLDEST (EVENTUAL) and does not fit — the event genuinely cannot be stored in the frozen
    ///     ring; drop it (NO write, no corruption) and report the distinct `EVENT_DROPPED` outcome. Never
    ///     success at the current head (#1233): that offset belongs to an already-stored event, and a
    ///     caller treating it as the new event's offset WAL-writes and replicates a phantom under it. The
    ///     publish path decides whether the stream may absorb the drop. See spec §4.2 / bug #7. The other
    ///     refusal an EVENTUAL append can meet is the eviction listener's: `SEALING_BEHIND` once the
    ///     pending-seal cap is reached on a partition with NO WAL — the non-crash-durable mode, where the
    ///     sealer's heap copy is the only holder (#1234, [#evictForSpace]). With a WAL the sealer never refuses.
    private Result<Long> appendIfFitsAllocated(byte[] payload, long timestamp) {
        if (payload.length <= allocatedDataBytes) {
            return appendWritten(payload, timestamp);
        }

        if (evictionPolicy == EvictionPolicy.REJECT_WHEN_FULL) {
            return StreamError.General.STREAM_MEMORY_EXCEEDED.result();
        }

        return StreamError.General.EVENT_DROPPED.result();
    }

    /// Runs AFTER growth so the REJECT_WHEN_FULL fullness check is evaluated against the grown
    /// allocation: a STRONG stream only reports BUFFER_FULL when it genuinely cannot fit even after
    /// growing to the cap. Seam-rejected growth has already returned STREAM_MEMORY_EXCEEDED upstream
    /// (in `ensureGrownFor`). Reached only when the event fits the allocated ring (bug #7 gate above), so
    /// it never overflows. No listener is queued here: listeners are notified when the event becomes
    /// VISIBLE ([#advanceVisible], #1235), after `appendLock` is released, on the serial notifier. See
    /// spec §4.2.
    private Result<Long> appendWritten(byte[] payload, long timestamp) {
        if (evictionPolicy == EvictionPolicy.REJECT_WHEN_FULL && countEvictionsForSpace(payload.length) > 0) {
            return StreamError.General.BUFFER_FULL.result();
        }

        return evictForSpace(payload.length).map(_ -> writeAppend(payload, timestamp));
    }

    private long writeAppend(byte[] payload, long timestamp) {
        var currentHead = rawHeadOffset();
        var newOffset = currentHead + 1;
        var slotIndex = Math.floorMod(newOffset, capacity);
        var dataPos = Math.floorMod(dataWritePos(), dataRing());

        writeDataBytes(dataPos, payload);
        writeIndexEntry(slotIndex, dataPos, payload.length, timestamp);
        updateHeaderAfterAppend(newOffset, payload.length);

        return newOffset;
    }

    /// Batch sibling of [#append]: visible at once when it succeeds.
    public Result<Long> appendBatch(List<byte[]> payloads, long[] timestamps) {
        return notifyingAfter(appendBatchVisible(payloads, timestamps));
    }

    private Result<Long> appendBatchVisible(List<byte[]> payloads, long[] timestamps) {
        synchronized (appendLock) {
            return appendBatchLocked(payloads, timestamps).onSuccess(this::queueAppendedVisible);
        }
    }

    /// Batch sibling of [#appendOrdered] (#1245): appends `payloads` as ONE contiguous run and runs
    /// `inOrder` with the run's LAST offset before any other append on this ring can be assigned one.
    /// A failed batch skips `inOrder`; listeners run after the section is released, as for
    /// [#appendOrdered].
    public <T> Result<T> appendBatchOrdered(List<byte[]> payloads, long[] timestamps, Fn1<Result<T>, Long> inOrder) {
        return notifyingAfter(appendBatchOrderedLocked(payloads, timestamps, inOrder));
    }

    private <T> Result<T> appendBatchOrderedLocked(List<byte[]> payloads,
                                                   long[] timestamps,
                                                   Fn1<Result<T>, Long> inOrder) {
        synchronized (appendLock) {
            return appendRunLocked(payloads, timestamps).flatMap(inOrder);
        }
    }

    /// Append `payloads` as ONE contiguous run, never worse than appending them one by one (#1287 review
    /// K1). A run that fits the ring after growth goes in as one batch. A run larger than that — up to
    /// the whole data region and beyond — is appended event by event under the same lock, each event
    /// evicting exactly as a sequential append would, so the run is still contiguous. Only when some event
    /// can never fit the allocation (a frozen ring) does the run refuse with
    /// [StreamError.General#RUN_DOES_NOT_FIT], appending nothing, so the caller can give each event
    /// the single-publish treatment instead.
    private Result<Long> appendRunLocked(List<byte[]> payloads, long[] timestamps) {
        var total = totalPayloadSize(payloads);

        return total <= dataRegionSize
               ? guardedAccess(() -> ensureGrownFor((int) total)).flatMap(_ -> appendRunGrown(payloads,
                                                                                              timestamps,
                                                                                              total))
               : appendEachLocked(payloads, timestamps);
    }

    private Result<Long> appendRunGrown(List<byte[]> payloads, long[] timestamps, long total) {
        return total <= allocatedDataBytes
               ? appendBatchLocked(payloads, timestamps)
               : appendEachLocked(payloads, timestamps);
    }

    /// Grows for the largest event first; the allocation never shrinks, so if the largest fits every
    /// event does, and no append below can take the drop branch.
    private Result<Long> appendEachLocked(List<byte[]> payloads, long[] timestamps) {
        var largest = largestPayloadSize(payloads);

        return guardedAccess(() -> ensureGrownFor(largest)).flatMap(_ -> appendEachIfEveryEventFits(payloads,
                                                                                                    timestamps,
                                                                                                    largest));
    }

    private Result<Long> appendEachIfEveryEventFits(List<byte[]> payloads, long[] timestamps, int largest) {
        return largest <= allocatedDataBytes
               ? appendEach(payloads, timestamps)
               : StreamError.General.RUN_DOES_NOT_FIT.result();
    }

    /// NOT atomic under REJECT_WHEN_FULL or sealing backpressure: an event can fail `BUFFER_FULL`
    /// after earlier ones of the run were appended, leaving them in the ring while the failed run skips
    /// its ordered continuation (no WAL frames, no replication for them). No durable/visible frontier
    /// advances for a failed run, and the caller reports unknown outcomes rather than retrying it.
    /// EVENTUAL rings can hit SEALING_BEHIND while evicting; STRONG batches use the consensus path.
    private Result<Long> appendEach(List<byte[]> payloads, long[] timestamps) {
        var last = success(rawHeadOffset());

        for (int i = 0; i < payloads.size(); i++) {
            var index = i;

            last = last.flatMap(_ -> appendLocked(payloads.get(index), timestamps[index]));
        }

        return last;
    }

    private static int largestPayloadSize(List<byte[]> payloads) {
        return payloads.stream()
                       .mapToInt(payload -> payload.length)
                       .max()
                       .orElse(0);
    }

    private Result<Long> appendBatchLocked(List<byte[]> payloads, long[] timestamps) {
        if (closed.get()) {
            return StreamError.General.BUFFER_CLOSED.result();
        }

        if (payloads.isEmpty()) {
            return success(rawHeadOffset());
        }

        var totalSize = totalPayloadSize(payloads);

        if (totalSize > dataRegionSize) {
            return new StreamError.EventTooLarge((int) totalSize, dataRegionSize).result();
        }

        synchronized (appendLock) {
            return guardedAccess(() -> ensureGrownFor((int) totalSize).flatMap(_ -> appendBatchIfFitsAllocated(payloads,
                                                                                                               timestamps,
                                                                                                               totalSize)));
        }
    }

    /// Batch analogue of `appendIfFitsAllocated` (bug #7): after growth was attempted, the batch total
    /// must still fit the **allocated** data bytes, otherwise a frozen-ring batch write would overflow
    /// the ring (corruption / segment overrun). STRONG rejects loud; EVENTUAL drops the whole batch (no
    /// write) and reports `EVENT_DROPPED`, never success at the current head (#1233, same reason as the
    /// single-event gate). See spec §4.2 / bug #7.
    private Result<Long> appendBatchIfFitsAllocated(List<byte[]> payloads, long[] timestamps, long totalSize) {
        if (totalSize <= allocatedDataBytes) {
            return appendBatchWritten(payloads, timestamps);
        }

        if (evictionPolicy == EvictionPolicy.REJECT_WHEN_FULL) {
            return StreamError.General.STREAM_MEMORY_EXCEEDED.result();
        }

        return StreamError.General.EVENT_DROPPED.result();
    }

    private Result<Long> appendBatchWritten(List<byte[]> payloads, long[] timestamps) {
        var totalSize = totalPayloadSize(payloads);

        if (evictionPolicy == EvictionPolicy.REJECT_WHEN_FULL && countEvictionsForSpace((int) totalSize) > 0) {
            return StreamError.General.BUFFER_FULL.result();
        }

        return evictForSpace((int) totalSize).map(_ -> appendPayloads(payloads, timestamps));
    }

    /// Position a FRESH ring (no appends yet, `headOffset() == -1`) so the NEXT append is assigned
    /// offset `base + 1`, leaving the ring logically EMPTY — it stores nothing (no data bytes, no
    /// index slot touched). Replay-only positioning op (spec PHASE A-WAL §W1): when sealed segments
    /// already cover `[0, base]`, a recovered ring resumes ABOVE `base` instead of restarting at 0,
    /// so WAL / segment / cursor offsets stay aligned. NOT a normal write — `append`'s `head + 1`
    /// assignment is unchanged.
    ///
    /// The post-seed header is made indistinguishable from a ring that organically reached `base`
    /// then had EVERYTHING evicted: head = `base`; tail (earliest retained) = `base + 1`, so reads
    /// at or below `base` cleanly MISS as below-earliest `CursorExpired` (never reading an unwritten
    /// slot); event count = 0; sealed high-water = `base` (all of `[0, base]` is sealed, none of it
    /// retained). The data write position stays 0 — the first real append writes at data position 0,
    /// exactly as on a fresh ring. Rejected with NO mutation on a non-fresh ring, a negative `base`,
    /// or a closed buffer.
    public Result<Unit> seedHead(long base) {
        if (closed.get()) {
            return StreamError.General.BUFFER_CLOSED.result();
        }

        synchronized (appendLock) {
            return guardedAccess(() -> seedHeadChecked(base));
        }
    }

    /// Native half of [#seedHead], behind the [#guardedAccess] boundary: the `closed` check above is a
    /// TOCTOU test that a concurrent release can win, so the header reads and writes here must still fail
    /// closed as `BUFFER_CLOSED` rather than throw out of the `Result` chain (#999).
    private Result<Unit> seedHeadChecked(long base) {
        var currentHead = rawHeadOffset();

        if (base < 0 || currentHead != -1) {
            return new StreamError.SeedRejected(base, currentHead).result();
        }
        // Tail first, then fence, then head — as evictOldest orders it: a reader that saw head = base
        // before tail = base + 1 would pass both range checks for an offset in [0, base] and copy an
        // unwritten slot (#1340 review N-4).
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_TAIL_OFFSET, base + 1);
        VarHandle.storeStoreFence();
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET, base);
        lastSealedOffset = base;
        durableOffset.set(base);
        visibleOffset.set(base);

        return unitResult();
    }

    /// Grow the data region until it can hold a write of `writeLen` bytes at the current logical
    /// write position, or until the cap is reached. A write spanning the logical ring-wrap point
    /// only happens at full cap, so growth (which always precedes wrap) needs to cover the highest
    /// linear position the next write touches: `floorMod(dataWritePos, dataRegionSize) + writeLen`,
    /// clamped to the cap. See spec §4.2.
    private Result<Long> ensureGrownFor(int writeLen) {
        var dataPos = Math.floorMod(dataWritePos(), dataRegionSize);
        var requiredLinearEnd = Math.min(dataPos + writeLen, dataRegionSize);

        return growUntil(requiredLinearEnd);
    }

    /// Grow until allocation reaches the required linear end or the cap, OR a growth attempt makes
    /// no progress (DROP_OLDEST stream whose seam rejected the pool — it will fall back to eviction
    /// in the write path). A STRONG (REJECT_WHEN_FULL) seam rejection short-circuits to a failure.
    private Result<Long> growUntil(long requiredAllocatedBytes) {
        if (allocatedDataBytes >= requiredAllocatedBytes || allocatedDataBytes >= dataRegionSize || growthFrozen) {
            return success(allocatedDataBytes);
        }

        var before = allocatedDataBytes;

        return growOneSegment().flatMap(after -> continueGrowth(requiredAllocatedBytes, before, after));
    }

    private Result<Long> continueGrowth(long requiredAllocatedBytes, long before, long after) {
        if (after > before) {
            return growUntil(requiredAllocatedBytes);
        }

        return success(after);
    }

    private Result<Long> growOneSegment() {
        var nextSegmentBytes = Math.min(DEFAULT_SEGMENT_BYTES, dataRegionSize - allocatedDataBytes);

        if (!reserve.test(nextSegmentBytes)) {
            return rejectGrowth();
        }

        return allocateGuarded(nextSegmentBytes).onSuccess(this::attachSegment)
                              .map(_ -> allocatedDataBytes)
                              .onFailure(_ -> release.accept(nextSegmentBytes));
    }

    /// DROP_OLDEST: freeze the ring at the current allocation and return it so `growUntil` halts and
    /// the caller falls back to eviction within the frozen ring. REJECT_WHEN_FULL: loud failure.
    /// See spec §4.2.
    private Result<Long> rejectGrowth() {
        if (evictionPolicy == EvictionPolicy.REJECT_WHEN_FULL) {
            return StreamError.General.STREAM_MEMORY_EXCEEDED.result();
        }

        growthFrozen = true;

        return success(allocatedDataBytes);
    }

    @Contract
    private void attachSegment(MemorySegment segment) {
        dataSegments.add(segment);
        allocatedDataBytes += segment.byteSize();
        accountedBytes += segment.byteSize();
    }

    /// JDK boundary for segment growth — one of the four marked `catch` sites in this file (see
    /// [#buildFloorGuarded]): native off-heap allocation (`Arena.allocate`) can fail with
    /// `OutOfMemoryError`. We isolate it here and convert to a `Result` failure so the caller releases
    /// the just-reserved bytes (accounting never leaks). See spec §4.3.
    @SuppressWarnings("JBCT-EX-03")
    private Result<MemorySegment> allocateGuarded(long bytes) {
        try {
            return success(arena.allocate(bytes, 64));
        } catch (OutOfMemoryError _) {
            return StreamError.General.STREAM_MEMORY_EXCEEDED.result();
        }
    }

    /// Native-access boundary for the read/append paths (bug #5). The buffer's `Arena.ofShared()`
    /// permits cross-thread access, but a concurrent `close()` (manager `reapIdleStreams`/`destroyStream`
    /// on a different thread) can win the race against the `closed.get()` TOCTOU check at the top of each
    /// public path and close the arena mid-`MemorySegment` access. The JDK keeps this memory-safe (no
    /// use-after-free) by throwing `IllegalStateException` ("already closed"), but that exception would
    /// otherwise ESCAPE the `Result` contract. Isolating the native access here converts that race into a
    /// clean `BUFFER_CLOSED` failure.
    ///
    /// An `IndexOutOfBoundsException` is NOT that race: it is an index or offset-arithmetic defect (bug #7's
    /// allocated-bytes gate prevents it on every legitimate path). #1247: it used to share the
    /// `BUFFER_CLOSED` mapping, which reported a corrupted ring as a benign release with no log. It is now
    /// logged at ERROR, counted by [#indexCorruptionCount], and surfaced as its own
    /// [StreamError.RingIndexCorrupted] cause.
    @SuppressWarnings("JBCT-EX-03")
    private <T> Result<T> guardedAccess(Supplier<Result<T>> access) {
        try {
            return access.get();
        } catch (IllegalStateException _) {
            return StreamError.General.BUFFER_CLOSED.result();
        } catch (IndexOutOfBoundsException e) {
            return reportIndexCorruption(e).result();
        }
    }

    public Result<MemorySegment> readSlice(long offset) {
        if (closed.get()) {
            return StreamError.General.BUFFER_CLOSED.result();
        }

        return guardedAccess(() -> readSliceChecked(offset));
    }

    private Result<MemorySegment> readSliceChecked(long offset) {
        var head = rawHeadOffset();
        var tail = rawTailOffset();

        if (head < 0) {
            return StreamError.General.BUFFER_EMPTY.result();
        }

        if (offset < tail) {
            return new StreamError.CursorExpired(offset, tail).result();
        }

        if (offset > Math.min(head, visibleOffset.get())) {
            return StreamError.General.BUFFER_EMPTY.result();
        }

        VarHandle.acquireFence();

        return readSliceAtOffset(offset).flatMap(slice -> retainedAfterCopy(offset, slice));
    }

    /// Registers a listener invoked with the new VISIBLE offset each time [#advanceVisible] moves it
    /// (#1235) — never on a bare append, so a consumer is never woken for an event it may not see. It runs
    /// on this ring's serial notifier, in offset order, never on the thread that advanced visibility and
    /// never under any lock (#1258 review B1, R2-1).
    @Contract
    public void addAppendListener(LongConsumer listener) {
        appendListeners.add(listener);
    }

    @Contract
    public void removeAppendListener(LongConsumer listener) {
        appendListeners.remove(listener);
    }

    /// Mark every offset up to `offset` durable. Monotonic: a lower value is ignored.
    @Contract
    public void markDurable(long offset) {
        durableOffset.accumulateAndGet(offset, Math::max);
    }

    /// Make every offset up to `offset` visible and, when that moves the visible position, raise the
    /// pending notification to it for the serial notifier (#1258's high-water model). Monotonic and
    /// lock-free: a lower value is ignored. The caller never runs a listener, so this is safe from the
    /// publisher, the WAL-commit and the replica-ack threads.
    @Contract
    public void advanceVisible(long offset) {
        queueVisibleAdvance(offset);
        startNotifierIfIdle();
    }

    /// Queues the CURRENT visible offset, not `offset`: a winner overtaken between its two steps then
    /// re-announces the newer high-water instead of an older one, so notified offsets never move backwards.
    @Contract
    private void queueVisibleAdvance(long offset) {
        if (visibleOffset.getAndAccumulate(offset, Math::max) < offset) {
            pendingNotification.accumulateAndGet(visibleOffset.get(), Math::max);
        }
    }

    /// Test seam (#1235): no notification is pending and no notifier is running, so every advance so far
    /// has been delivered to the listeners. A negative listener assertion waits for this condition instead
    /// of a timed window. The notifier clears its flag only after delivering, so a listener's effects
    /// happen-before a `true` here.
    boolean notifierIdle() {
        return pendingNotification.get() == NO_PENDING_NOTIFICATION && !notifying.get();
    }

    public long durableOffset() {
        return durableOffset.get();
    }

    public long visibleOffset() {
        return visibleOffset.get();
    }

    /// Consumer read, bounded by the VISIBLE position (#1235): an appended event that is not yet durable
    /// and acknowledged reads as not yet written.
    public Result<List<RawEvent>> read(long fromOffset, int maxEvents) {
        if (closed.get()) {
            return StreamError.General.BUFFER_CLOSED.result();
        }

        return guardedAccess(() -> readChecked(fromOffset, maxEvents, visibleOffset.get()));
    }

    /// Replication read, bounded by the APPENDED head: a replica catching up, a new owner pulling from a
    /// survivor, and the entity log fold all need events that are not yet visible. A replica that could
    /// only receive visible events could never supply the ack that makes them visible.
    public Result<List<RawEvent>> readAppended(long fromOffset, int maxEvents) {
        if (closed.get()) {
            return StreamError.General.BUFFER_CLOSED.result();
        }

        return guardedAccess(() -> readChecked(fromOffset, maxEvents, Long.MAX_VALUE));
    }

    private Result<List<RawEvent>> readChecked(long fromOffset, int maxEvents, long bound) {
        var tail = rawTailOffset();
        var head = Math.min(rawHeadOffset(), bound);

        if (head < 0) {
            return success(List.of());
        }

        if (fromOffset < tail) {
            return new StreamError.CursorExpired(fromOffset, tail).result();
        }

        if (fromOffset > head) {
            return success(List.of());
        }

        var count = (int) Math.min(maxEvents, head - fromOffset + 1);

        VarHandle.acquireFence();

        return retainedAfterCopy(fromOffset, readEvents(fromOffset, count));
    }

    private List<RawEvent> readEvents(long fromOffset, int count) {
        var events = new ArrayList<RawEvent>(count);

        for (long offset = fromOffset; offset < fromOffset + count; offset++) {
            events.add(readSingleEvent(offset));
        }

        return List.copyOf(events);
    }

    /// Seqlock validation of a copy against a concurrent eviction (#1340). Readers take no lock: the
    /// tail check at the top of [#readChecked] / [#readSliceChecked] is a snapshot, and a writer wrapping
    /// the ring advances the tail past `fromOffset` and overwrites that slot with the record of
    /// `fromOffset + capacity` while the copy is in flight. The copy then holds the NEWER record under
    /// the REQUESTED offset's label — well-formed, decodable, and another offset's data (measured at
    /// rc4: 3,499 of 23,984 tail reads on a 64-slot ring, `OffHeapRingBufferReadEvictionRaceTest`).
    ///
    /// The re-read decides AFTER the bytes are copied: if the tail has moved past `fromOffset`, some slot
    /// of the copy may have been reclaimed under it, and the whole read fails `CursorExpired` — the same
    /// typed refusal a read that arrived after the eviction gets, so every caller already handles it
    /// (`PartitionedStreamAccess.handleReadFailure` reroutes to the sealed tier). What makes the check a
    /// guarantee rather than an x86 accident is the fence pairing: [#evictOldest] publishes the new tail
    /// with a store-store fence BEFORE the slot is overwritten, and the reader's acquire fence here
    /// orders the copy's loads BEFORE the tail re-read. So a copy that observed the overwrite cannot
    /// observe the old tail. Holding the slot instead would put a lock on a path that has never had one
    /// and serialise every reader against the writer; the re-check keeps readers lock-free.
    private <T> Result<T> retainedAfterCopy(long fromOffset, T copy) {
        VarHandle.acquireFence();
        var tailAfterCopy = rawTailOffset();

        if (tailAfterCopy > fromOffset) {
            return new StreamError.CursorExpired(fromOffset, tailAfterCopy).result();
        }

        return success(copy);
    }

    /// Guarded header reads (#999). See [#guardedRead] for why a `long`-returning accessor needs a
    /// native-access boundary of its own and why a refusal reports the EMPTY-ring encoding.
    public long headOffset() {
        return guardedRead(NO_OFFSET, this::rawHeadOffset);
    }

    public long tailOffset() {
        return guardedRead(NO_OFFSET, this::rawTailOffset);
    }

    public long eventCount() {
        return guardedRead(NO_EVENTS, this::rawEventCount);
    }

    /// Raw header reads — the form every INTERNAL caller uses. Internal callers already run inside
    /// [#guardedAccess] and must ABORT on a concurrent close, never continue with a refusal sentinel: a
    /// sentinel here would be actively harmful, because [#updateHeaderAfterAppend] persists
    /// `rawEventCount() + 1` and [#evictOldest] persists `rawEventCount() - 1`, so a swallowed `0` would
    /// write a NEGATIVE event count into the header. Letting the native exception propagate to the
    /// enclosing [#guardedAccess] is what makes those paths fail closed as `BUFFER_CLOSED`.
    private long rawHeadOffset() {
        return controlSegment.get(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET);
    }

    private long rawTailOffset() {
        return controlSegment.get(ValueLayout.JAVA_LONG, HEADER_TAIL_OFFSET);
    }

    private long rawEventCount() {
        return controlSegment.get(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT);
    }

    /// Native-access boundary for the primitive header accessors (#999) — the `long`-returning sibling of
    /// [#guardedAccess], which only ever covered the `Result`-returning paths.
    ///
    /// These accessors are read by LIVE paths: the replication receive handler's `nextExpectedOffset` (on a
    /// Netty event loop), the backfill thread's `partitionInfo`, and the status/metrics surfaces. The
    /// closing side is NOT shutdown-specific — `StreamPartitionManager.reconcileReshuffle`, scheduled every
    /// `STREAM_RESHUFFLE_RECONCILE_INTERVAL` (5s) by `AetherNode`, releases a materialized ring on confirmed
    /// role loss, and `destroyStream` closes one straight from the Management API. `Arena.ofShared()` keeps
    /// the freed memory safe by throwing `IllegalStateException` AT THE READER, so without this boundary the
    /// throw escaped an accessor that has no failure channel: it killed the `stream-partition-backfill`
    /// thread outright, and on the event loop it was swallowed by `RabiaNode.dispatchLoudly`, DROPPING a
    /// replication message.
    ///
    /// A refused read reports the EMPTY-ring encoding, which every caller already treats as "this node does
    /// not hold the partition" — true by construction once the ring is released. That makes the racy path
    /// converge on the behaviour the non-racy path has always had (the ring is removed from the entry's
    /// `materialized` map BEFORE it is closed, so a later resolution returns [Option#none] and yields the
    /// same values), rather than inventing a third outcome.
    ///
    /// An `IndexOutOfBoundsException` here is a ring defect, not the close race (#1247): it is logged at
    /// ERROR and counted by [#indexCorruptionCount] instead of [#closedUnderReaderCount], and the read still
    /// reports the refusal sentinel because these accessors have no failure channel.
    @SuppressWarnings("JBCT-EX-03")
    private long guardedRead(long refused, LongSupplier read) {
        if (closed.get()) {
            return refused;
        }

        readWindowProbe.run();
        try {
            return read.getAsLong();
        } catch (IllegalStateException _) {
            reportClosedUnderReader();

            return refused;
        } catch (IndexOutOfBoundsException e) {
            reportIndexCorruption(e);

            return refused;
        }
    }

    /// Void-shaped sibling of [#guardedRead] for the public retention sweeps, which read and then rewrite
    /// the control region and have no value to report. Under `appendLock`: a sweep rewrites the tail and
    /// the event count that a concurrent append also rewrites (#1231).
    private void guardedSweep(Runnable sweep) {
        synchronized (appendLock) {
            guardedRead(NO_EVENTS, () -> sweepAsRead(sweep));
        }
    }

    private long sweepAsRead(Runnable sweep) {
        sweep.run();

        return NO_EVENTS;
    }

    /// Report the GENUINE race — the arena was closed while this read was already in flight — distinctly
    /// from the benign late arrival that the `closed` fast path above absorbs silently (#999 expectation 3).
    /// Until now this event surfaced only as an anonymous `dispatchLoudly` "message dropped by this handler"
    /// line, or as nothing at all on the thread it killed: no stream, no partition, and no way to tell a
    /// shutdown drop from a live one. The counter is read by [#closedUnderReaderCount] so a run can be
    /// scored without grepping logs.
    private void reportClosedUnderReader() {
        var occurrence = closedUnderReader.incrementAndGet();

        log.warn("OffHeapRingBuffer {}[{}]: arena was closed by a concurrent release WHILE a read was in "
                + "flight — read refused as empty (occurrence {} for this ring). The partition was released "
                + "or destroyed under an in-flight reader; this is NOT shutdown-specific.",
                 streamName,
                 partition,
                 occurrence);
    }

    /// Count of reads refused because the arena was closed UNDER an in-flight reader (#999). Zero on every
    /// quiescent ring; non-zero means the release path overlapped a live reader on this partition.
    /// Append-listener invocations that threw — an exception or an `Error` — since the ring was built. Each
    /// is logged; none stops later notifications (#1258 review R3-1).
    public long appendListenerFailures() {
        return appendListenerFailures.get();
    }

    public long closedUnderReaderCount() {
        return closedUnderReader.get();
    }

    /// Count of native accesses refused because index or offset arithmetic went out of bounds (#1247).
    /// Zero on every healthy ring; non-zero is a ring defect, never a release race.
    public long indexCorruptionCount() {
        return indexCorruption.get();
    }

    /// Report an out-of-bounds native access (#1247) distinctly from [#reportClosedUnderReader]: the arena
    /// was open, so the index or offset arithmetic is wrong and the ring's contents cannot be trusted.
    private StreamError.RingIndexCorrupted reportIndexCorruption(IndexOutOfBoundsException fault) {
        var occurrence = indexCorruption.incrementAndGet();

        log.error("OffHeapRingBuffer {}[{}]: out-of-bounds native access — ring index or offset arithmetic is "
                 + "corrupted, NOT a concurrent close (occurrence {} for this ring)",
                  streamName,
                  partition,
                  occurrence,
                  fault);

        return new StreamError.RingIndexCorrupted(streamName,
                                                  partition,
                                                  String.valueOf(fault.getMessage()));
    }

    /// Test-only seam (#1253): install a probe that [#guardedRead] runs AFTER its `closed` fast-path check and
    /// BEFORE the native read — exactly the window a concurrent `close()` must land in for the reader to be
    /// refused by the JDK rather than by the flag. A probe that parks one reader there until `close()` has
    /// completed makes that race deterministic instead of scheduler-dependent. Must be installed before any
    /// reader thread starts. Production never touches it.
    @Contract
    void readWindowProbe(Runnable probe) {
        readWindowProbe = probe;
    }

    public long allocatedBytes() {
        return controlSegment.byteSize() + allocatedDataBytes;
    }

    /// Control-region bytes (header + index). This portion of the floor is NOT accounted against the
    /// seam (the seam tracks only data segments), so the manager releases it separately on destroy to
    /// avoid double-release. See spec §4.3.
    public long controlBytes() {
        return controlSegment.byteSize();
    }

    /// Retention is a READ-MODIFY-WRITE over the control region, so it needs the same native-access
    /// boundary as every other public path (#999): a concurrent release closing the arena mid-sweep would
    /// otherwise throw out of a `void` method onto whichever thread drives retention.
    @Contract
    public void applyRetention(RetentionPolicy policy) {
        guardedSweep(() -> applyRetentionChecked(policy));
    }

    @SuppressWarnings("JBCT-ZONE-02")
    private void applyRetentionChecked(RetentionPolicy policy) {
        policy.tierAwareRetention().filter(_ -> lastSealedOffset >= 0).onPresent(this::applyTierAwareRetention);
        applyNormalRetention(policy);
    }

    @Contract
    public void updateLastSealedOffset(long sealedOffset) {
        lastSealedOffset = sealedOffset;
    }

    public long lastSealedOffset() {
        return lastSealedOffset;
    }

    /// Tell this ring's eviction listener which WAL holds the partition's records (#1234), before anything
    /// is replayed into the ring, so every hand-over — recovery-time ones included — knows the WAL is there.
    public Unit attachWal(PartitionWal wal) {
        return listener.walAttached(streamName, partition, wal);
    }

    /// Guarded for the same reason as [#applyRetention] (#999) — a public `void` path that reads and then
    /// rewrites the control region.
    @Contract
    public void evictByAge(long maxAgeMs) {
        guardedSweep(() -> evictByAgeChecked(maxAgeMs));
    }

    private void evictByAgeChecked(long maxAgeMs) {
        var cutoff = System.currentTimeMillis() - maxAgeMs;
        var countToEvict = countEvictionsByAge(cutoff);

        notifyAndEvict(countToEvict);
    }

    @Contract
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            appendListeners.clear();
            release.accept(accountedBytes);
            arena.close();
        }
    }

    /// Close the native arena WITHOUT returning bytes to the seam (bug #6 partial-construction cleanup).
    /// Used only when a sibling partition's floor allocation failed and the manager will release the
    /// ENTIRE reserved floor lump itself: routing these built buffers through `close()` (which seam-
    /// releases their first segment) on top of the manager's full-floor release would DOUBLE-release.
    /// This frees the off-heap memory (no Arena leak) while leaving the budget for the manager to settle
    /// in one place. See spec §4.3.
    @Contract
    void closeWithoutRelease() {
        if (closed.compareAndSet(false, true)) {
            appendListeners.clear();
            arena.close();
        }
    }

    @SuppressWarnings("JBCT-ZONE-02")
    private void applyNormalRetention(RetentionPolicy policy) {
        if (policy.mode() == RetentionMode.ALL) {
            applyAllModeRetention(policy);
        } else {
            applyAnyModeRetention(policy);
        }
    }

    private void applyAnyModeRetention(RetentionPolicy policy) {
        evictByCount(policy.maxCount());
        evictBySize(policy.maxBytes());
        evictByAge(policy.maxAgeMs());
    }

    @SuppressWarnings("JBCT-ZONE-02")
    private void applyAllModeRetention(RetentionPolicy policy) {
        var countConfigured = policy.maxCount() != Long.MAX_VALUE;
        var sizeConfigured = policy.maxBytes() != Long.MAX_VALUE;
        var ageConfigured = policy.maxAgeMs() != Long.MAX_VALUE;
        var countExcess = countConfigured
                          ? Math.max(0, rawEventCount() - policy.maxCount())
                          : Long.MAX_VALUE;
        var sizeExcess = sizeConfigured
                         ? countEvictionsBySize(policy.maxBytes())
                         : Long.MAX_VALUE;
        var ageExcess = ageConfigured
                        ? countEvictionsByAge(System.currentTimeMillis() - policy.maxAgeMs())
                        : Long.MAX_VALUE;
        var allExceeded = (!countConfigured || countExcess > 0) && (!sizeConfigured || sizeExcess > 0) && (!ageConfigured || ageExcess > 0);

        if (allExceeded) {
            notifyAndEvict(minConfiguredExcess(countExcess, sizeExcess, ageExcess));
        }
    }

    private static long minConfiguredExcess(long countExcess, long sizeExcess, long ageExcess) {
        var min = Long.MAX_VALUE;

        if (countExcess != Long.MAX_VALUE && countExcess > 0) {
            min = Math.min(min, countExcess);
        }

        if (sizeExcess != Long.MAX_VALUE && sizeExcess > 0) {
            min = Math.min(min, sizeExcess);
        }

        if (ageExcess != Long.MAX_VALUE && ageExcess > 0) {
            min = Math.min(min, ageExcess);
        }

        return min == Long.MAX_VALUE
               ? 0
               : min;
    }

    @SuppressWarnings("JBCT-ZONE-02")
    private void applyTierAwareRetention(TierAwareRetention tierAware) {
        var sealedCount = countSealedEvents();
        var sealedExcess = sealedCount - tierAware.postSealMaxCount();

        if (sealedExcess > 0) {
            notifyAndEvict(sealedExcess);
        }

        evictSealedByAge(tierAware.postSealBufferMs());
    }

    private long countSealedEvents() {
        var tail = rawTailOffset();
        var sealed = lastSealedOffset;

        if (sealed < tail) {
            return 0;
        }

        return sealed - tail + 1;
    }

    @SuppressWarnings("JBCT-PAT-01")
    private void evictSealedByAge(long postSealBufferMs) {
        var cutoff = System.currentTimeMillis() - postSealBufferMs;
        var tail = rawTailOffset();
        var sealed = lastSealedOffset;
        var count = 0L;

        while (tail + count <= sealed) {
            var slotIndex = Math.floorMod(tail + count, capacity);
            var timestamp = readTimestamp(slotIndex);

            if (timestamp >= cutoff) {
                break;
            }

            count++;
        }

        notifyAndEvict(count);
    }

    /// A plain append has no durability gate: durable and visible at once. Called inside the section.
    @Contract
    private void queueAppendedVisible(long offset) {
        markDurable(offset);
        queueVisibleAdvance(offset);
    }

    private void notifyAppendListeners(long offset) {
        appendListeners.forEach(listener -> notifyGuarded(listener, offset));
    }

    /// A listener's failure is logged and counted here and goes no further: it must never surface on an
    /// unrelated publish, nor stop the notifications that follow it (#1258 review R2-2, R3-1). That holds
    /// for an `Error` too — slice code can throw `StackOverflowError`, `AssertionError` or a
    /// `LinkageError` after a reload — logged at ERROR. `StackOverflowError` is a `VirtualMachineError`
    /// but is the listener's own runaway recursion, fully recovered once its stack unwinds, so it is
    /// handled like any other `Error`. Every other `VirtualMachineError` (out of memory, internal error)
    /// is rethrown: the JVM itself is failing. The listeners after it then miss that offset, but
    /// [#runNotifier]'s `finally` keeps the ring notifying later ones.
    @SuppressWarnings("JBCT-EX-01")
    private void notifyGuarded(LongConsumer listener, long offset) {
        try {
            listener.accept(offset);
        } catch (RuntimeException e) {
            appendListenerFailures.incrementAndGet();
            log.warn("OffHeapRingBuffer {}[{}]: append listener failed at offset {}: {}",
                     streamName,
                     partition,
                     offset,
                     e.toString());
        } catch (StackOverflowError e) {
            appendListenerFailures.incrementAndGet();
            log.error("OffHeapRingBuffer {}[{}]: append listener overflowed its stack at offset {}; later notifications continue",
                      streamName,
                      partition,
                      offset);
        } catch (VirtualMachineError e) {
            appendListenerFailures.incrementAndGet();

            throw e;
        } catch (Error e) {
            appendListenerFailures.incrementAndGet();
            log.error("OffHeapRingBuffer {}[{}]: append listener threw {} at offset {}; later notifications continue",
                      streamName,
                      partition,
                      e.toString(),
                      offset);
        }
    }

    private static long totalPayloadSize(List<byte[]> payloads) {
        var total = 0L;

        for (var payload : payloads) {
            total += payload.length;
        }

        return total;
    }

    private long appendPayloads(List<byte[]> payloads, long[] timestamps) {
        var currentHead = rawHeadOffset();
        var lastOffset = currentHead;

        for (int i = 0; i < payloads.size(); i++) {
            var payload = payloads.get(i);

            lastOffset = currentHead + 1 + i;
            var slotIndex = Math.floorMod(lastOffset, capacity);
            var dataPos = Math.floorMod(dataWritePos(), dataRing());

            writeDataBytes(dataPos, payload);
            writeIndexEntry(slotIndex, dataPos, payload.length, timestamps[i]);
            updateHeaderAfterAppend(lastOffset, payload.length);
        }

        return lastOffset;
    }

    private Result<MemorySegment> readSliceAtOffset(long offset) {
        var slotIndex = Math.floorMod(offset, capacity);
        var indexPos = indexStart + slotIndex * INDEX_ENTRY_SIZE;
        var dataPos = controlSegment.get(ValueLayout.JAVA_LONG, indexPos + INDEX_DATA_OFFSET);
        var dataLen = controlSegment.get(ValueLayout.JAVA_INT, indexPos + INDEX_DATA_LENGTH);

        return success(MemorySegment.ofArray(readDataBytes(dataPos, dataLen)));
    }

    private long dataWritePos() {
        return controlSegment.get(ValueLayout.JAVA_LONG, HEADER_DATA_WRITE_POS);
    }

    private long dataTailPos() {
        return controlSegment.get(ValueLayout.JAVA_LONG, HEADER_DATA_TAIL_POS);
    }

    /// Bytes of the data region held by live records — exact, whatever the ring's wrap state.
    private long liveDataBytes() {
        return dataWritePos() - dataTailPos();
    }

    /// Current wrap modulus of the logical data ring = the live allocated data bytes. While the
    /// region is still growing this is raised before each write to cover it (so writes never wrap
    /// below cap); once a stream can no longer grow (EVENTUAL pool-exhausted) it stays fixed and the
    /// ring wraps within the allocated bytes — i.e. behaves like a fixed ring at its current size.
    /// At full cap this equals `dataRegionSize`. Always <= `dataRegionSize`. See spec §4.2.
    private long dataRing() {
        return allocatedDataBytes;
    }

    /// Write a payload into the logical data ring at `dataPos`. Splits on the ring-wrap boundary
    /// (`dataRing()`), then each chunk is further split across data-segment boundaries by
    /// `copyIntoData`. See spec §4.2.
    private void writeDataBytes(long dataPos, byte[] payload) {
        var remaining = dataRing() - dataPos;

        if (remaining >= payload.length) {
            copyIntoData(dataPos, payload, 0, payload.length);
        } else {
            copyIntoData(dataPos, payload, 0, (int) remaining);
            copyIntoData(0, payload, (int) remaining, (int)(payload.length - remaining));
        }
    }

    /// Read a payload from the logical data ring at `dataPos`. Mirror of `writeDataBytes`.
    private byte[] readDataBytes(long dataPos, int dataLen) {
        var eventBytes = new byte[dataLen];
        var remaining = dataRing() - dataPos;

        if (remaining >= dataLen) {
            copyOutOfData(dataPos, eventBytes, 0, dataLen);
        } else {
            copyOutOfData(dataPos, eventBytes, 0, (int) remaining);
            copyOutOfData(0, eventBytes, (int) remaining, (int)(dataLen - remaining));
        }

        return eventBytes;
    }

    /// Copy `len` bytes from `src[srcOffset..]` into the logical data ring starting at linear
    /// position `pos`, crossing whatever data-segment boundaries the range [pos, pos+len) spans.
    /// `pos + len` never exceeds `dataRegionSize` (ring-wrap is handled by the caller); and a write
    /// that reaches segment N implies the data region has already grown to cover N (growth precedes
    /// every write). See spec §4.2.
    private void copyIntoData(long pos, byte[] src, int srcOffset, int len) {
        var srcSeg = MemorySegment.ofArray(src);
        var copied = 0;

        while (copied < len) {
            var linearPos = pos + copied;
            var segmentIndex = (int)(linearPos / DEFAULT_SEGMENT_BYTES);
            var segmentOffset = linearPos % DEFAULT_SEGMENT_BYTES;
            var segment = dataSegments.get(segmentIndex);
            var spaceInSegment = segment.byteSize() - segmentOffset;
            var chunk = (int) Math.min(len - copied, spaceInSegment);

            MemorySegment.copy(srcSeg, srcOffset + copied, segment, segmentOffset, chunk);
            copied += chunk;
        }
    }

    /// Copy `len` bytes out of the logical data ring starting at linear position `pos` into
    /// `dest[destOffset..]`, crossing whatever data-segment boundaries the range spans. Mirror of
    /// `copyIntoData`.
    private void copyOutOfData(long pos, byte[] dest, int destOffset, int len) {
        var destSeg = MemorySegment.ofArray(dest);
        var copied = 0;

        while (copied < len) {
            var linearPos = pos + copied;
            var segmentIndex = (int)(linearPos / DEFAULT_SEGMENT_BYTES);
            var segmentOffset = linearPos % DEFAULT_SEGMENT_BYTES;
            var segment = dataSegments.get(segmentIndex);
            var spaceInSegment = segment.byteSize() - segmentOffset;
            var chunk = (int) Math.min(len - copied, spaceInSegment);

            MemorySegment.copy(segment, segmentOffset, destSeg, destOffset + copied, chunk);
            copied += chunk;
        }
    }

    private void writeIndexEntry(long slotIndex, long dataPos, int dataLength, long timestamp) {
        var indexPos = indexStart + slotIndex * INDEX_ENTRY_SIZE;

        controlSegment.set(ValueLayout.JAVA_LONG, indexPos + INDEX_DATA_OFFSET, dataPos);
        controlSegment.set(ValueLayout.JAVA_INT, indexPos + INDEX_DATA_LENGTH, dataLength);
        controlSegment.set(ValueLayout.JAVA_LONG, indexPos + INDEX_TIMESTAMP, timestamp);
    }

    /// The slot's data and index stores must be visible before the head that publishes it (#1340): a
    /// reader that sees `newHeadOffset` copies that slot next, and without the release fence it could
    /// read the index entry the slot held `capacity` offsets ago — the head-side twin of the tail race.
    private void updateHeaderAfterAppend(long newHeadOffset, int payloadLength) {
        VarHandle.releaseFence();
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET, newHeadOffset);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_DATA_WRITE_POS, dataWritePos() + payloadLength);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT, rawEventCount() + 1);
    }

    private RawEvent readSingleEvent(long offset) {
        var slotIndex = Math.floorMod(offset, capacity);
        var indexPos = indexStart + slotIndex * INDEX_ENTRY_SIZE;
        var dataPos = controlSegment.get(ValueLayout.JAVA_LONG, indexPos + INDEX_DATA_OFFSET);
        var dataLen = controlSegment.get(ValueLayout.JAVA_INT, indexPos + INDEX_DATA_LENGTH);
        var timestamp = controlSegment.get(ValueLayout.JAVA_LONG, indexPos + INDEX_TIMESTAMP);
        var eventBytes = readDataBytes(dataPos, dataLen);

        return RawEvent.rawEvent(offset, eventBytes, timestamp);
    }

    private long dataLengthAt(long slotIndex) {
        var indexPos = indexStart + slotIndex * INDEX_ENTRY_SIZE;

        return controlSegment.get(ValueLayout.JAVA_INT, indexPos + INDEX_DATA_LENGTH);
    }

    private long readTimestamp(long slotIndex) {
        var indexPos = indexStart + slotIndex * INDEX_ENTRY_SIZE;

        return controlSegment.get(ValueLayout.JAVA_LONG, indexPos + INDEX_TIMESTAMP);
    }

    /// Make room for an append. A refusal by the eviction listener (#1234) leaves every event in place and is
    /// returned to the append, which then writes nothing.
    private Result<Unit> evictForSpace(int payloadLength) {
        return handOverAndEvict(countEvictionsForSpace(payloadLength));
    }

    private long countEvictionsForSpace(int payloadLength) {
        var count = 0L;
        var simulatedTail = rawTailOffset();
        var simulatedCount = rawEventCount();
        var simulatedLive = liveDataBytes();

        while (simulatedCount >= capacity) {
            simulatedLive -= dataLengthAt(Math.floorMod(simulatedTail, capacity));
            simulatedTail++;
            simulatedCount--;
            count++;
        }
        // Data-region pressure is measured against the **allocated** data bytes, not the cap: an
        // EVENTUAL stream that could not grow (pool exhausted) must evict to fit within what it has.
        // While the region can still grow (allocated < cap) growth covers the write, so this loop is a
        // no-op. See spec §4.2. The live count is exact (write position minus tail position), so a ring
        // that is EXACTLY full evicts before the write lands on its tail record (#1340 review M-1).
        while (simulatedCount > 0 && simulatedLive + payloadLength > allocatedDataBytes) {
            simulatedLive -= dataLengthAt(Math.floorMod(simulatedTail, capacity));
            simulatedTail++;
            simulatedCount--;
            count++;
        }

        return count;
    }

    private void evictOldest() {
        var tail = rawTailOffset();

        if (tail > rawHeadOffset()) {
            return;
        }

        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_TAIL_OFFSET, tail + 1);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT, rawEventCount() - 1);
        controlSegment.set(ValueLayout.JAVA_LONG,
                           HEADER_DATA_TAIL_POS,
                           dataTailPos() + dataLengthAt(Math.floorMod(tail, capacity)));
        // The new tail must be visible before the slot it frees is overwritten — the writer half of
        // the reader's post-copy check in retainedAfterCopy (#1340).
        VarHandle.storeStoreFence();
    }

    private void evictByCount(long maxCount) {
        var excess = rawEventCount() - maxCount;

        if (excess > 0) {
            notifyAndEvict(excess);
        }
    }

    private void evictBySize(long maxBytes) {
        var countToEvict = countEvictionsBySize(maxBytes);

        notifyAndEvict(countToEvict);
    }

    /// Retention-driven reclamation. A refusal by the eviction listener is absorbed by design-out, not
    /// loss: the events stay in the ring, readable and counted against its retention, and the next retention
    /// pass hands them over again. The listener that refused has already reported it (#1234). [Contract]:
    /// retention is a `void` sweep driven from [#applyRetention] / [#evictByAge], so the refusal has no caller
    /// to return to — the same shape as the other `void` sweep paths here.
    @Contract
    private void notifyAndEvict(long count) {
        handOverAndEvict(count).onFailure(this::retentionDeferred);
    }

    private void retentionDeferred(Cause cause) {
        log.debug("OffHeapRingBuffer {}[{}]: retention deferred, eviction listener refused: {}",
                  streamName,
                  partition,
                  cause.message());
    }

    /// Hand the oldest `count` events to the eviction listener and reclaim them once it has taken them. The
    /// listener takes ownership synchronously and seals asynchronously, so reclamation is immediate; the
    /// partition WAL holds the events until their seal lands (#1234).
    private Result<Unit> handOverAndEvict(long count) {
        if (count <= 0) {
            return unitResult();
        }

        return handOver(count).onSuccess(_ -> evictOldest(count));
    }

    private Result<Unit> handOver(long count) {
        if (listener == EvictionListener.NOOP) {
            return unitResult();
        }

        var events = collectEvictedEvents(count);

        return listener.onEviction(streamName, partition, events)
                       .onSuccess(_ -> updateSealedOffsetFromEvents(events));
    }

    private void evictOldest(long count) {
        for (long i = 0; i < count; i++) {
            evictOldest();
        }
    }

    private void updateSealedOffsetFromEvents(List<RawEvent> events) {
        if (events.isEmpty()) {
            return;
        }

        var sealedTo = events.getLast().offset();

        if (sealedTo > lastSealedOffset) {
            lastSealedOffset = sealedTo;
        }
    }

    private List<OffHeapRingBuffer.RawEvent> collectEvictedEvents(long count) {
        var tail = rawTailOffset();
        var events = new ArrayList<RawEvent>((int) count);

        for (long i = 0; i < count; i++) {
            events.add(readSingleEvent(tail + i));
        }

        return List.copyOf(events);
    }

    private long countEvictionsByAge(long cutoff) {
        var count = 0L;
        var tail = rawTailOffset();

        while (count < rawEventCount()) {
            var slotIndex = Math.floorMod(tail + count, capacity);
            var timestamp = readTimestamp(slotIndex);

            if (timestamp >= cutoff) {
                break;
            }

            count++;
        }

        return count;
    }

    private long countEvictionsBySize(long maxBytes) {
        var count = 0L;
        var tail = rawTailOffset();
        var head = rawHeadOffset();
        var live = liveDataBytes();

        while (tail + count <= head && live > maxBytes) {
            live -= dataLengthAt(Math.floorMod(tail + count, capacity));
            count++;
        }

        return count;
    }

    public record RawEvent(long offset, byte[] data, long timestamp) {
        public RawEvent {
            data = data.clone();
        }

        public static RawEvent rawEvent(long offset, byte[] data, long timestamp) {
            return new RawEvent(offset, data, timestamp);
        }

        @Override
        public byte[] data() {
            return data.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof RawEvent other
                   && offset == other.offset
                   && timestamp == other.timestamp
                   && Arrays.equals(data, other.data);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * Long.hashCode(offset) + Arrays.hashCode(data)) + Long.hashCode(timestamp);
        }
    }
}
