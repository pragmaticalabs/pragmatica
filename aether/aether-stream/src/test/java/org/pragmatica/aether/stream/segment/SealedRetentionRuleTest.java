// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;

import static org.assertj.core.api.Assertions.assertThat;

/// #2201 3.2 item 3 (Q6 = B, Q13 = A): the sealed-tier rule judges each segment on what is newer than it, over the
/// dimensions the stream declared, and reclaims only a prefix.
class SealedRetentionRuleTest {
    private static final long NOW = 1_000_000_000L;
    private static final long HOUR = 3_600_000L;
    private static final long UNBOUNDED = Long.MAX_VALUE;

    private static SegmentIndex.SegmentRef segment(long start, long end, long maxTimestamp, int size) {
        return SegmentIndex.SegmentRef.segmentRef(start, end, maxTimestamp, 0, false, size);
    }

    private static SegmentIndex.SegmentRef fresh(long start, long end) {
        return segment(start, end, NOW, 0);
    }

    private static RetentionPolicy count(long maxCount) {
        return RetentionPolicy.retentionPolicy(maxCount, UNBOUNDED, UNBOUNDED).withDeclared(RetentionDimensions.COUNT);
    }

    private static int prefix(List<SegmentIndex.SegmentRef> segments, RetentionPolicy policy) {
        return SealedRetentionRule.reclaimablePrefix(segments, policy, UNBOUNDED, NOW, Set.of());
    }

    /// NT-115: count is measured in events by offset span, never in segments.
    @Nested
    class CountBySpan {
        @Test
        void tenEventSegments_withMaxCount15_reclaimOnlyTheOldest() {
            var segments = List.of(fresh(0, 9), fresh(10, 19), fresh(20, 29));

            assertThat(prefix(segments, count(15))).isEqualTo(1);
        }

        @Test
        void aSegmentOfManyEvents_countsItsEvents() {
            var segments = List.of(fresh(0, 0), fresh(1, 4096));

            assertThat(prefix(segments, count(4096))).as("4,096 newer events satisfy max_count 4,096").isEqualTo(1);
            assertThat(prefix(segments, count(4097))).as("measured in segments it would be 1, never 4,097").isZero();
        }

        @Test
        void aGapBetweenSegments_countsNothing() {
            var segments = List.of(fresh(0, 9), fresh(1000, 1009));

            assertThat(prefix(segments, count(11))).as("the 990 offsets of the gap are not events").isZero();
        }
    }

    /// NT-116: today's code judged every segment on the partition's totals, so a finite count reclaimed all of them.
    @Test
    void sharedTotals_designedOut_hundredOneEventSegmentsWithMaxCount50_reclaimExactlyTheOldest50() {
        var segments = LongStream.range(0, 100).mapToObj(offset -> fresh(offset, offset)).toList();

        assertThat(prefix(segments, count(50))).isEqualTo(50);
    }

    /// NT-115: bytes by suffix sums of the recorded sizes; at least `max_bytes` stays, and less than that plus the oldest
    /// segment kept.
    @Test
    void bytes_leaveAtLeastMaxBytes_andLessThanThatPlusTheOldestKept() {
        var segments = LongStream.range(0, 10).mapToObj(offset -> segment(offset, offset, NOW, 100)).toList();
        var policy = RetentionPolicy.retentionPolicy(UNBOUNDED, 350, UNBOUNDED).withDeclared(RetentionDimensions.BYTES);
        var kept = 10 - prefix(segments, policy);

        assertThat(kept * 100L).isGreaterThanOrEqualTo(350L).isLessThan(350L + 100L);
    }

    /// NT-117 (rule half): a size not yet learned counts 0, so it can only delay a reclaim.
    @Test
    void bytes_anUnknownSizeCountsNothing() {
        var segments = List.of(segment(0, 0, NOW, 100), segment(1, 1, NOW, 0), segment(2, 2, NOW, 0));
        var policy = RetentionPolicy.retentionPolicy(UNBOUNDED, 1, UNBOUNDED).withDeclared(RetentionDimensions.BYTES);

        assertThat(prefix(segments, policy)).isZero();
    }

    /// NT-115: age is not monotone; a young segment stops the prefix although an older-timestamped one follows it, so no
    /// hole opens.
    @Test
    void age_aYoungSegmentStopsThePrefix() {
        var old = NOW - 3 * HOUR;
        var segments = List.of(segment(0, 0, old, 0), segment(1, 1, NOW, 0), segment(2, 2, old, 0));
        var policy = RetentionPolicy.retentionPolicy(UNBOUNDED, UNBOUNDED, HOUR).withDeclared(RetentionDimensions.AGE);

        assertThat(prefix(segments, policy)).isEqualTo(1);
    }

    @Test
    void age_anUnknownTimestampIsNeverOld() {
        var segments = List.of(segment(0, 0, 0L, 0), segment(1, 1, NOW - 3 * HOUR, 0));
        var policy = RetentionPolicy.retentionPolicy(UNBOUNDED, UNBOUNDED, HOUR).withDeclared(RetentionDimensions.AGE);

        assertThat(prefix(segments, policy)).isZero();
    }

    /// NT-115: the entity floor (and the contiguous sealed watermark) caps the prefix; a straddling segment is kept whole.
    @Test
    void deletableThrough_capsThePrefix() {
        var segments = List.of(fresh(0, 9), fresh(10, 19), fresh(20, 29), fresh(30, 39));

        assertThat(SealedRetentionRule.reclaimablePrefix(segments, count(0), 15, NOW, Set.of())).isEqualTo(1);
    }

    /// NT-105: the mode combines the DECLARED dimensions only.
    @Nested
    class ModeOverTheDeclaredSet {
        private final List<SegmentIndex.SegmentRef> segments = List.of(segment(0, 9, NOW - 3 * HOUR, 0), segment(10, 19, NOW, 0));

        @Test
        void unknown_withholds() {
            var policy = RetentionPolicy.retentionPolicy(0, 0, 0, RetentionMode.UNKNOWN)
                                        .withDeclared(RetentionDimensions.retentionDimensions(true, true, true));

            assertThat(prefix(segments, policy)).isZero();
        }

        @Test
        void any_reclaimsWhenOneDeclaredDimensionIsExceeded() {
            var policy = RetentionPolicy.retentionPolicy(1_000, UNBOUNDED, HOUR, RetentionMode.ANY)
                                        .withDeclared(RetentionDimensions.retentionDimensions(true, false, true));

            assertThat(prefix(segments, policy)).as("age exceeded, count not").isEqualTo(1);
        }

        @Test
        void all_needsEveryDeclaredDimension() {
            var policy = RetentionPolicy.retentionPolicy(1_000, UNBOUNDED, HOUR, RetentionMode.ALL)
                                        .withDeclared(RetentionDimensions.retentionDimensions(true, false, true));

            assertThat(prefix(segments, policy)).as("age exceeded, count not").isZero();
        }

        /// A `time` stream carries the default count and bytes it never declared; under `ALL` it reclaims at its age
        /// alone, where `exceedsAllConfiguredLimits` would also need 100,000 events and 256 MiB.
        @Test
        void all_anUndeclaredDimensionAtItsDefaultNeitherReclaimsNorBlocks() {
            var defaults = RetentionPolicy.retentionPolicy();
            var timeStream = RetentionPolicy.retentionPolicy(defaults.maxCount(), defaults.maxBytes(), HOUR, RetentionMode.ALL)
                                            .withDeclared(RetentionDimensions.AGE);

            assertThat(prefix(segments, timeStream)).isEqualTo(1);
        }

        @Test
        void nothingDeclared_reclaimsNothing() {
            assertThat(prefix(segments, RetentionPolicy.retentionPolicy(0, 0, 0))).isZero();
        }

        /// Under `ALL` an empty declared set would make "every declared dimension is exceeded" vacuously true and reclaim
        /// the whole history; the rule's own guard refuses it, whatever its caller substitutes (F4, r2).
        @Test
        void nothingDeclared_underAll_reclaimsNothing() {
            assertThat(prefix(segments, RetentionPolicy.retentionPolicy(0, 0, 0, RetentionMode.ALL))).isZero();
        }
    }

    /// NT-134 (rule half): a segment whose age is deterministically unreadable takes the age of the next newer segment
    /// whose age is known, so it neither holds the prefix for ever nor goes before a newer segment does.
    @Nested
    class UnreadableAge {
        private final RetentionPolicy ageOnly = RetentionPolicy.retentionPolicy(UNBOUNDED, UNBOUNDED, HOUR)
                                                                .withDeclared(RetentionDimensions.AGE);

        @Test
        void takesTheNextNewerKnownAge_andThePrefixMovesPastIt() {
            var segments = List.of(segment(0, 0, 0L, 0), segment(1, 1, NOW - 3 * HOUR, 0), segment(2, 2, NOW, 0));

            assertThat(SealedRetentionRule.reclaimablePrefix(segments, ageOnly, UNBOUNDED, NOW, Set.of(0L))).isEqualTo(2);
        }

        @Test
        void neverGoesBeforeANewerSegment() {
            var segments = List.of(segment(0, 0, 0L, 0), segment(1, 1, NOW, 0));

            assertThat(SealedRetentionRule.reclaimablePrefix(segments, ageOnly, UNBOUNDED, NOW, Set.of(0L))).isZero();
        }
    }
}
