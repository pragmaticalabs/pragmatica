// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.IntStream;

import org.pragmatica.aether.slice.stream.RemovalCause;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.stream.RingStateViolated.ringStateViolated;
import static org.pragmatica.lang.Result.success;


/// The ring's tail as ONE immutable value R, published once per change through [RingHeader#publish]: the tail, what was removed
/// below it and why. Every field is final and the run array is never written after construction (a successor copies it, or
/// shares it when it did not change), so a reader that holds an R holds a consistent answer.
///
/// R covers `[0, tail)` by construction:
///  - `[0, firstHeld)` is the seed run, never dropped: this copy never held it (a seed positioned the ring above it).
///  - `[firstHeld, floor)` is the cause SET `dropped` (a 14-bit set by [RemovalCause] ordinal): the runs that fell off the 16.
///  - `[floor, tail)` is up to 16 runs, each (end, cause), packed `end << 4 | ordinal` in one array; run `i` starts where run
///    `i - 1` ends (the first at `floor`) and the last ends at `tail`.
///
/// The makers are [#EMPTY], [#seeded] (from `EMPTY` only) and [#removed] (the tail must rise); [#advanced] is the PERSISTENT
/// ring's maker, which carries the tail only. A refused maker returns [RingStateViolated].
final class RemovalLedger {
    static final int MAX_RUNS = 16;
    private static final int CAUSE_BITS = 4;
    private static final long CAUSE_MASK = (1L << CAUSE_BITS) - 1;
    private static final RemovalCause[] CAUSES = RemovalCause.values();
    private static final long[] NO_RUNS = new long[0];
    static final RemovalLedger EMPTY = new RemovalLedger(0L, 0L, 0, NO_RUNS, 0L);

    private final long firstHeld;
    private final long floor;
    private final int dropped;
    private final long[] runs;
    private final long tail;

    private RemovalLedger(long firstHeld, long floor, int dropped, long[] runs, long tail) {
        this.firstHeld = firstHeld;
        this.floor = floor;
        this.dropped = dropped;
        this.runs = runs;
        this.tail = tail;
    }

    long firstHeld() {
        return firstHeld;
    }

    long floor() {
        return floor;
    }

    long tail() {
        return tail;
    }

    int runCount() {
        return runs.length;
    }

    /// The cause set of the runs that fell off the 16, as a mask by [RemovalCause] ordinal.
    int droppedMask() {
        return dropped;
    }

    /// The position of a seed: `firstHeld = tail = base + 1`, as the ring's tail was set by the seed before R existed. Only
    /// `EMPTY` is seeded; the seed run `[0, firstHeld)` is implicit and never dropped.
    Result<RemovalLedger> seeded(long base) {
        return this != EMPTY
               ? ringStateViolated("seeded on a value that is not EMPTY (tail " + tail + ")").result()
               : success(new RemovalLedger(base + 1, base + 1, 0, NO_RUNS, base + 1));
    }

    /// `[tail, to)` was removed by `cause`. The tail must rise. An adjacent run of the same cause is extended; a 17th run drops
    /// the lowest one into the cause set and raises the floor to its end.
    Result<RemovalLedger> removed(long to, RemovalCause cause) {
        return to <= tail
               ? ringStateViolated("removed to " + to + " does not raise the tail " + tail).result()
               : success(withRun(to, cause));
    }

    /// The PERSISTENT ring's successor: the tail alone moves, no cause is kept (it answers `RETENTION` for every removal).
    Result<RemovalLedger> advanced(long to) {
        return to <= tail
               ? ringStateViolated("advanced to " + to + " does not raise the tail " + tail).result()
               : success(new RemovalLedger(firstHeld, floor, dropped, runs, to));
    }

    /// The refusal of a read of `requested` below this value's tail, a pure function of this value and of the reader's role.
    /// `causes` is every cause covering any offset in `[requested, tail)`, the dropped set included when `requested` is below the
    /// floor; `cause` is the one covering `requested` itself ([RemovalCause#UNRECORDED] where only the dropped SET covers it). A
    /// request below `firstHeld` is answered `LIFE_LOST` for the owner and [StreamError.BelowCopyTail] for any other reader.
    Cause answer(long requested, boolean owner) {
        return requested < firstHeld && !owner
               ? new StreamError.BelowCopyTail(firstHeld, tail)
               : expired(requested);
    }

    private StreamError.CursorExpired expired(long requested) {
        var cause = causeAt(requested);

        return new StreamError.CursorExpired(requested, tail, reasonOf(cause), cause, causesFrom(requested));
    }

    private static StreamError.CursorExpired.Reason reasonOf(RemovalCause cause) {
        return StreamError.CursorExpired.reasonOf(cause).fold(() -> StreamError.CursorExpired.Reason.EXPIRED,
                                                              reason -> reason);
    }

    private RemovalCause causeAt(long requested) {
        return requested < firstHeld
               ? RemovalCause.LIFE_LOST
               : requested < floor
                 ? RemovalCause.UNRECORDED
                 : causeOfRunCovering(requested);
    }

    private RemovalCause causeOfRunCovering(long requested) {
        return IntStream.range(0, runs.length)
                        .filter(index -> endOf(runs[index]) > requested)
                        .mapToObj(index -> causeOf(runs[index]))
                        .findFirst()
                        .orElse(RemovalCause.UNRECORDED);
    }

    private Set<RemovalCause> causesFrom(long requested) {
        var causes = EnumSet.noneOf(RemovalCause.class);

        if (requested < firstHeld) {
            causes.add(RemovalCause.LIFE_LOST);
        }

        if (requested < floor) {
            causes.addAll(StreamError.CursorExpired.causesOf(dropped));
        }

        Arrays.stream(runs).filter(run -> endOf(run) > requested).forEach(run -> causes.add(causeOf(run)));

        return causes;
    }

    private RemovalLedger withRun(long to, RemovalCause cause) {
        return extendsLast(cause)
               ? new RemovalLedger(firstHeld, floor, dropped, merged(to, cause), to)
               : runs.length < MAX_RUNS
                 ? new RemovalLedger(firstHeld, floor, dropped, appended(to, cause), to)
                 : droppingLowest(to, cause);
    }

    private boolean extendsLast(RemovalCause cause) {
        return runs.length > 0 && causeOf(runs[runs.length - 1]) == cause;
    }

    private long[] merged(long to, RemovalCause cause) {
        var next = runs.clone();

        next[next.length - 1] = packed(to, cause);

        return next;
    }

    private long[] appended(long to, RemovalCause cause) {
        var next = Arrays.copyOf(runs, runs.length + 1);

        next[runs.length] = packed(to, cause);

        return next;
    }

    private RemovalLedger droppingLowest(long to, RemovalCause cause) {
        var next = Arrays.copyOfRange(runs, 1, runs.length + 1);

        next[runs.length - 1] = packed(to, cause);

        return new RemovalLedger(firstHeld, endOf(runs[0]), dropped | (1 << causeOf(runs[0]).ordinal()), next, to);
    }

    private static long packed(long end, RemovalCause cause) {
        return (end << CAUSE_BITS) | cause.ordinal();
    }

    private static long endOf(long run) {
        return run >>> CAUSE_BITS;
    }

    private static RemovalCause causeOf(long run) {
        return CAUSES[(int)(run & CAUSE_MASK)];
    }
}
