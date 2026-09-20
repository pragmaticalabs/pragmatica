// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.MetadataStore;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentIndexTest {

    private static final String STREAM = "test-stream";
    private static final int PARTITION = 0;

    private SegmentIndex index;

    @BeforeEach
    void setUp() {
        index = new SegmentIndex();
    }

    @Nested
    class FindSegment {

        @Test
        void addSegment_findSegment_returnsCorrect() {
            index.addSegment(STREAM, PARTITION, 0, 9);

            var result = index.findSegment(STREAM, PARTITION, 0);

            assertThat(result.isEmpty()).isFalse();
            result.onPresent(ref -> assertSegmentRef(ref, 0, 9));
        }

        @Test
        void findSegment_middleOfRange_returnsContainingSegment() {
            index.addSegment(STREAM, PARTITION, 10, 19);

            var result = index.findSegment(STREAM, PARTITION, 15);

            assertThat(result.isEmpty()).isFalse();
            result.onPresent(ref -> assertSegmentRef(ref, 10, 19));
        }

        @Test
        void findSegment_beforeAll_returnsNone() {
            index.addSegment(STREAM, PARTITION, 10, 19);

            var result = index.findSegment(STREAM, PARTITION, 5);

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void findSegment_afterAll_returnsNone() {
            index.addSegment(STREAM, PARTITION, 10, 19);

            var result = index.findSegment(STREAM, PARTITION, 25);

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void findSegment_unknownStream_returnsNone() {
            index.addSegment(STREAM, PARTITION, 0, 9);

            var result = index.findSegment("other-stream", PARTITION, 5);

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void findSegment_exactEndOffset_returnsSegment() {
            index.addSegment(STREAM, PARTITION, 10, 19);

            var result = index.findSegment(STREAM, PARTITION, 19);

            assertThat(result.isEmpty()).isFalse();
        }
    }

    @Nested
    class SegmentRange {

        @Test
        void segmentRange_multipleSegments_returnsAll() {
            index.addSegment(STREAM, PARTITION, 0, 9);
            index.addSegment(STREAM, PARTITION, 10, 19);
            index.addSegment(STREAM, PARTITION, 20, 29);

            var result = index.segmentRange(STREAM, PARTITION, 0, 29);

            assertThat(result).hasSize(3);
        }

        @Test
        void segmentRange_partialOverlap_returnsOverlapping() {
            index.addSegment(STREAM, PARTITION, 0, 9);
            index.addSegment(STREAM, PARTITION, 10, 19);
            index.addSegment(STREAM, PARTITION, 20, 29);

            var result = index.segmentRange(STREAM, PARTITION, 5, 15);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).startOffset()).isEqualTo(0);
            assertThat(result.get(1).startOffset()).isEqualTo(10);
        }

        @Test
        void segmentRange_noSegments_returnsEmpty() {
            var result = index.segmentRange(STREAM, PARTITION, 0, 100);

            assertThat(result).isEmpty();
        }

        @Test
        void segmentRange_differentPartitions_isolated() {
            index.addSegment(STREAM, 0, 0, 9);
            index.addSegment(STREAM, 1, 0, 9);

            var result = index.segmentRange(STREAM, 0, 0, 9);

            assertThat(result).hasSize(1);
        }
    }

    /// #1234: the sealed watermark drives WAL truncation and recovery seeding, both of which discard
    /// everything at or below it — so it must be CONTIGUOUS (every offset at or below it sealed), never
    /// the maximum `endOffset`, which a later successful seal pushes past a failed one.
    @Nested
    class ContiguousSealedWatermark {

        @Test
        void lastSealedOffset_stopsBelowHole_whenLaterSegmentSealed() {
            index.addSegment(STREAM, PARTITION, 0, 99);
            index.addSegment(STREAM, PARTITION, 200, 299);

            assertThat(index.lastSealedOffset(STREAM, PARTITION)).isEqualTo(99L);
        }

        @Test
        void lastSealedOffset_isMinusOne_whenFirstSegmentMissing() {
            index.addSegment(STREAM, PARTITION, 100, 199);

            assertThat(index.lastSealedOffset(STREAM, PARTITION)).isEqualTo(-1L);
        }

        @Test
        void lastSealedOffset_advancesThroughLaterSegments_whenHoleIsSealed() {
            index.addSegment(STREAM, PARTITION, 0, 99);
            index.addSegment(STREAM, PARTITION, 200, 299);
            index.addSegment(STREAM, PARTITION, 100, 199);

            assertThat(index.lastSealedOffset(STREAM, PARTITION)).isEqualTo(299L);
        }

        /// Retention reclaiming a sealed segment does not un-seal it: lowering the watermark would let
        /// recovery seed a ring below a WAL that was already truncated past it.
        @Test
        void lastSealedOffset_doesNotRegress_whenSealedSegmentRemoved() {
            index.addSegment(STREAM, PARTITION, 0, 99);
            index.addSegment(STREAM, PARTITION, 100, 199);
            index.removeSegment(STREAM, PARTITION, 0);
            index.removeSegment(STREAM, PARTITION, 100);

            assertThat(index.lastSealedOffset(STREAM, PARTITION)).isEqualTo(199L);
        }

        /// After a restart only surviving refs are known, and a prefix reclaimed by retention cannot be told
        /// apart from one never sealed — so the rebuilt watermark is anchored at the lowest surviving ref and
        /// still stops at the first hole above it.
        @Test
        void rebuildFromRefs_anchorsAtLowestSurvivingRef_andStopsAtHole() {
            var store = MetadataStore.inMemoryMetadataStore("rebuild");
            var block = BlockId.blockId(new byte[]{1}).unwrap();

            store.putRef("streams/" + STREAM + "/" + PARTITION + "/100-199", block);
            store.putRef("streams/" + STREAM + "/" + PARTITION + "/200-299", block);
            store.putRef("streams/" + STREAM + "/" + PARTITION + "/400-499", block);

            index.rebuildFromRefs(store);

            assertThat(index.lastSealedOffset(STREAM, PARTITION)).isEqualTo(299L);
        }
    }

    /// #1352: a range DROP_OLDEST reclaimed WITHOUT a seal — its events were never acknowledged, so they are not
    /// in the log — counts towards the watermark exactly as a sealed segment does. Otherwise the drop would
    /// pin the watermark below it forever: WAL truncation would stop for the partition and a read from the
    /// dropped offset would read as a FAILED seal (`SealedRangeMissing`) instead of reclaimed history.
    @Nested
    class ReclaimedWithoutSeal {

        @Test
        void markReclaimed_advancesTheWatermark_asASealWould() {
            index.markReclaimed(STREAM, PARTITION, 0, 0);

            assertThat(index.lastSealedOffset(STREAM, PARTITION)).isEqualTo(0L);

            index.addSegment(STREAM, PARTITION, 1, 5);

            assertThat(index.lastSealedOffset(STREAM, PARTITION)).isEqualTo(5L);
        }

        /// The seal below the drop was still in flight when the drop was reported: the range is kept and
        /// counted once that seal lands.
        @Test
        void markReclaimed_aheadOfAPendingSeal_isCountedWhenTheSealLands() {
            index.markReclaimed(STREAM, PARTITION, 3, 4);

            assertThat(index.lastSealedOffset(STREAM, PARTITION)).as("offsets 0-2 are neither sealed nor reclaimed").isEqualTo(-1L);

            index.addSegment(STREAM, PARTITION, 0, 2);

            assertThat(index.lastSealedOffset(STREAM, PARTITION)).as("the seal bridges to the reclaimed range").isEqualTo(4L);
        }

        @Test
        void markReclaimed_bridgesTwoSealedRuns() {
            index.addSegment(STREAM, PARTITION, 0, 2);
            index.addSegment(STREAM, PARTITION, 5, 9);

            assertThat(index.lastSealedOffset(STREAM, PARTITION)).isEqualTo(2L);

            index.markReclaimed(STREAM, PARTITION, 3, 4);

            assertThat(index.lastSealedOffset(STREAM, PARTITION)).isEqualTo(9L);
        }

        /// The reader's contiguous run is segments only: a read must stop at the reclaimed range, so that the
        /// next read starts inside it and is told the offset expired instead of skipping it.
        @Test
        void reclaimedRange_isNotPartOfTheReadableRun_andReadsAsReclaimed() {
            index.addSegment(STREAM, PARTITION, 0, 2);
            index.markReclaimed(STREAM, PARTITION, 3, 4);
            index.addSegment(STREAM, PARTITION, 5, 9);

            assertThat(index.contiguousSealedEnd(STREAM, PARTITION, 0)).as("the read stops before the reclaimed range").isEqualTo(Option.some(2L));
            assertThat(index.contiguousSealedEnd(STREAM, PARTITION, 3)).as("no segment holds a reclaimed offset").isEqualTo(Option.none());
            assertThat(index.nextSealedOffset(STREAM, PARTITION, 3)).isEqualTo(Option.some(5L));
            assertThat(index.lastSealedOffset(STREAM, PARTITION)).as("at or below the watermark ⇒ the reader reports CursorExpired, not SealedRangeMissing")
                                                                 .isGreaterThanOrEqualTo(3L);
        }

        @Test
        void rebuildFromRefs_forgetsReclaimedRanges() {
            index.markReclaimed(STREAM, PARTITION, 0, 0);
            var store = MetadataStore.inMemoryMetadataStore("rebuild-reclaimed");
            var block = BlockId.blockId(new byte[]{1}).unwrap();

            store.putRef("streams/" + STREAM + "/" + PARTITION + "/1-5", block);
            index.rebuildFromRefs(store);

            assertThat(index.lastSealedOffset(STREAM, PARTITION)).as("anchored at the lowest surviving ref, as for any reclaimed prefix")
                                                                 .isEqualTo(5L);
        }
    }

    private void assertSegmentRef(SegmentIndex.SegmentRef ref, long expectedStart, long expectedEnd) {
        assertThat(ref.startOffset()).isEqualTo(expectedStart);
        assertThat(ref.endOffset()).isEqualTo(expectedEnd);
    }
}
