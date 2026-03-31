package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

class StorageSegmentSinkTest {

    private static final String STREAM = "test-stream";
    private static final int PARTITION = 0;
    private static final long ONE_GB = 1024 * 1024 * 1024L;

    private StorageInstance storage;
    private SegmentIndex index;
    private StorageSegmentSink sink;

    @BeforeEach
    void setUp() {
        storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(ONE_GB)));
        index = new SegmentIndex();
        sink = storageSegmentSink(storage, index);
    }

    @Nested
    class Seal {

        @Test
        void seal_storesInStorage_andCreatesRef() {
            var segment = sealedSegment(STREAM, PARTITION, 0, 9, 10, 1000L, 2000L, new byte[]{1, 2, 3});

            sink.seal(segment).await();

            var refName = StorageSegmentSink.refName(segment);
            var blockId = storage.resolveRef(refName);
            assertThat(blockId.isEmpty()).isFalse();

            blockId.onPresent(id ->
                storage.get(id).await()
                       .onSuccess(opt -> opt.onPresent(data -> assertThat(data).isEqualTo(new byte[]{1, 2, 3})))
            );
        }

        @Test
        void seal_updatesIndex() {
            var segment = sealedSegment(STREAM, PARTITION, 10, 19, 10, 1000L, 2000L, new byte[]{4, 5});

            sink.seal(segment).await();

            var ref = index.findSegment(STREAM, PARTITION, 15);
            assertThat(ref.isEmpty()).isFalse();
            ref.onPresent(r -> {
                assertThat(r.startOffset()).isEqualTo(10);
                assertThat(r.endOffset()).isEqualTo(19);
            });
        }
    }

    @Nested
    class RefName {

        @Test
        void refName_formatsCorrectly() {
            var segment = sealedSegment("my-stream", 3, 100, 199, 100, 5000L, 6000L, new byte[0]);

            var name = StorageSegmentSink.refName(segment);

            assertThat(name).isEqualTo("streams/my-stream/3/100-199");
        }

        @Test
        void refName_partitionZero() {
            var segment = sealedSegment("s", 0, 0, 0, 1, 1L, 1L, new byte[0]);

            var name = StorageSegmentSink.refName(segment);

            assertThat(name).isEqualTo("streams/s/0/0-0");
        }
    }
}
