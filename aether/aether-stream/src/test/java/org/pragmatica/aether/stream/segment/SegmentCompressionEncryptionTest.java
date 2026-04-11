package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.BlockEncryptor;
import org.pragmatica.storage.Compression;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.SegmentReader.segmentReader;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


class SegmentCompressionEncryptionTest {

    private static final String STREAM = "test-stream";
    private static final int PARTITION = 0;
    private static final long ONE_GB = 1024 * 1024 * 1024L;

    private StorageInstance storage;
    private SegmentIndex index;

    @BeforeEach
    void setUp() {
        storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(ONE_GB)));
        index = new SegmentIndex();
    }

    @Nested
    class Lz4Compression {

        @Test
        void roundTrip_lz4_preservesData() {
            var codec = Compression.LZ4.codec();
            var sink = storageSegmentSink(storage, index, codec, Compression.LZ4.ordinal(), none());
            var reader = segmentReader(storage, index);

            var events = testEvents();
            var serialized = serializeEvents(events);
            var segment = sealedSegment(STREAM, PARTITION, 0, 2, 3, 100L, 300L, serialized);

            sink.seal(segment).await();

            var result = reader.readEvents(STREAM, PARTITION, 0, 10).await();
            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(list -> assertEventsMatch(list, events));
        }

        @Test
        void segmentRef_recordsCompressionMetadata() {
            var codec = Compression.LZ4.codec();
            var sink = storageSegmentSink(storage, index, codec, Compression.LZ4.ordinal(), none());

            var serialized = serializeEvents(testEvents());
            var segment = sealedSegment(STREAM, PARTITION, 0, 2, 3, 100L, 300L, serialized);

            sink.seal(segment).await();

            var ref = index.findSegment(STREAM, PARTITION, 0);
            assertThat(ref.isEmpty()).isFalse();
            ref.onPresent(r -> {
                assertThat(r.compressionOrdinal()).isEqualTo(Compression.LZ4.ordinal());
                assertThat(r.originalSize()).isEqualTo(serialized.length);
                assertThat(r.encrypted()).isFalse();
            });
        }
    }

    @Nested
    class ZstdCompression {

        @Test
        void roundTrip_zstd_preservesData() {
            var codec = Compression.ZSTD.codec();
            var sink = storageSegmentSink(storage, index, codec, Compression.ZSTD.ordinal(), none());
            var reader = segmentReader(storage, index);

            var events = testEvents();
            var serialized = serializeEvents(events);
            var segment = sealedSegment(STREAM, PARTITION, 0, 2, 3, 100L, 300L, serialized);

            sink.seal(segment).await();

            var result = reader.readEvents(STREAM, PARTITION, 0, 10).await();
            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(list -> assertEventsMatch(list, events));
        }
    }

    @Nested
    class AesGcmEncryption {

        @Test
        void roundTrip_encryptDecrypt_preservesData() {
            var key = new byte[32];
            for (int i = 0; i < 32; i++) {key[i] = (byte) i;}
            var encryptor = BlockEncryptor.aesGcm(key, "test-key");
            assertThat(encryptor.isSuccess()).isTrue();

            var enc = some(encryptor.unwrap());
            var sink = storageSegmentSink(storage, index, Compression.NONE.codec(), 0, enc);
            var reader = segmentReader(storage, index, enc);

            var events = testEvents();
            var serialized = serializeEvents(events);
            var segment = sealedSegment(STREAM, PARTITION, 0, 2, 3, 100L, 300L, serialized);

            sink.seal(segment).await();

            var result = reader.readEvents(STREAM, PARTITION, 0, 10).await();
            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(list -> assertEventsMatch(list, events));
        }

        @Test
        void segmentRef_recordsEncryptionMetadata() {
            var key = new byte[32];
            for (int i = 0; i < 32; i++) {key[i] = (byte) i;}
            var enc = some(BlockEncryptor.aesGcm(key, "test-key").unwrap());
            var sink = storageSegmentSink(storage, index, Compression.NONE.codec(), 0, enc);

            var serialized = serializeEvents(testEvents());
            var segment = sealedSegment(STREAM, PARTITION, 0, 2, 3, 100L, 300L, serialized);

            sink.seal(segment).await();

            var ref = index.findSegment(STREAM, PARTITION, 0);
            assertThat(ref.isEmpty()).isFalse();
            ref.onPresent(r -> assertThat(r.encrypted()).isTrue());
        }
    }

    @Nested
    class CompressionAndEncryption {

        @Test
        void roundTrip_lz4PlusAes_preservesData() {
            var key = new byte[32];
            for (int i = 0; i < 32; i++) {key[i] = (byte) i;}
            var enc = some(BlockEncryptor.aesGcm(key, "test-key").unwrap());
            var codec = Compression.LZ4.codec();
            var sink = storageSegmentSink(storage, index, codec, Compression.LZ4.ordinal(), enc);
            var reader = segmentReader(storage, index, enc);

            var events = testEvents();
            var serialized = serializeEvents(events);
            var segment = sealedSegment(STREAM, PARTITION, 0, 2, 3, 100L, 300L, serialized);

            sink.seal(segment).await();

            var result = reader.readEvents(STREAM, PARTITION, 0, 10).await();
            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(list -> assertEventsMatch(list, events));
        }

        @Test
        void roundTrip_zstdPlusAes_preservesData() {
            var key = new byte[32];
            for (int i = 0; i < 32; i++) {key[i] = (byte) i;}
            var enc = some(BlockEncryptor.aesGcm(key, "test-key").unwrap());
            var codec = Compression.ZSTD.codec();
            var sink = storageSegmentSink(storage, index, codec, Compression.ZSTD.ordinal(), enc);
            var reader = segmentReader(storage, index, enc);

            var events = testEvents();
            var serialized = serializeEvents(events);
            var segment = sealedSegment(STREAM, PARTITION, 0, 2, 3, 100L, 300L, serialized);

            sink.seal(segment).await();

            var result = reader.readEvents(STREAM, PARTITION, 0, 10).await();
            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(list -> assertEventsMatch(list, events));
        }

        @Test
        void segmentRef_recordsBothMetadata() {
            var key = new byte[32];
            for (int i = 0; i < 32; i++) {key[i] = (byte) i;}
            var enc = some(BlockEncryptor.aesGcm(key, "test-key").unwrap());
            var codec = Compression.ZSTD.codec();
            var sink = storageSegmentSink(storage, index, codec, Compression.ZSTD.ordinal(), enc);

            var serialized = serializeEvents(testEvents());
            var segment = sealedSegment(STREAM, PARTITION, 0, 2, 3, 100L, 300L, serialized);

            sink.seal(segment).await();

            var ref = index.findSegment(STREAM, PARTITION, 0);
            assertThat(ref.isEmpty()).isFalse();
            ref.onPresent(r -> {
                assertThat(r.compressionOrdinal()).isEqualTo(Compression.ZSTD.ordinal());
                assertThat(r.encrypted()).isTrue();
                assertThat(r.originalSize()).isEqualTo(serialized.length);
            });
        }
    }

    @Nested
    class BackwardCompatibility {

        @Test
        void noCompressionNoEncryption_preservesData() {
            var sink = storageSegmentSink(storage, index);
            var reader = segmentReader(storage, index);

            var events = testEvents();
            var serialized = serializeEvents(events);
            var segment = sealedSegment(STREAM, PARTITION, 0, 2, 3, 100L, 300L, serialized);

            sink.seal(segment).await();

            var result = reader.readEvents(STREAM, PARTITION, 0, 10).await();
            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(list -> assertEventsMatch(list, events));
        }

        @Test
        void segmentRef_defaultsToNoCompression() {
            var sink = storageSegmentSink(storage, index);

            var serialized = serializeEvents(testEvents());
            var segment = sealedSegment(STREAM, PARTITION, 0, 2, 3, 100L, 300L, serialized);

            sink.seal(segment).await();

            var ref = index.findSegment(STREAM, PARTITION, 0);
            assertThat(ref.isEmpty()).isFalse();
            ref.onPresent(r -> {
                assertThat(r.compressionOrdinal()).isEqualTo(0);
                assertThat(r.encrypted()).isFalse();
                assertThat(r.originalSize()).isEqualTo(serialized.length);
            });
        }
    }

    private static List<RawEvent> testEvents() {
        return List.of(
            RawEvent.rawEvent(0L, "hello-world-event-data-one".getBytes(), 100L),
            RawEvent.rawEvent(1L, "hello-world-event-data-two".getBytes(), 200L),
            RawEvent.rawEvent(2L, "hello-world-event-data-three".getBytes(), 300L)
        );
    }

    private static void assertEventsMatch(List<RawEvent> actual, List<RawEvent> expected) {
        assertThat(actual).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertThat(actual.get(i).offset()).isEqualTo(expected.get(i).offset());
            assertThat(actual.get(i).data()).isEqualTo(expected.get(i).data());
            assertThat(actual.get(i).timestamp()).isEqualTo(expected.get(i).timestamp());
        }
    }

    private static byte[] serializeEvents(List<RawEvent> events) {
        var totalSize = events.stream()
                              .mapToInt(e -> Long.BYTES + Long.BYTES + Integer.BYTES + e.data().length)
                              .sum();
        var buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN);

        for (var event : events) {
            buffer.putLong(event.offset());
            buffer.putLong(event.timestamp());
            buffer.putInt(event.data().length);
            buffer.put(event.data());
        }

        return buffer.array();
    }
}
