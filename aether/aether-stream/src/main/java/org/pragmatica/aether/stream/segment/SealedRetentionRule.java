// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.lang.Option;

/// How many of a partition's oldest sealed segments retention may reclaim (#2201 Q6 = B, know 59dfb9e86c; Q13 = A, know
/// caf701869e: count and bytes are measured over the sealed tier).
///
/// Each segment is judged on what is NEWER than it, never on per-partition totals: a finite count or byte limit then
/// reclaims the oldest segments that exceed it and no others, where a per-partition total judged every segment the same
/// and so reclaimed all of them together.
///   - count: the events of the newer segments, by offset span (a gap between segments counts nothing), is at least
///     `maxCount`;
///   - bytes: the recorded sizes of the newer segments (an unknown size counts 0, which can only delay) is at least
///     `maxBytes`;
///   - age: `now` minus the segment's newest event timestamp exceeds `maxAgeMs`. An unknown timestamp is never old. A
///     segment whose age cannot be read on any pass (`unreadable`) takes the age of the next newer segment whose age is
///     known, so it neither holds the prefix for ever nor goes before a newer segment does.
///
/// Only the DECLARED dimensions combine: `ANY` reclaims when one is exceeded, `ALL` when every one is; an undeclared
/// dimension neither reclaims nor blocks. `UNKNOWN` reclaims nothing (#964). Only a PREFIX goes, and only segments that
/// end at or below `deletableThrough`: the first segment that may not go stops it, so retention never opens a hole.
public final class SealedRetentionRule {
    private SealedRetentionRule() {}

    public static int reclaimablePrefix(List<SegmentIndex.SegmentRef> segments,
                                        RetentionPolicy policy,
                                        long deletableThrough,
                                        long now,
                                        Set<Long> unreadable) {
        if (policy.mode() == RetentionMode.UNKNOWN || !policy.declared().any()) {
            return 0;
        }

        var size = segments.size();
        var newerEvents = new long[size];
        var newerBytes = new long[size];
        var ages = new long[size];
        var events = 0L;
        var bytes = 0L;
        var newerKnownAge = Long.MIN_VALUE;

        for (var i = size - 1; i >= 0; i--) {
            var segment = segments.get(i);

            newerEvents[i] = events;
            newerBytes[i] = bytes;
            ages[i] = ageOf(segment, now, unreadable, newerKnownAge);
            newerKnownAge = knownAge(segment, now).or(newerKnownAge);
            events = saturatedAdd(events, segment.endOffset() - segment.startOffset() + 1);
            bytes = saturatedAdd(bytes, Math.max(0, segment.originalSize()));
        }

        var prefix = 0;

        while (prefix < size
               && segments.get(prefix).endOffset() <= deletableThrough
               && reclaimable(policy, newerEvents[prefix], newerBytes[prefix], ages[prefix])) {
            prefix++;
        }

        return prefix;
    }

    private static boolean reclaimable(RetentionPolicy policy, long newerEvents, long newerBytes, long age) {
        var declared = policy.declared();
        var countExceeded = newerEvents >= policy.maxCount();
        var bytesExceeded = newerBytes >= policy.maxBytes();
        var ageExceeded = age != Long.MIN_VALUE && age > policy.maxAgeMs();

        return policy.mode() == RetentionMode.ALL
               ? all(declared, countExceeded, bytesExceeded, ageExceeded)
               : any(declared, countExceeded, bytesExceeded, ageExceeded);
    }

    private static boolean any(RetentionDimensions declared, boolean count, boolean bytes, boolean age) {
        return (declared.count() && count) || (declared.bytes() && bytes) || (declared.age() && age);
    }

    private static boolean all(RetentionDimensions declared, boolean count, boolean bytes, boolean age) {
        return (!declared.count() || count) && (!declared.bytes() || bytes) && (!declared.age() || age);
    }

    /// `Long.MIN_VALUE` stands for "not known", which is never old.
    private static long ageOf(SegmentIndex.SegmentRef segment, long now, Set<Long> unreadable, long newerKnownAge) {
        return knownAge(segment, now).or(unreadable.contains(segment.startOffset())
                                         ? newerKnownAge
                                         : Long.MIN_VALUE);
    }

    private static Option<Long> knownAge(SegmentIndex.SegmentRef segment, long now) {
        return segment.maxTimestamp() > 0
               ? Option.some(now - segment.maxTimestamp())
               : Option.none();
    }

    private static long saturatedAdd(long left, long right) {
        var sum = left + right;

        return sum < left ? Long.MAX_VALUE : sum;
    }
}
