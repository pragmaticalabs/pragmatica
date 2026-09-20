// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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

    private void assertSegmentRef(SegmentIndex.SegmentRef ref, long expectedStart, long expectedEnd) {
        assertThat(ref.startOffset()).isEqualTo(expectedStart);
        assertThat(ref.endOffset()).isEqualTo(expectedEnd);
    }
}
