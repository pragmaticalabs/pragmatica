// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #345 I3 — the fold: rebuilding a partition's state from its durable log.
///
/// The fake substrate below is deliberately LABEL-FAITHFUL rather than permissive. It models a log as a
/// list with a real base offset, so `earliestRetainedOffset` and `headOffset` mean what they mean in the
/// stream: trimming the front genuinely makes those offsets unreadable. A fake that answered every read
/// regardless of its own retention would make the gap test pass against a fold that never checked, which
/// is the one thing these tests exist to prevent.
class EntityFoldTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITION = 3;

    @Nested
    class Replay {
        @Test
        void ready_foldsEmptyPartition_withNoCheckpointAndNoRecords() {
            var fold = EntityFold.entityFold(KEYSPACE, new FakeSubstrate());

            awaitReady(fold);

            assertThat(fold.get(PARTITION, "absent")).isEqualTo(Option.none());
        }

        @Test
        void ready_replaysEveryRecord_fromTheBeginning() {
            var substrate = new FakeSubstrate();

            substrate.append(EntityLogRecord.upsert("a", bytes("1")));
            substrate.append(EntityLogRecord.upsert("b", bytes("2")));
            substrate.append(EntityLogRecord.upsert("a", bytes("3")));

            var fold = EntityFold.entityFold(KEYSPACE, substrate);

            awaitReady(fold);

            assertThat(text(fold, "a")).isEqualTo("3");
            assertThat(text(fold, "b")).isEqualTo("2");
        }

        /// A tombstone must remove the key on replay. If a delete were encoded as an absent record, the
        /// prior value would reappear on every rebuild — state that resurrects deleted keys after a
        /// failover.
        @Test
        void ready_appliesTombstone_soDeletedKeyStaysAbsent() {
            var substrate = new FakeSubstrate();

            substrate.append(EntityLogRecord.upsert("gone", bytes("v")));
            substrate.append(EntityLogRecord.delete("gone"));

            var fold = EntityFold.entityFold(KEYSPACE, substrate);

            awaitReady(fold);

            assertThat(fold.get(PARTITION, "gone")).isEqualTo(Option.none());
        }

        /// Replay must cross the internal batch boundary. A fold that read one batch and stopped would
        /// look correct on small logs and silently truncate on real ones.
        @Test
        void ready_replaysAcrossBatchBoundaries() {
            var substrate = new FakeSubstrate();
            var count = 1500;

            for (var i = 0; i < count; i++) {
                substrate.append(EntityLogRecord.upsert("k" + i, bytes("v" + i)));
            }

            var fold = EntityFold.entityFold(KEYSPACE, substrate);

            awaitReady(fold);

            assertThat(text(fold, "k0")).isEqualTo("v0");
            assertThat(text(fold, "k" + (count - 1))).isEqualTo("v" + (count - 1));
        }

        @Test
        void ready_restoresCheckpoint_thenReplaysOnlyTheTail() {
            var substrate = new FakeSubstrate();

            substrate.checkpoint(4, EntityFoldSnapshot.encode(Map.of("seeded", bytes("fromCheckpoint"))));
            substrate.appendAt(5, EntityLogRecord.upsert("tail", bytes("fromLog")));

            var fold = EntityFold.entityFold(KEYSPACE, substrate);

            awaitReady(fold);

            assertThat(text(fold, "seeded")).isEqualTo("fromCheckpoint");
            assertThat(text(fold, "tail")).isEqualTo("fromLog");
        }
    }

    @Nested
    class RefusesRatherThanServingPartialState {
        /// THE safety case. The checkpoint resumes at 5, but retention has moved the readable log up to
        /// 20, so offsets 5..19 are on no reachable node. Folding what is available would produce state
        /// missing committed writes that no later read could detect, so the fold must refuse.
        @Test
        void ready_fails_whenRetentionHasOutrunTheCheckpoint() {
            var substrate = new FakeSubstrate();

            substrate.checkpoint(4, EntityFoldSnapshot.encode(Map.of("seeded", bytes("v"))));
            substrate.appendAt(20, EntityLogRecord.upsert("late", bytes("v")));
            substrate.trimBefore(20);

            var fold = EntityFold.entityFold(KEYSPACE, substrate);

            assertFailsWith(fold, EntityLogError.FoldFailed.class);
        }

        /// A node whose local copy is not yet complete must WAIT, not fold. Its head offset would
        /// otherwise be read as the end of the log when it is only the end of what happens to be here —
        /// the #593 shape.
        @Test
        void ready_reportsFoldInProgress_whileLocalLogIncomplete() {
            var substrate = new FakeSubstrate();

            substrate.append(EntityLogRecord.upsert("a", bytes("1")));
            substrate.localLogComplete = false;

            var fold = EntityFold.entityFold(KEYSPACE, substrate);

            assertFailsWith(fold, EntityLogError.FoldInProgress.class);
        }

        /// The refusal must not latch: an incomplete local log becomes complete once catch-up finishes,
        /// and a latched failure would turn that transient state into a permanent outage.
        @Test
        void ready_retriesAfterFailure_soATransientRefusalClears() {
            var substrate = new FakeSubstrate();

            substrate.append(EntityLogRecord.upsert("a", bytes("1")));
            substrate.localLogComplete = false;

            var fold = EntityFold.entityFold(KEYSPACE, substrate);

            assertFailsWith(fold, EntityLogError.FoldInProgress.class);

            substrate.localLogComplete = true;

            awaitReady(fold);

            assertThat(text(fold, "a")).isEqualTo("1");
        }

        @Test
        void ready_fails_forCorruptRecordInTheLog() {
            var substrate = new FakeSubstrate();

            substrate.appendRaw(new byte[] {99, 0, 0, 0, 0, 0});

            var fold = EntityFold.entityFold(KEYSPACE, substrate);

            assertFailsWith(fold, EntityLogError.FoldFailed.class);
        }
    }

    /// A checkpoint may only claim an offset every record at or below which is actually in the map.
    /// These pin that bound directly, because getting it wrong loses a write silently: recovery resumes
    /// at `throughOffset + 1`, so any offset wrongly claimed is skipped forever.
    ///
    /// Offsets are NOT known before an append resolves, and concurrent writes to different keys of one
    /// partition resolve in arbitrary order — so the watermark must be a contiguous prefix, not a maximum.
    @Nested
    class CheckpointableThrough {
        @Test
        void checkpointableThrough_isMinusOne_forAFreshPartition() {
            var fold = readyFold(new FakeSubstrate());

            assertThat(fold.checkpointableThrough(PARTITION)).isEqualTo(-1);
        }

        @Test
        void checkpointableThrough_advancesContiguously_whenOffsetsArriveInOrder() {
            var fold = readyFold(new FakeSubstrate());

            fold.apply(PARTITION, 0, EntityLogRecord.upsert("a", bytes("1")));
            fold.apply(PARTITION, 1, EntityLogRecord.upsert("b", bytes("2")));

            assertThat(fold.checkpointableThrough(PARTITION)).isEqualTo(1);
        }

        /// THE subtle one. Offsets 6 and 7 have landed while 5 has not. Claiming 7 would make recovery
        /// resume at 8 and skip offset 5 — a real, durable, committed mutation — permanently. The bound
        /// must stay at 4 until 5 arrives.
        @Test
        void checkpointableThrough_holdsAtTheGap_whenLaterOffsetsLandFirst() {
            var fold = foldSeededAt(4);

            fold.apply(PARTITION, 6, EntityLogRecord.upsert("b", bytes("2")));
            fold.apply(PARTITION, 7, EntityLogRecord.upsert("c", bytes("3")));

            assertThat(fold.checkpointableThrough(PARTITION)).isEqualTo(4);
        }

        /// ...and once the missing offset lands, the watermark jumps past everything already parked
        /// behind it, rather than crawling forward one checkpoint at a time.
        @Test
        void checkpointableThrough_jumpsPastParkedOffsets_onceTheGapFills() {
            var fold = foldSeededAt(4);

            fold.apply(PARTITION, 6, EntityLogRecord.upsert("b", bytes("2")));
            fold.apply(PARTITION, 7, EntityLogRecord.upsert("c", bytes("3")));

            assertThat(fold.checkpointableThrough(PARTITION)).isEqualTo(4);

            fold.apply(PARTITION, 5, EntityLogRecord.upsert("a", bytes("1")));

            assertThat(fold.checkpointableThrough(PARTITION)).isEqualTo(7);
        }

        /// State is visible as soon as it is applied, even while the watermark is held back — the bound
        /// governs what a CHECKPOINT may claim, not what a read may see. Conflating the two would make a
        /// write invisible to its own caller until an unrelated key's append happened to land.
        @Test
        void get_servesAppliedState_evenWhileTheWatermarkIsHeldBack() {
            var fold = foldSeededAt(4);

            fold.apply(PARTITION, 7, EntityLogRecord.upsert("visible", bytes("now")));

            assertThat(fold.checkpointableThrough(PARTITION)).isEqualTo(4);
            assertThat(text(fold, "visible")).isEqualTo("now");
        }

        private static EntityFold foldSeededAt(long throughOffset) {
            var substrate = new FakeSubstrate();

            substrate.checkpoint(throughOffset, EntityFoldSnapshot.encode(Map.of()));

            return readyFold(substrate);
        }
    }

    @Nested
    class Snapshotting {
        @Test
        void snapshot_roundTripsThroughAFreshFold() {
            var source = readyFold(new FakeSubstrate());

            source.apply(PARTITION, 0, EntityLogRecord.upsert("a", bytes("1")));
            source.apply(PARTITION, 1, EntityLogRecord.upsert("b", bytes("2")));

            var substrate = new FakeSubstrate();

            substrate.checkpoint(source.checkpointableThrough(PARTITION), source.snapshot(PARTITION));

            var restored = EntityFold.entityFold(KEYSPACE, substrate);

            awaitReady(restored);

            assertThat(text(restored, "a")).isEqualTo("1");
            assertThat(text(restored, "b")).isEqualTo("2");
        }

        @Test
        void snapshot_omitsDeletedKeys() {
            var source = readyFold(new FakeSubstrate());

            source.apply(PARTITION, 0, EntityLogRecord.upsert("a", bytes("1")));
            source.apply(PARTITION, 1, EntityLogRecord.delete("a"));

            var substrate = new FakeSubstrate();

            substrate.checkpoint(source.checkpointableThrough(PARTITION), source.snapshot(PARTITION));

            var restored = EntityFold.entityFold(KEYSPACE, substrate);

            awaitReady(restored);

            assertThat(restored.get(PARTITION, "a")).isEqualTo(Option.none());
        }
    }

    @Nested
    class Memoization {
        /// Concurrent operations on different keys of one partition must trigger exactly ONE replay.
        /// Replaying per caller would multiply recovery cost by the request rate at the worst moment.
        @Test
        void ready_rebuildsOnce_forRepeatedCalls() {
            var substrate = new FakeSubstrate();

            substrate.append(EntityLogRecord.upsert("a", bytes("1")));

            var fold = EntityFold.entityFold(KEYSPACE, substrate);

            awaitReady(fold);
            awaitReady(fold);
            awaitReady(fold);

            assertThat(substrate.checkpointLoads.get()).isEqualTo(1);
        }
    }

    private static EntityFold readyFold(FakeSubstrate substrate) {
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        awaitReady(fold);

        return fold;
    }

    private static void awaitReady(EntityFold fold) {
        fold.ready(PARTITION).await().onFailure(cause -> fail("fold must be ready: " + cause.message()));
    }

    private static void assertFailsWith(EntityFold fold, Class<? extends Cause> expected) {
        fold.ready(PARTITION)
            .await()
            .onSuccess(_ -> fail("fold must refuse rather than serve partial state"))
            .onFailure(cause -> assertThat(cause.stream()).hasAtLeastOneElementOfType(expected));
    }

    private static String text(EntityFold fold, String key) {
        return fold.get(PARTITION, key)
                   .map(value -> new String(value, StandardCharsets.UTF_8))
                   .or(() -> fail("key " + key + " must be present"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /// A log modelled as records from `baseOffset` onward, so retention is REAL: [#trimBefore] genuinely
    /// removes the ability to read what it drops, and `earliestRetainedOffset` reports it.
    private static final class FakeSubstrate implements EntityLogSubstrate {
        private final List<byte[]> records = new ArrayList<>();
        private final AtomicInteger checkpointLoads = new AtomicInteger();
        private long baseOffset;
        private long checkpointThrough = -1L;
        private byte[] checkpointSnapshot;
        private boolean localLogComplete = true;
        private boolean holdsPartition = true;

        void append(EntityLogRecord record) {
            records.add(record.encode());
        }

        void appendRaw(byte[] raw) {
            records.add(raw);
        }

        void appendAt(long offset, EntityLogRecord record) {
            baseOffset = offset;
            records.clear();
            records.add(record.encode());
        }

        void checkpoint(long throughOffset, byte[] snapshot) {
            checkpointThrough = throughOffset;
            checkpointSnapshot = snapshot;
        }

        void trimBefore(long offset) {
            baseOffset = offset;
        }

        @Override
        public Result<Unit> ensureLog(String keyspace, int partitionCount, int replicationFactor, int minSyncReplicas) {
            return Result.unitResult();
        }

        @Override
        public Promise<Long> append(String keyspace, int partition, byte[] record) {
            records.add(record);

            return Promise.success(baseOffset + records.size() - 1);
        }

        @Override
        public Promise<List<byte[]>> read(String keyspace, int partition, long fromOffset, int maxRecords) {
            var start = (int) (fromOffset - baseOffset);

            if (start < 0 || start >= records.size()) {
                return Promise.success(List.of());
            }

            return Promise.success(List.copyOf(records.subList(start, Math.min(records.size(), start + maxRecords))));
        }

        @Override
        public long headOffset(String keyspace, int partition) {
            return records.isEmpty() ? -1L : baseOffset + records.size() - 1;
        }

        @Override
        public long earliestRetainedOffset(String keyspace, int partition) {
            return records.isEmpty() ? -1L : baseOffset;
        }

        @Override
        public boolean holdsPartition(String keyspace, int partition) {
            return holdsPartition;
        }

        @Override
        public boolean localLogComplete(String keyspace, int partition) {
            return localLogComplete;
        }

        @Override
        public Promise<Unit> saveCheckpoint(String keyspace, int partition, long throughOffset, byte[] snapshot) {
            checkpoint(throughOffset, snapshot);

            return Promise.unitPromise();
        }

        @Override
        public Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition) {
            checkpointLoads.incrementAndGet();

            return Promise.success(checkpointSnapshot == null
                                   ? Option.none()
                                   : Option.some(EntityCheckpoint.entityCheckpoint(checkpointThrough,
                                                                                   checkpointSnapshot)));
        }
    }
}
