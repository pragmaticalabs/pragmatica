// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
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

    /// #601-class regression: `SegmentIndex.rebuildFromRefs` never recovers `maxTimestamp` (a ref name
    /// carries only offsets), so every segment surviving a restart comes back with `maxTimestamp = 0` — an
    /// UNKNOWN age, not a zero age. `isSegmentExpired` used to `return false` outright on that, which
    /// disabled count- and size-based eviction too (the check sat before the policy call), so disk grew
    /// without bound across restarts. The fix passes age `0` into the policy instead of short-circuiting,
    /// so under `RetentionMode.ANY` the age term alone is neutralized while count/bytes still fire.
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

            // Zero-timestamp segment (from rebuild) is preserved — age-only limit, unknown age never fires it.
            assertThat(index.listSegments(STREAM, PARTITION)).hasSize(1);
            assertThat(index.listSegments(STREAM, PARTITION).getFirst().startOffset()).isEqualTo(0);
        }

        /// THE bug this fix closes: a rebuilt (post-restart) segment must still be reclaimable once the
        /// stream holds more segments than `maxCount` allows — an unknown age must withhold only the AGE
        /// term, not the whole policy. `maxAgeMs = Long.MAX_VALUE` isolates this to the count term alone.
        @Test
        void enforce_evictsZeroTimestampSegment_whenCountLimitExceeded() {
            index.addSegment(STREAM, PARTITION, 0, 9);

            var enforcer = retentionEnforcer(storage,
                                             index,
                                             RetentionPolicy.retentionPolicy(0, Long.MAX_VALUE, Long.MAX_VALUE));
            enforcer.enforce();

            assertThat(index.listSegments(STREAM, PARTITION)).isEmpty();
        }

        /// Same bug, isolated to the size term: `maxCount = Long.MAX_VALUE` and `maxAgeMs = Long.MAX_VALUE`
        /// leave only `maxBytes` able to fire, and the zero-timestamp segment must still be evicted.
        @Test
        void enforce_evictsZeroTimestampSegment_whenSizeLimitExceeded() {
            index.addSegment(STREAM, PARTITION, 0, 9, 0L, 0, false, 1024);

            var enforcer = retentionEnforcer(storage,
                                             index,
                                             RetentionPolicy.retentionPolicy(Long.MAX_VALUE, 100, Long.MAX_VALUE));
            enforcer.enforce();

            assertThat(index.listSegments(STREAM, PARTITION)).isEmpty();
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

    /// #345 I3 — the floor that stops a durable entity's state being deleted by the age policy.
    ///
    /// This enforcer is built ONCE per node with a single policy and never reads per-stream config, so
    /// before the floor existed an entity's segments were deleted a fixed interval after its last write.
    /// For an event stream that is a missed event; for an entity, whose state IS its log, it is silent
    /// data loss no later read can detect. Every segment below is deliberately made EXPIRED by age, so
    /// each test isolates exactly one thing: whether the floor withheld it.
    @Nested
    class RecoveryFloor {

        /// The baseline that makes the rest meaningful — with no floor, an expired segment is deleted.
        /// If this ever fails, the other tests in here prove nothing, because a segment could survive for
        /// reasons having nothing to do with the floor.
        @Test
        void enforce_removesExpiredSegment_withNoFloor() {
            sealExpired(0, 9);

            enforceWithFloor(RetentionEnforcer.SegmentRetentionFloor.NONE);

            assertThat(index.listSegments(STREAM, PARTITION)).isEmpty();
        }

        /// Everything at or below the checkpoint is already folded into a durable snapshot, so reclaiming
        /// it loses nothing.
        @Test
        void enforce_removesExpiredSegment_entirelyBelowTheFloor() {
            sealExpired(0, 9);

            enforceWithFloor(floorAt(9));

            assertThat(index.listSegments(STREAM, PARTITION)).isEmpty();
        }

        /// THE data-loss case. The segment is expired by age and would be deleted by the plain policy,
        /// but it holds mutations above the checkpoint — the only copy of them anywhere. Deleting it is
        /// exactly the bug the floor exists to prevent, so this is the test that must go red if the floor
        /// is removed.
        @Test
        void enforce_keepsExpiredSegment_entirelyAboveTheFloor() {
            sealExpired(10, 19);

            enforceWithFloor(floorAt(9));

            assertThat(index.listSegments(STREAM, PARTITION)).hasSize(1);
            assertThat(index.listSegments(STREAM, PARTITION).getFirst().startOffset()).isEqualTo(10);
        }

        /// Segments are deleted as whole units, so a segment straddling the checkpoint must be kept
        /// entirely. Reclaiming it to recover the folded part below the bound would take the unfolded
        /// part above it too — which is the very state nothing else holds.
        @Test
        void enforce_keepsExpiredSegment_straddlingTheFloor() {
            sealExpired(5, 14);

            enforceWithFloor(floorAt(9));

            assertThat(index.listSegments(STREAM, PARTITION)).hasSize(1);
        }

        /// An entity partition with no checkpoint yet has folded nothing anywhere, so nothing may be
        /// reclaimed however old it is. The log grows until the first checkpoint — bounded growth is the
        /// checkpoint's job, and until one exists the only safe answer is to keep everything.
        @Test
        void enforce_keepsEverything_whenNothingHasBeenCheckpointed() {
            sealExpired(0, 9);
            sealExpired(10, 19);

            enforceWithFloor(floorAt(-1));

            assertThat(index.listSegments(STREAM, PARTITION)).hasSize(2);
        }

        /// The floor is scoped per partition: a checkpoint on partition 0 must not pin partition 1's log,
        /// or one lagging partition would hold the whole keyspace's storage.
        @Test
        void enforce_appliesFloorPerPartition() {
            sealExpired(STREAM, 0, 10, 19);
            sealExpired(STREAM, 1, 10, 19);

            enforceWithFloor((_, partition) -> partition == 0 ? 9 : Long.MAX_VALUE);

            assertThat(index.listSegments(STREAM, 0)).hasSize(1);
            assertThat(index.listSegments(STREAM, 1)).isEmpty();
        }

        /// The floor may only ever WITHHOLD a deletion, never cause one: a segment inside the age policy
        /// stays regardless of how permissive the bound is.
        @Test
        void enforce_keepsUnexpiredSegment_evenWhenFullyReclaimable() {
            var now = System.currentTimeMillis();

            sink.seal(sealedSegment(STREAM, PARTITION, 0, 9, 10, now, now, new byte[] {1})).await();

            enforceWithFloor(floorAt(Long.MAX_VALUE));

            assertThat(index.listSegments(STREAM, PARTITION)).hasSize(1);
        }

        private static RetentionEnforcer.SegmentRetentionFloor floorAt(long deletableThrough) {
            return (_, _) -> deletableThrough;
        }

        private void enforceWithFloor(RetentionEnforcer.SegmentRetentionFloor floor) {
            retentionEnforcer(storage, index, ONE_HOUR_MS, floor).enforce();
        }

        private void sealExpired(long startOffset, long endOffset) {
            sealExpired(STREAM, PARTITION, startOffset, endOffset);
        }

        private void sealExpired(String stream, int partition, long startOffset, long endOffset) {
            var expiredAt = System.currentTimeMillis() - 2 * ONE_HOUR_MS;

            sink.seal(sealedSegment(stream,
                                    partition,
                                    startOffset,
                                    endOffset,
                                    (int) (endOffset - startOffset + 1),
                                    expiredAt,
                                    expiredAt,
                                    new byte[] {1, 2, 3})).await();
        }
    }
}
