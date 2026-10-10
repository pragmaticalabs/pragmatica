// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1395: what the batch-chase loop must keep doing, on the inline and the off-thread path: apply batches in order (the
/// last write of a key wins), fail the replay on a failed read, a malformed record or an empty read below the head, and
/// lose nothing appended during the chase (a catch-up drains it).
class EntityFoldBatchChaseBehaviourTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITION = 3;

    @Test
    @Timeout(60)
    void ordering_sameKeyRewrittenPerBatch_lastWriteWins_inline_andOffThread() {
        for (var offThread : List.of(false, true)) {
            var substrate = new ChaseSubstrate();

            substrate.settleOffThread = offThread;
            substrate.appendSameKey(500);
            var fold = EntityFold.entityFold(KEYSPACE, substrate);

            fold.ready(PARTITION).await().onFailure(cause -> fail("ready: " + cause.message()));
            assertThat(text(fold, "k")).as("offThread=%s", offThread).isEqualTo("v499");
        }
    }

    @Test
    @Timeout(30)
    void failedReadMidChase_failsTheReplay_inline_andOffThread() {
        for (var offThread : List.of(false, true)) {
            var substrate = new ChaseSubstrate();

            substrate.settleOffThread = offThread;
            substrate.appendUpserts(50);
            substrate.failFromRead = 25;
            EntityFold.entityFold(KEYSPACE, substrate).ready(PARTITION).await()
                      .onSuccess(_ -> fail("a replay whose read failed must refuse (offThread=" + offThread + ")"))
                      .onFailure(cause -> assertThat(cause.message()).contains("chase read failure"));
        }
    }

    @Test
    @Timeout(30)
    void emptyBatchBelowTheHead_refusesTheReplay() {
        var substrate = new ChaseSubstrate();

        substrate.appendUpserts(50);
        substrate.emptyAtRead = 20;
        EntityFold.entityFold(KEYSPACE, substrate).ready(PARTITION).await()
                  .onSuccess(_ -> fail("an empty read below the head must refuse"))
                  .onFailure(cause -> assertThat(cause.message()).contains("log ended at offset"));
    }

    @Test
    @Timeout(30)
    void malformedRecordMidChase_failsTheReplay() {
        var substrate = new ChaseSubstrate();

        substrate.appendUpserts(30);
        substrate.records.add(new byte[]{(byte) 0x7f, 1, 2});
        substrate.appendUpserts(30);
        EntityFold.entityFold(KEYSPACE, substrate).ready(PARTITION).await()
                  .onSuccess(_ -> fail("a malformed record must refuse"));
    }

    @Test
    @Timeout(30)
    void appendDuringTheChase_isNotLost_aCatchUpDrainsIt() {
        var substrate = new ChaseSubstrate();

        substrate.appendUpserts(100);
        substrate.appendAtRead = 50;
        substrate.appendCount = 40;
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        fold.ready(PARTITION).await().onFailure(cause -> fail("ready: " + cause.message()));
        assertThat(text(fold, "k99")).isEqualTo("v99");
        fold.caughtUp(PARTITION).await().onFailure(cause -> fail("catch-up: " + cause.message()));
        assertThat(text(fold, "k139")).as("the records appended during the chase").isEqualTo("v139");
    }

    /// The off-thread path resumes the loop from `onResult`, which swallows a throw: a read that THROWS on a resumed
    /// batch must still fail the replay (contained in `containedRead`), not leave it unsettled.
    @Test
    @Timeout(30)
    void offThread_aReadThatThrowsOnAResumedBatch_failsTheReplay_ratherThanHanging() {
        var substrate = new ChaseSubstrate();

        substrate.settleOffThread = true;
        substrate.appendUpserts(50);
        substrate.throwFromRead = 25;

        EntityFold.entityFold(KEYSPACE, substrate)
                  .ready(PARTITION)
                  .await(TimeSpan.timeSpan(10).seconds())
                  .onSuccess(_ -> fail("a replay whose resumed read threw must refuse"))
                  .onFailure(cause -> assertThat(cause.message()).contains("substrate threw"));
    }

    /// Same for a step that THROWS on a resumed batch (a record the decoder cannot even read, `null`): contained in
    /// `applyBatch`, so the replay refuses promptly instead of waiting out the await bound.
    @Test
    @Timeout(30)
    void offThread_aStepThatThrowsOnAResumedBatch_failsTheReplay_ratherThanHanging() {
        var substrate = new ChaseSubstrate();

        substrate.settleOffThread = true;
        substrate.appendUpserts(20);
        substrate.records.add(null);
        substrate.appendUpserts(20);

        var started = System.nanoTime();
        var result = EntityFold.entityFold(KEYSPACE, substrate).ready(PARTITION).await(TimeSpan.timeSpan(10).seconds());
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(result.isFailure()).as("a replay whose step threw must refuse").isTrue();
        assertThat(elapsedMillis).as("it must settle, not wait out the 10 s await bound").isLessThan(5_000L);
    }

    private static String text(EntityFold fold, String key) {
        return fold.get(PARTITION, key)
                   .map(value -> new String(value, StandardCharsets.UTF_8))
                   .or(() -> fail("key " + key + " must be present"));
    }

    /// Serves ONE record per read whatever `maxRecords` asks (a legal "up to" answer), so a log of N records is N
    /// batches; the knobs fail, empty or extend the log at a chosen read.
    private static final class ChaseSubstrate implements EntityLogSubstrate {
        final List<byte[]> records = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger reads = new AtomicInteger();
        private volatile boolean settleOffThread;
        volatile int throwFromRead = -1;
        volatile int failFromRead = -1;
        volatile int emptyAtRead = -1;
        volatile int appendAtRead = -1;
        volatile int appendCount;

        void appendUpserts(int count) {
            var first = records.size();

            for (var i = first; i < first + count; i++) {
                records.add(EntityLogRecord.upsert("k" + i, ("v" + i).getBytes(StandardCharsets.UTF_8)).encode());
            }
        }

        void appendSameKey(int count) {
            for (var i = 0; i < count; i++) {
                records.add(EntityLogRecord.upsert("k", ("v" + i).getBytes(StandardCharsets.UTF_8)).encode());
            }
        }

        @Override
        public Result<Unit> ensureLog(String keyspace, int partitionCount, ReplicationFactors replication) {
            return Result.unitResult();
        }

        @Override
        public Promise<Long> append(String keyspace, int partition, byte[] record) {
            records.add(record);

            return Promise.success((long) records.size() - 1);
        }

        @Override
        public Promise<List<byte[]>> read(String keyspace, int partition, long fromOffset, int maxRecords) {
            var count = reads.incrementAndGet();

            if (throwFromRead > 0 && count >= throwFromRead) {
                throw new IllegalStateException("substrate threw instead of failing its promise");
            }

            if (failFromRead > 0 && count >= failFromRead) {
                return new EntityLogError.MalformedRecord("chase read failure").promise();
            }

            if (count == appendAtRead) {
                appendUpserts(appendCount);
            }

            var snapshot = new ArrayList<>(records);
            var start = (int) fromOffset;
            List<byte[]> batch = count == emptyAtRead ? List.of() : start < 0 || start >= snapshot.size() ? List.of() : snapshot.subList(start, start + 1);

            if (!settleOffThread) {
                return Promise.success(batch);
            }

            var promise = Promise.<List<byte[]>>promise();

            Thread.ofVirtual().start(() -> promise.succeed(batch));

            return promise;
        }

        @Override
        public long headOffset(String keyspace, int partition) {
            return records.size() - 1L;
        }

        @Override
        public long earliestRetainedOffset(String keyspace, int partition) {
            return records.isEmpty() ? -1L : 0L;
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
            return Promise.unitPromise();
        }

        @Override
        public Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition) {
            return Promise.success(Option.none());
        }
    }
}
