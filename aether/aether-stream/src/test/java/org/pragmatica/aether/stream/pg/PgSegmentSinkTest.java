package org.pragmatica.aether.stream.pg;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.pg.PgSegmentSink.pgSegmentSink;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.lang.Unit.unit;


class PgSegmentSinkTest {

    private static final String STREAM = "test-stream";
    private static final int PARTITION = 0;

    private RecordingPgStreamStore recording;
    private PgSegmentSink sink;

    @BeforeEach
    void setUp() {
        recording = new RecordingPgStreamStore();
        sink = pgSegmentSink(recording);
    }

    @Nested
    class Seal {

        @Test
        void seal_delegatesToStoreSegment() {
            var data = new byte[]{1, 2, 3};
            var segment = sealedSegment(STREAM, PARTITION, 0, 9, 10, 1000L, 2000L, data);

            var result = sink.seal(segment).await();

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));
            assertThat(recording.lastStreamName.get()).isEqualTo(STREAM);
            assertThat(recording.lastPartition.get()).isEqualTo(PARTITION);
            assertThat(recording.lastStartOffset.get()).isEqualTo(0L);
            assertThat(recording.lastEndOffset.get()).isEqualTo(9L);
            assertThat(recording.lastData.get()).isEqualTo(data);
        }

        @Test
        void seal_passesCorrectOffsets() {
            var segment = sealedSegment(STREAM, 3, 100, 199, 100, 5000L, 6000L, new byte[]{42});

            sink.seal(segment).await();

            assertThat(recording.lastPartition.get()).isEqualTo(3);
            assertThat(recording.lastStartOffset.get()).isEqualTo(100L);
            assertThat(recording.lastEndOffset.get()).isEqualTo(199L);
        }
    }

    /// Recording stub that captures call parameters.
    static final class RecordingPgStreamStore implements PgStreamStore {
        final AtomicReference<String> lastStreamName = new AtomicReference<>();
        final AtomicReference<Integer> lastPartition = new AtomicReference<>();
        final AtomicReference<Long> lastStartOffset = new AtomicReference<>();
        final AtomicReference<Long> lastEndOffset = new AtomicReference<>();
        final AtomicReference<byte[]> lastData = new AtomicReference<>();

        @Override
        public Promise<Unit> storeSegment(String streamName, int partition, long startOffset, long endOffset, byte[] data) {
            lastStreamName.set(streamName);
            lastPartition.set(partition);
            lastStartOffset.set(startOffset);
            lastEndOffset.set(endOffset);
            lastData.set(data);
            return Promise.success(unit());
        }

        @Override
        public Promise<Option<byte[]>> readSegment(String streamName, int partition, long startOffset) {
            return Promise.success(Option.none());
        }

        @Override
        public Promise<Integer> deleteExpired(String streamName, Instant cutoff) {
            return Promise.success(0);
        }

        @Override
        public Promise<Unit> commitCursor(String consumerGroup, String streamName, int partition, long offset) {
            return Promise.success(unit());
        }

        @Override
        public Promise<Option<Long>> fetchCursor(String consumerGroup, String streamName, int partition) {
            return Promise.success(Option.none());
        }
    }
}
