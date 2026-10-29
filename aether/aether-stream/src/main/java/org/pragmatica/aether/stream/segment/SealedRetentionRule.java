// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.slice.RetentionPolicy;

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

    /// RED stub (#2201 PR-0b part 1, first commit): reclaims nothing. The rule lands in the next commit.
    public static int reclaimablePrefix(List<SegmentIndex.SegmentRef> segments,
                                        RetentionPolicy policy,
                                        long deletableThrough,
                                        long now,
                                        Set<Long> unreadable) {
        return 0;
    }
}
