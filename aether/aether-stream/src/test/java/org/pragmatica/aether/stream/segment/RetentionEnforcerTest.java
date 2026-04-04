package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.segment.RetentionEnforcer.retentionEnforcer;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

class RetentionEnforcerTest {

    private static final String STREAM = "test-stream";
    private static final int PARTITION = 0;
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final long ONE_HOUR_MS = 3_600_000L;

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
    class EnforcePass {

        @Test
        void enforce_removesExpiredSegments() {
            var now = System.currentTimeMillis();
            var oldTimestamp = now - 2 * ONE_HOUR_MS;

            var oldSegment = sealedSegment(STREAM, PARTITION, 0, 9, 10, oldTimestamp, oldTimestamp, new byte[]{1, 2, 3});
            var recentSegment = sealedSegment(STREAM, PARTITION, 10, 19, 10, now, now, new byte[]{4, 5, 6});

            sink.seal(oldSegment).await();
            sink.seal(recentSegment).await();

            assertThat(index.listSegments(STREAM, PARTITION)).hasSize(2);

            var enforcer = retentionEnforcer(storage, index, ONE_HOUR_MS);
            enforcer.enforce();

            var remaining = index.listSegments(STREAM, PARTITION);
            assertThat(remaining).hasSize(1);
            assertThat(remaining.getFirst().startOffset()).isEqualTo(10);
        }

        @Test
        void enforce_keepsAllSegments_whenNoneExpired() {
            var now = System.currentTimeMillis();

            var segment1 = sealedSegment(STREAM, PARTITION, 0, 9, 10, now, now, new byte[]{1});
            var segment2 = sealedSegment(STREAM, PARTITION, 10, 19, 10, now, now, new byte[]{2});

            sink.seal(segment1).await();
            sink.seal(segment2).await();

            var enforcer = retentionEnforcer(storage, index, ONE_HOUR_MS);
            enforcer.enforce();

            assertThat(index.listSegments(STREAM, PARTITION)).hasSize(2);
        }

        @Test
        void enforce_removesAllExpiredSegments() {
            var oldTimestamp = System.currentTimeMillis() - 2 * ONE_HOUR_MS;

            var seg1 = sealedSegment(STREAM, PARTITION, 0, 9, 10, oldTimestamp, oldTimestamp, new byte[]{1});
            var seg2 = sealedSegment(STREAM, PARTITION, 10, 19, 10, oldTimestamp, oldTimestamp, new byte[]{2});

            sink.seal(seg1).await();
            sink.seal(seg2).await();

            var enforcer = retentionEnforcer(storage, index, ONE_HOUR_MS);
            enforcer.enforce();

            assertThat(index.listSegments(STREAM, PARTITION)).isEmpty();
        }

        @Test
        void enforce_removesStorageRefs() {
            var oldTimestamp = System.currentTimeMillis() - 2 * ONE_HOUR_MS;
            var segment = sealedSegment(STREAM, PARTITION, 0, 9, 10, oldTimestamp, oldTimestamp, new byte[]{1, 2});

            sink.seal(segment).await();
            var refName = StorageSegmentSink.refName(segment);
            assertThat(storage.resolveRef(refName).isEmpty()).isFalse();

            var enforcer = retentionEnforcer(storage, index, ONE_HOUR_MS);
            enforcer.enforce();

            assertThat(storage.resolveRef(refName).isEmpty()).isTrue();
        }

        @Test
        void enforce_handlesMultiplePartitions() {
            var oldTimestamp = System.currentTimeMillis() - 2 * ONE_HOUR_MS;
            var now = System.currentTimeMillis();

            var seg0 = sealedSegment(STREAM, 0, 0, 9, 10, oldTimestamp, oldTimestamp, new byte[]{1});
            var seg1 = sealedSegment(STREAM, 1, 0, 9, 10, now, now, new byte[]{2});

            sink.seal(seg0).await();
            sink.seal(seg1).await();

            var enforcer = retentionEnforcer(storage, index, ONE_HOUR_MS);
            enforcer.enforce();

            assertThat(index.listSegments(STREAM, 0)).isEmpty();
            assertThat(index.listSegments(STREAM, 1)).hasSize(1);
        }
    }

    @Nested
    class SkipsZeroTimestamp {

        @Test
        void enforce_skipsSegmentsWithZeroTimestamp() {
            index.addSegment(STREAM, PARTITION, 0, 9);
            var now = System.currentTimeMillis();
            var oldTimestamp = now - 2 * ONE_HOUR_MS;
            var seg = sealedSegment(STREAM, PARTITION, 10, 19, 10, oldTimestamp, oldTimestamp, new byte[]{1});
            sink.seal(seg).await();

            var enforcer = retentionEnforcer(storage, index, ONE_HOUR_MS);
            enforcer.enforce();

            // Zero-timestamp segment (from rebuild) is preserved
            assertThat(index.listSegments(STREAM, PARTITION)).hasSize(1);
            assertThat(index.listSegments(STREAM, PARTITION).getFirst().startOffset()).isEqualTo(0);
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void close_preventsSubsequentEnforce() {
            var oldTimestamp = System.currentTimeMillis() - 2 * ONE_HOUR_MS;
            var segment = sealedSegment(STREAM, PARTITION, 0, 9, 10, oldTimestamp, oldTimestamp, new byte[]{1});
            sink.seal(segment).await();

            var enforcer = retentionEnforcer(storage, index, ONE_HOUR_MS);
            enforcer.close();
            enforcer.enforce();

            // Segment should NOT be removed because enforcer is closed
            assertThat(index.listSegments(STREAM, PARTITION)).hasSize(1);
        }
    }
}
