package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

    private void assertSegmentRef(SegmentIndex.SegmentRef ref, long expectedStart, long expectedEnd) {
        assertThat(ref.startOffset()).isEqualTo(expectedStart);
        assertThat(ref.endOffset()).isEqualTo(expectedEnd);
    }
}
