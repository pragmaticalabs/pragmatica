// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

    /// #701 — the watermark never asserts coverage the fold does not have. Of the ticket's two honest
    /// options (block, or advance and record the gap explicitly) the BLOCKING one is taken, matching the
    /// fold's existing refuse-over-lie posture: a failed apply HOLDS the watermark, which freezes the
    /// checkpoint below the hole so retention keeps the record replayable, and makes the next operation's
    /// catch-up gate replay it through the path that propagates the failure loudly.
    ///
    /// The outage that buys — a permanently unapplicable record blocks its partition until a build that
    /// can apply it is deployed — is the ticket's own named-and-bounded requirement, not a regression: the
    /// behaviour it replaces advanced past the record and then let a checkpoint reclaim the only replayable
    /// copy, losing the write for good while still reporting coverage.
    ///
    /// The ticket's second half (the lost-CAS NPE in [EntityFold#caughtUp]) is covered here with its
    /// evidence split honestly: the abandoned-slot HANG is pinned deterministically below, while the
    /// null-slot dereference itself is closed BY CONSTRUCTION — the failed-CAS path re-enters the gate
    /// instead of dereferencing the slot, so no read remains that could return null.
    ///
    /// A concurrency hammer aimed at that race was written, MEASURED, and deleted rather than shipped.
    /// Instrumenting it over ~2400 calls showed 99.1% short-circuiting at the `appliedThrough >= head`
    /// gate, the CAS reached 3 times per run and lost 0–1 times, and the null-slot precondition never
    /// occurring once — a start latch and a yield inside every substrate read moved none of those numbers,
    /// because the appender's in-memory backlog drains in a single pass and leaves later callers nothing
    /// to do. It was a test whose name was a claim the code did not support. Recorded here so it is not
    /// reintroduced on the assumption that nobody checked.
    @Nested
    class WatermarkHonesty {
        private static final byte[] MALFORMED_TIMER_PAYLOAD = {1, 2, 3, 4};

        /// The write-path pin: a failed apply at offset 1 holds the watermark at 0 even though offset 2
        /// applies successfully afterwards — the later success PARKS instead of stepping the claim over
        /// the hole, exactly as an out-of-order arrival would.
        @Test
        void apply_holdsWatermark_atFailedRecord_andLaterSuccessParks() {
            var substrate = new FakeSubstrate();
            var fold = EntityFold.entityFold(KEYSPACE, substrate);

            awaitReady(fold);

            fold.apply(PARTITION, 0, EntityLogRecord.upsert("a", bytes("1")));
            fold.apply(PARTITION,
                       1,
                       new EntityLogRecord(EntityLogRecord.Op.TIMER_SCHEDULE, "poison", MALFORMED_TIMER_PAYLOAD));
            fold.apply(PARTITION, 2, EntityLogRecord.upsert("b", bytes("2")));

            fold.checkpointCandidate(PARTITION)
                .onPresent(candidate -> assertThat(candidate.throughOffset())
                        .as("the claim must stop BELOW the unapplied record — a checkpoint past it would let"
                            + " retention reclaim the only replayable copy")
                        .isEqualTo(0L))
                .onEmpty(() -> fail("offset 0 was applied — a candidate must exist"));
        }

        /// The catch-up-path pin: a record the fold cannot apply makes replay REFUSE rather than silently
        /// skip and serve state missing it.
        ///
        /// Scope, stated because it is easy to over-read: this passes against the PRE-#701 code too — the
        /// replay path already advanced only on success, so it is a regression fence around behaviour the
        /// fix did not change, NOT evidence for the fix. The fix's own evidence is the two watermark pins,
        /// which flip when `apply` is reverted.
        @Test
        void ready_refusesLoudly_whenLogHoldsUnapplicableRecord() {
            var substrate = new FakeSubstrate();

            substrate.append(EntityLogRecord.upsert("a", bytes("1")));
            substrate.append(new EntityLogRecord(EntityLogRecord.Op.TIMER_SCHEDULE, "poison", MALFORMED_TIMER_PAYLOAD));
            substrate.append(EntityLogRecord.upsert("b", bytes("2")));

            EntityFold.entityFold(KEYSPACE, substrate)
                      .ready(PARTITION)
                      .await()
                      .onSuccess(_ -> fail("a fold that cannot apply a committed record must refuse,"
                                           + " not serve state missing it"));
        }

        /// #701 item 2's liveness sibling, and the half of it a test can pin deterministically. A
        /// SYNCHRONOUS throw out of `runCatchUp` used to escape between the won CAS and the `onResult`
        /// attach, leaving the slot holding a promise nothing would ever resolve — so the failure was not
        /// the end of it: every LATER caller waited on that abandoned promise forever.
        ///
        /// The assertion that matters is therefore the SECOND call, not the first. It must make its own
        /// attempt rather than inherit a slot the first call walked away from, and the third — once the
        /// substrate is healthy again — must actually drain the gap.
        @Test
        @Timeout(30)
        void caughtUp_synchronousSubstrateThrow_failsAndLeavesTheSlotReusable() {
            var substrate = new FakeSubstrate();

            substrate.append(EntityLogRecord.upsert("a", bytes("1")));

            var fold = EntityFold.entityFold(KEYSPACE, substrate);

            awaitReady(fold);

            substrate.append(EntityLogRecord.upsert("b", bytes("2")));
            substrate.readThrows = new IllegalStateException("substrate threw instead of failing its promise");

            assertThat(fold.caughtUp(PARTITION).await().isFailure())
                    .as("a synchronous throw must arrive as a resolved failure, not escape the gate")
                    .isTrue();
            assertThat(fold.caughtUp(PARTITION).await().isFailure())
                    .as("the slot must be clear — a second caller inheriting an abandoned promise hangs")
                    .isTrue();

            substrate.readThrows = null;

            fold.caughtUp(PARTITION)
                .await()
                .onFailure(cause -> fail("a recovered substrate must let catch-up drain: " + cause.message()));

            assertThat(text(fold, "b")).isEqualTo("2");
        }
    }

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

            substrate.checkpoint(4, EntityFoldSnapshot.encode(Map.of("seeded", bytes("fromCheckpoint")), Map.of()));
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

            substrate.checkpoint(4, EntityFoldSnapshot.encode(Map.of("seeded", bytes("v")), Map.of()));
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

            substrate.checkpoint(throughOffset, EntityFoldSnapshot.encode(Map.of(), Map.of()));

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
        // Copy-on-write, and every read SNAPSHOTS before slicing (see #read). The fold's own machinery
        // resolves catch-up continuations off the calling thread, so this fake is not reliably
        // single-threaded even in tests that look sequential; a plain list threw
        // ConcurrentModificationException from inside runCatchUp, which poisoned the catch-up slot and
        // hung the suite rather than failing it.
        private final List<byte[]> records = new CopyOnWriteArrayList<>();
        private final AtomicInteger checkpointLoads = new AtomicInteger();
        private long baseOffset;
        private long checkpointThrough = -1L;
        private byte[] checkpointSnapshot;
        private boolean localLogComplete = true;
        private boolean holdsPartition = true;
        // Inert by default, so every other test in this file sees the substrate unchanged. Models a
        // substrate that fails SYNCHRONOUSLY rather than returning a failed promise — the window
        // Result.lift closes in caughtUp.
        private RuntimeException readThrows;

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
            if (readThrows != null) {
                throw readThrows;
            }

            // Snapshot FIRST: copy-on-write covers the list, not the views it hands out. A subList of a
            // CopyOnWriteArrayList is a LIVE view, so slicing before copying reintroduces exactly the
            // ConcurrentModificationException the copy-on-write list was chosen to avoid.
            var snapshot = List.copyOf(records);
            var start = (int) (fromOffset - baseOffset);

            if (start < 0 || start >= snapshot.size()) {
                return Promise.success(List.of());
            }

            return Promise.success(snapshot.subList(start, Math.min(snapshot.size(), start + maxRecords)));
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
