// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.provenance;

import java.util.stream.LongStream;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The divergence rule of spec #1569 §7.5.2 (AD7), as ONE pure function shared by cold-restart detection and
/// the promotion gate (#1596), so the two can never disagree about whether two copies of a partition log are
/// the same history.
///
/// `prov_X(o)`, for `o ≤ head_X`: **NONE** if `o < base_X`; the epoch of the last entry with `start ≤ o` if
/// `o ≥ firstStart_X`; otherwise **UNDEFINED**. Epochs are equal when they are the same [ProvenanceEpoch] (a real
/// epoch with the same incarnation ULID, a synthetic one with the same `d`), NONE equals NONE only for the same base, and **UNDEFINED equals nothing** -- not even UNDEFINED.
///
/// > a and b **diverge** iff there is an `o ∈ [0, min(head_a, head_b)]` with `prov_a(o) ≠ prov_b(o)`.
///
/// `prov` is constant between consecutive history boundaries, so the walk visits only `0`, the bases, and
/// every entry start of either copy within range -- bounded by history length, never by offset count, and
/// always from 0, never from the retained overlap (rev1569 R5-1: two copies whose retained ranges do not
/// overlap can still diverge below both).
///
/// Non-divergent implies prefix: each epoch has one writer and each offset is written once, so equal
/// provenance means equal payload `[unverified: no two owners share an epoch -- #1230, #1529; #1625]`.
///
/// `[unverified: #1648 -- the same owner can re-write an offset under the SAME epoch.]` The owner replicates an append
/// before its own fsync, and the ownership writer keeps the epoch when the HRW owner is unchanged, so after a fast
/// restart that loses the owner's unfsynced tail the owner writes that offset again, with different content, under the
/// same epoch. A replica that already holds the first write compares prov-equal: a false CONSISTENT this rule cannot
/// see. Pre-existing; the likely fix is that an owner restart mints a new epoch.
public sealed interface ProvenanceComparison {
    /// Why a lone copy cannot be compared at all (§7.5.3 reasons).
    enum Incompleteness {
        /// It holds offsets at or above its base but has no history.
        HISTORY_MISSING,
        /// Its history does not start at its base, so some offset `≤ head` has UNDEFINED provenance, or it
        /// still holds a record below its base (R7-1).
        HISTORY_INCOMPLETE
    }

    /// A copy is complete iff every offset in `[base, head]` has a defined provenance and it holds no record
    /// below its base. A copy holding no offset at or above its base is complete (there is nothing to vouch
    /// for).
    static Option<Incompleteness> incompleteness(LogProvenance copy) {
        if (holdsRecordBelowBase(copy)) {
            return some(Incompleteness.HISTORY_INCOMPLETE);
        }

        if (copy.head() < copy.base()) {
            return none();
        }

        return copy.history()
                   .isEmpty()
               ? some(Incompleteness.HISTORY_MISSING)
               : startsAtBase(copy);
    }

    /// Whether `a` and `b` diverge anywhere in `[0, min(head_a, head_b)]`.
    static boolean diverge(LogProvenance a, LogProvenance b) {
        return divergesWithin(a,
                              b,
                              0,
                              Math.min(a.head(), b.head()));
    }

    /// Whether `a` and `b` disagree at any offset of `[from, to]` -- the range form, for the backfill's N13
    /// check (the receiver's history against the source's slice over the receiver's own prefix).
    static boolean divergesWithin(LogProvenance a, LogProvenance b, long from, long to) {
        return firstDivergence(a, b, from, to).isPresent();
    }

    /// The first offset of `[from, to]` at which `a` and `b` disagree, if any.
    static Option<Long> firstDivergence(LogProvenance a, LogProvenance b, long from, long to) {
        return Option.from(boundaries(a, b, from, to).filter(offset -> !equal(prov(a, offset),
                                                                              prov(b, offset)))
                                     .boxed()
                                     .findFirst());
    }

    private static boolean holdsRecordBelowBase(LogProvenance copy) {
        return copy.low() < copy.base()
               && copy.low() >= 0
               && copy.low() <= copy.head();
    }

    private static Option<Incompleteness> startsAtBase(LogProvenance copy) {
        return copy.history()
                   .getFirst()
                   .startOffset() == copy.base()
               ? none()
               : some(Incompleteness.HISTORY_INCOMPLETE);
    }

    private static LongStream boundaries(LogProvenance a, LogProvenance b, long from, long to) {
        return LongStream.concat(LongStream.of(from,
                                               a.base(),
                                               b.base()),
                                 LongStream.concat(starts(a),
                                                   starts(b)))
                         .filter(offset -> offset >= from && offset <= to)
                         .sorted()
                         .distinct();
    }

    private static LongStream starts(LogProvenance copy) {
        return copy.history()
                   .stream()
                   .mapToLong(ProvenanceEntry::startOffset);
    }

    private static OffsetProvenance prov(LogProvenance copy, long offset) {
        if (offset < copy.base()) {
            return new OffsetProvenance.None(copy.base());
        }

        return copy.history()
                   .stream()
                   .filter(entry -> entry.startOffset() <= offset)
                   .reduce((earlier, later) -> later)
                   .<OffsetProvenance> map(OffsetProvenance.Owned::new)
                   .orElse(OffsetProvenance.Undefined.UNDEFINED);
    }

    private static boolean equal(OffsetProvenance left, OffsetProvenance right) {
        return switch (left) {
            case OffsetProvenance.None none -> right instanceof OffsetProvenance.None other && none.base() == other.base();
            case OffsetProvenance.Owned owned -> right instanceof OffsetProvenance.Owned other && sameDecision(owned.entry(),
                                                                                                               other.entry());
            case OffsetProvenance.Undefined _ -> false;
        };
    }

    private static boolean sameDecision(ProvenanceEntry left, ProvenanceEntry right) {
        return left.identified()
               && right.identified()
               && left.epoch()
                      .equals(right.epoch());
    }

    /// The provenance of one offset of one copy.
    sealed interface OffsetProvenance {
        record None(long base) implements OffsetProvenance {}

        record Owned(ProvenanceEntry entry) implements OffsetProvenance {}

        enum Undefined implements OffsetProvenance {
            UNDEFINED
        }
    }

    record unused() implements ProvenanceComparison {}
}
