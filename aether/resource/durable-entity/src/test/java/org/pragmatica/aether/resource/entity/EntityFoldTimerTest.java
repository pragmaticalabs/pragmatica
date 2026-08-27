// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #345 I4 — the fold half of durable timers: the pending set is DERIVED from the log, exactly as state
/// is, so a node inherits a timer wheel by replaying rather than by being told.
///
/// The property that makes that safe is the one pinned hardest below: a fire is ONE record that both
/// consumes its token and upserts the post-fire state, so replaying a fired timer produces the FIRED state
/// instead of re-arming the timer. Get that wrong and every restart re-runs every timer that ever fired —
/// an at-least-once timer wearing a one-shot API, and nothing in a read would show it.
class EntityFoldTimerTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITION = 0;
    private static final String KEY = "order-1";
    private static final String TOKEN = "tok-1";
    private static final long FIRE_AT = 1_700_000_000_000L;

    private GrowableSubstrate substrate;

    @BeforeEach
    void setUp() {
        substrate = new GrowableSubstrate();
    }

    @Nested
    class ApplySemantics {
        @Test
        void apply_timerSchedule_makesTheTokenPending() {
            var fold = readyFold();

            fold.apply(PARTITION, 0, EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));

            assertThat(fold.isTimerPending(PARTITION, KEY, TOKEN)).isTrue();
        }

        @Test
        void apply_timerCancel_removesThePendingToken() {
            var fold = readyFold();

            fold.apply(PARTITION, 0, EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));
            fold.apply(PARTITION, 1, EntityLogRecord.timerCancel(KEY, TOKEN));

            assertThat(fold.isTimerPending(PARTITION, KEY, TOKEN)).isFalse();
        }

        /// Cancel is idempotent by construction — removing a token that is not there is a no-op. That is
        /// what makes a replayed cancel, a caller's second cancel, and the consume-on-failure record all
        /// safe, and it is why a cancel is never an error.
        @Test
        void apply_timerCancel_isANoOp_forATokenThatWasNeverPending() {
            var fold = readyFold();

            fold.apply(PARTITION, 0, EntityLogRecord.upsert(KEY, bytes("state")));
            fold.apply(PARTITION, 1, EntityLogRecord.timerCancel(KEY, "never-scheduled"));

            assertThat(fold.isTimerPending(PARTITION, KEY, "never-scheduled")).isFalse();
            assertThat(text(fold, KEY)).isEqualTo("state");
        }

        /// The atomicity claim, at the only level it can be observed: after ONE record the token is gone
        /// AND the state has advanced. Neither half can be seen without the other.
        @Test
        void apply_timerFire_consumesTheTokenAndUpsertsTheCarriedState() {
            var fold = readyFold();

            fold.apply(PARTITION, 0, EntityLogRecord.upsert(KEY, bytes("before")));
            fold.apply(PARTITION, 1, EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));
            fold.apply(PARTITION, 2, EntityLogRecord.timerFire(KEY, TOKEN, bytes("after")));

            assertThat(fold.isTimerPending(PARTITION, KEY, TOKEN)).isFalse();
            assertThat(text(fold, KEY)).isEqualTo("after");
        }

        /// A fire consumes ONLY its own token. A key holding several timers must not lose the others
        /// because one of them came due.
        @Test
        void apply_timerFire_leavesTheKeysOtherTimersPending() {
            var fold = readyFold();

            fold.apply(PARTITION, 0, EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));
            fold.apply(PARTITION, 1, EntityLogRecord.timerSchedule(KEY, "tok-2", FIRE_AT, bytes("cmd2")));
            fold.apply(PARTITION, 2, EntityLogRecord.timerFire(KEY, TOKEN, bytes("after")));

            assertThat(fold.isTimerPending(PARTITION, KEY, TOKEN)).isFalse();
            assertThat(fold.isTimerPending(PARTITION, KEY, "tok-2")).isTrue();
        }

        /// Spec §5.1 — delete auto-cancels. Leaving the timers armed would come due against a key with no
        /// state, once per tick: the fire would find nothing, consume the timer and log an error naming a
        /// deletion that was entirely correct.
        @Test
        void apply_delete_clearsThePendingTimersOfThatKey() {
            var fold = readyFold();

            fold.apply(PARTITION, 0, EntityLogRecord.upsert(KEY, bytes("state")));
            fold.apply(PARTITION, 1, EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));
            fold.apply(PARTITION, 2, EntityLogRecord.delete(KEY));

            assertThat(fold.isTimerPending(PARTITION, KEY, TOKEN)).isFalse();
            assertThat(fold.dueTimers(PARTITION, Long.MAX_VALUE)).isEmpty();
        }

        @Test
        void apply_delete_leavesOtherKeysTimersPending() {
            var fold = readyFold();

            fold.apply(PARTITION, 0, EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));
            fold.apply(PARTITION, 1, EntityLogRecord.timerSchedule("order-2", "tok-2", FIRE_AT, bytes("cmd")));
            fold.apply(PARTITION, 2, EntityLogRecord.delete(KEY));

            assertThat(fold.isTimerPending(PARTITION, KEY, TOKEN)).isFalse();
            assertThat(fold.isTimerPending(PARTITION, "order-2", "tok-2")).isTrue();
        }

        /// A payload this build cannot parse must not silently arm nothing and move on unnoticed: the
        /// append path logs it and keeps the watermark moving (the record IS in the log), and the replay
        /// path fails the fold outright — see [RefusesMalformedPayloads].
        @Test
        void apply_malformedTimerPayload_armsNothing_andStillAdvancesTheWatermark() {
            var fold = readyFold();

            fold.apply(PARTITION, 0, new EntityLogRecord(EntityLogRecord.Op.TIMER_SCHEDULE, KEY, bytes("junk")));

            assertThat(fold.dueTimers(PARTITION, Long.MAX_VALUE)).isEmpty();
            assertThat(fold.checkpointableThrough(PARTITION)).isEqualTo(0L);
        }
    }

    @Nested
    class DueTimers {
        @Test
        void dueTimers_excludesATimerWhoseInstantHasNotArrived() {
            var fold = readyFold();

            fold.apply(PARTITION, 0, EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));

            assertThat(fold.dueTimers(PARTITION, FIRE_AT - 1)).isEmpty();
        }

        /// The boundary is `<=`, so a timer scheduled with zero delay is due on the tick it was created
        /// for rather than one interval later.
        @Test
        void dueTimers_includesATimerDueExactlyNow() {
            var fold = readyFold();

            fold.apply(PARTITION, 0, EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));

            assertThat(fold.dueTimers(PARTITION, FIRE_AT)).hasSize(1);
        }

        @Test
        void dueTimers_carriesTheKeyTokenAndCommand() {
            var fold = readyFold();

            fold.apply(PARTITION, 0, EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));

            var due = fold.dueTimers(PARTITION, FIRE_AT).getFirst();

            assertThat(due.key()).isEqualTo(KEY);
            assertThat(due.token()).isEqualTo(TOKEN);
            assertThat(new String(due.command(), StandardCharsets.UTF_8)).isEqualTo("cmd");
        }

        @Test
        void dueTimers_spansEveryKeyOfThePartition() {
            var fold = readyFold();

            fold.apply(PARTITION, 0, EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("a")));
            fold.apply(PARTITION, 1, EntityLogRecord.timerSchedule("order-2", "tok-2", FIRE_AT - 5, bytes("b")));
            fold.apply(PARTITION, 2, EntityLogRecord.timerSchedule("order-3", "tok-3", FIRE_AT + 5, bytes("c")));

            assertThat(fold.dueTimers(PARTITION, FIRE_AT)).extracting(EntityFold.DueTimer::token)
                                                          .containsExactlyInAnyOrder(TOKEN, "tok-2");
        }

        /// A partition that has never been rebuilt answers EMPTY rather than guessing. That is honest —
        /// this node knows of no pending timer here — and is exactly why the tick drives readiness before
        /// asking.
        @Test
        void dueTimers_isEmpty_forAPartitionNeverRebuilt() {
            assertThat(EntityFold.entityFold(KEYSPACE, substrate).dueTimers(PARTITION, Long.MAX_VALUE)).isEmpty();
        }
    }

    /// The restart case. A fold rebuilt from the log alone must reproduce the state the timers left, not
    /// re-arm them — otherwise every restart re-runs every timer that ever fired.
    @Nested
    class ReplayIdempotence {
        @Test
        void ready_replayOfScheduleThenFire_leavesTheFiredState_andNoPendingTimer() {
            replicate(EntityLogRecord.upsert(KEY, bytes("before")));
            replicate(EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));
            replicate(EntityLogRecord.timerFire(KEY, TOKEN, bytes("after")));

            var fold = readyFold();

            assertThat(text(fold, KEY)).isEqualTo("after");
            assertThat(fold.isTimerPending(PARTITION, KEY, TOKEN)).isFalse();
            assertThat(fold.dueTimers(PARTITION, Long.MAX_VALUE)).isEmpty();
        }

        @Test
        void ready_replayOfScheduleThenCancel_leavesNoPendingTimer() {
            replicate(EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));
            replicate(EntityLogRecord.timerCancel(KEY, TOKEN));

            assertThat(readyFold().dueTimers(PARTITION, Long.MAX_VALUE)).isEmpty();
        }

        /// A timer that has NOT fired must survive replay — the whole point of putting it in the log.
        @Test
        void ready_replayOfAnUnfiredSchedule_restoresThePendingTimer() {
            replicate(EntityLogRecord.upsert(KEY, bytes("state")));
            replicate(EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));

            var due = readyFold().dueTimers(PARTITION, FIRE_AT);

            assertThat(due).hasSize(1);
            assertThat(new String(due.getFirst().command(), StandardCharsets.UTF_8)).isEqualTo("cmd");
        }

        /// The append path and catch-up meeting over the same offsets. A timer record the append path
        /// already applied is ACCOUNTED by catch-up, never re-applied, so the checkpointable watermark
        /// reaches the head instead of stalling below a parked offset — a stall would hold every later
        /// checkpoint back forever, and a double count would let a checkpoint claim records the fold never
        /// absorbed.
        @Test
        void caughtUp_directlyAppliedTimerRecord_isAccounted_soTheWatermarkReachesHead() {
            replicate(EntityLogRecord.upsert("order-2", bytes("seed")));

            var fold = readyFold();

            replicate(EntityLogRecord.upsert("order-2", bytes("replicated")));            // offset 1
            fold.apply(PARTITION, 2, EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));
            replicate(EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd"))); // its log copy at offset 2

            assertThat(fold.caughtUp(PARTITION).await().isSuccess()).isTrue();

            assertThat(fold.checkpointableThrough(PARTITION)).isEqualTo(2L);
            assertThat(text(fold, "order-2")).isEqualTo("replicated");
            assertThat(fold.isTimerPending(PARTITION, KEY, TOKEN)).isTrue();
        }
    }

    /// A timer payload met during REPLAY comes from bytes another build wrote, so it fails the fold rather
    /// than being skipped. A fold that silently dropped what it could not read would produce state that is
    /// wrong in a way no later read can detect — the same rule the envelope already follows.
    @Nested
    class RefusesMalformedPayloads {
        @Test
        void ready_fails_forAMalformedTimerPayloadInTheLog() {
            substrate.appendRaw(new EntityLogRecord(EntityLogRecord.Op.TIMER_SCHEDULE, KEY, bytes("junk")).encode());

            EntityFold.entityFold(KEYSPACE, substrate)
                      .ready(PARTITION)
                      .await()
                      .onSuccess(_ -> fail("a timer payload this build cannot parse must fail the fold"))
                      .onFailure(cause -> assertThat(cause.stream()).hasAtLeastOneElementOfType(EntityLogError.FoldFailed.class));
        }

        @Test
        void caughtUp_fails_forAMalformedTimerPayloadReplicatedIn() {
            replicate(EntityLogRecord.upsert(KEY, bytes("seed")));

            var fold = readyFold();

            substrate.appendRaw(new EntityLogRecord(EntityLogRecord.Op.TIMER_FIRE, KEY, bytes("junk")).encode());

            fold.caughtUp(PARTITION)
                .await()
                .onSuccess(_ -> fail("a timer payload this build cannot parse must fail the catch-up"))
                .onFailure(cause -> assertThat(cause.stream()).hasAtLeastOneElementOfType(EntityLogError.FoldFailed.class));
        }
    }

    /// A checkpoint pins retention: everything at or below its offset MAY be reclaimed. A timer scheduled
    /// below that offset and not carried in the snapshot would therefore be lost exactly when the log that
    /// proved it existed is reclaimed — silently, and only on the node that took over.
    @Nested
    class Checkpointing {
        @Test
        void snapshot_carriesPendingTimers_soARestoredFoldStillHasThem() {
            var source = readyFold();

            source.apply(PARTITION, 0, EntityLogRecord.upsert(KEY, bytes("state")));
            source.apply(PARTITION, 1, EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));

            var restored = restoreFrom(source);

            assertThat(restored.isTimerPending(PARTITION, KEY, TOKEN)).isTrue();
            assertThat(new String(restored.dueTimers(PARTITION, FIRE_AT).getFirst().command(), StandardCharsets.UTF_8))
                .isEqualTo("cmd");
            assertThat(text(restored, KEY)).isEqualTo("state");
        }

        @Test
        void snapshot_omitsFiredTimers() {
            var source = readyFold();

            source.apply(PARTITION, 0, EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));
            source.apply(PARTITION, 1, EntityLogRecord.timerFire(KEY, TOKEN, bytes("after")));

            var restored = restoreFrom(source);

            assertThat(restored.dueTimers(PARTITION, Long.MAX_VALUE)).isEmpty();
            assertThat(text(restored, KEY)).isEqualTo("after");
        }

        @Test
        void snapshot_omitsTimersOfDeletedKeys() {
            var source = readyFold();

            source.apply(PARTITION, 0, EntityLogRecord.upsert(KEY, bytes("state")));
            source.apply(PARTITION, 1, EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));
            source.apply(PARTITION, 2, EntityLogRecord.delete(KEY));

            assertThat(restoreFrom(source).dueTimers(PARTITION, Long.MAX_VALUE)).isEmpty();
        }

        /// A key whose timers have all been cancelled keeps an empty inner map in the live fold; it must
        /// not reach the checkpoint as a present-but-empty entry, or a snapshot would grow with every key
        /// that ever held a timer rather than with the timers that exist.
        @Test
        void snapshot_omitsKeysWhoseTimersWereAllCancelled() {
            var source = readyFold();

            source.apply(PARTITION, 0, EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));
            source.apply(PARTITION, 1, EntityLogRecord.timerCancel(KEY, TOKEN));

            var nothingAtAll = EntityFold.entityFold(KEYSPACE, new GrowableSubstrate()).snapshot(PARTITION);

            assertThat(source.snapshot(PARTITION))
                .as("a key whose timers were all cancelled must contribute nothing to the checkpoint")
                .isEqualTo(nothingAtAll);
        }

        /// The fold's own path into the narrowest-layout rule. A snapshot with no pending timer must stay
        /// readable by a build predating I4; only a snapshot that actually carries a timer takes the
        /// version step, and a fold whose timers have all fired or been cancelled emits the pre-timer
        /// layout again.
        @Test
        void snapshot_writesThePreTimerLayout_whenNoTimerIsPending() {
            var fold = readyFold();

            fold.apply(PARTITION, 0, EntityLogRecord.upsert(KEY, bytes("state")));

            assertThat(fold.snapshot(PARTITION)[0]).isEqualTo(EntityFoldSnapshot.VERSION_WITHOUT_TIMERS);
        }

        @Test
        void snapshot_writesTheTimerLayout_whenATimerIsPending() {
            var fold = readyFold();

            fold.apply(PARTITION, 0, EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));

            assertThat(fold.snapshot(PARTITION)[0]).isEqualTo(EntityFoldSnapshot.VERSION);
        }

        /// ...and a fold that fired its only timer drops back to the narrow layout, rather than latching the
        /// wider one for the life of the keyspace.
        @Test
        void snapshot_returnsToThePreTimerLayout_afterTheLastTimerFires() {
            var fold = readyFold();

            fold.apply(PARTITION, 0, EntityLogRecord.timerSchedule(KEY, TOKEN, FIRE_AT, bytes("cmd")));
            fold.apply(PARTITION, 1, EntityLogRecord.timerFire(KEY, TOKEN, bytes("after")));

            assertThat(fold.snapshot(PARTITION)[0]).isEqualTo(EntityFoldSnapshot.VERSION_WITHOUT_TIMERS);
        }

        private EntityFold restoreFrom(EntityFold source) {
            var target = new GrowableSubstrate();

            target.checkpoint = Option.some(new Checkpoint(source.checkpointableThrough(PARTITION),
                                                           source.snapshot(PARTITION)));

            var restored = EntityFold.entityFold(KEYSPACE, target);

            restored.ready(PARTITION).await().onFailure(cause -> fail("restored fold must be ready: " + cause.message()));

            return restored;
        }
    }

    // --- fixtures ---

    private EntityFold readyFold() {
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        fold.ready(PARTITION).await().onFailure(cause -> fail("fold must be ready: " + cause.message()));

        return fold;
    }

    private void replicate(EntityLogRecord record) {
        substrate.appendRaw(record.encode());
    }

    private static String text(EntityFold fold, String key) {
        return fold.get(PARTITION, key)
                   .map(value -> new String(value, StandardCharsets.UTF_8))
                   .or(() -> fail("key " + key + " must be present"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Checkpoint(long throughOffset, byte[] snapshot) {}

    /// A log that GROWS underneath the fold — the fold-side view of replication: the log gained records and
    /// nobody told it.
    private static final class GrowableSubstrate implements EntityLogSubstrate {
        private final List<byte[]> log = new ArrayList<>();
        private Option<Checkpoint> checkpoint = Option.none();

        void appendRaw(byte[] record) {
            log.add(record);
        }

        @Override
        public Result<Unit> ensureLog(String keyspace, int partitionCount, int replicationFactor, int minSyncReplicas) {
            return Result.unitResult();
        }

        @Override
        public Promise<Long> append(String keyspace, int partition, byte[] record) {
            log.add(record);

            return Promise.success((long) log.size() - 1);
        }

        @Override
        public Promise<List<byte[]>> read(String keyspace, int partition, long fromOffset, int maxRecords) {
            if (fromOffset < 0 || fromOffset >= log.size()) {
                return Promise.success(List.of());
            }

            var to = (int) Math.min(log.size(), fromOffset + maxRecords);

            return Promise.success(List.copyOf(log.subList((int) fromOffset, to)));
        }

        @Override
        public long headOffset(String keyspace, int partition) {
            return log.size() - 1L;
        }

        @Override
        public long earliestRetainedOffset(String keyspace, int partition) {
            return 0L;
        }

        @Override
        public boolean localLogComplete(String keyspace, int partition) {
            return true;
        }

        @Override
        public boolean holdsPartition(String keyspace, int partition) {
            return true;
        }

        @Override
        public Promise<Unit> saveCheckpoint(String keyspace, int partition, long throughOffset, byte[] snapshot) {
            checkpoint = Option.some(new Checkpoint(throughOffset, snapshot));

            return Promise.unitPromise();
        }

        @Override
        public Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition) {
            return Promise.success(checkpoint.map(c -> EntityCheckpoint.entityCheckpoint(c.throughOffset(), c.snapshot())));
        }
    }
}
