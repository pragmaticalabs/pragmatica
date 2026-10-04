// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1395 (sibling of #1392): the fold chases a replay, and a catch-up, one batch at a time, and a read that settles
/// synchronously (a memory-tier substrate does) used to run the next batch's continuation inline, nesting about 7
/// frames per batch. The chase is now a loop. These pin that the stack depth at a batch read does not grow with the
/// number of batches: the substrate below serves ONE record per read, so a log of N records is N batches, and it
/// records the stack depth of every read.
///
/// The bound is relative, N=2,000 against N=20: an absolute figure would depend on the test runner's own depth.
/// Reverted to the recursive chase, 2,000 batches nest about 14,000 frames more than 20 do (and may overflow the
/// stack outright, which fails the replay itself).
class EntityFoldBatchChaseDepthTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITION = 3;
    private static final int SMALL = 20;
    private static final int LARGE = 2_000;
    /// A loop adds nothing per batch; the slack covers incidental differences between the first and later reads.
    private static final int SLACK_FRAMES = 60;

    @Test
    @Timeout(60)
    void rebuildReplay_stackDepthDoesNotGrowWithTheBatchCount() {
        var small = new OneRecordPerReadSubstrate();
        var large = new OneRecordPerReadSubstrate();

        small.appendUpserts(SMALL);
        large.appendUpserts(LARGE);

        var smallFold = ready(small);
        var largeFold = ready(large);

        assertThat(large.reads.get()).as("fixture premise: one batch per record").isGreaterThanOrEqualTo(LARGE);
        assertThat(text(largeFold, "k" + (LARGE - 1))).as("the whole history was replayed").isEqualTo("v" + (LARGE - 1));
        assertThat(text(smallFold, "k" + (SMALL - 1))).isEqualTo("v" + (SMALL - 1));
        assertThat(large.maxDepth()).as("max stack depth at a read: %d batches vs %d", LARGE, SMALL)
                                    .isLessThanOrEqualTo(small.maxDepth() + SLACK_FRAMES);
    }

    @Test
    @Timeout(60)
    void catchUp_stackDepthDoesNotGrowWithTheBatchCount() {
        var small = new OneRecordPerReadSubstrate();
        var large = new OneRecordPerReadSubstrate();
        var smallFold = ready(small);
        var largeFold = ready(large);

        small.appendUpserts(SMALL);
        large.appendUpserts(LARGE);
        small.depths.clear();
        large.depths.clear();

        smallFold.caughtUp(PARTITION).await().onFailure(cause -> fail("catch-up must drain: " + cause.message()));
        largeFold.caughtUp(PARTITION).await().onFailure(cause -> fail("catch-up must drain: " + cause.message()));

        assertThat(text(largeFold, "k" + (LARGE - 1))).as("the whole gap was drained").isEqualTo("v" + (LARGE - 1));
        assertThat(large.maxDepth()).as("max stack depth at a read: %d batches vs %d", LARGE, SMALL)
                                    .isLessThanOrEqualTo(small.maxDepth() + SLACK_FRAMES);
    }

    /// The pending path: every read settles on ANOTHER thread, so each batch resumes the loop from `onResult`. The
    /// state must still be complete, and the replay must still end.
    @Test
    @Timeout(60)
    void rebuildReplay_withReadsThatSettleLater_foldsEveryRecord() {
        var substrate = new OneRecordPerReadSubstrate();

        substrate.settleOffThread = true;
        substrate.appendUpserts(300);

        var fold = ready(substrate);

        assertThat(text(fold, "k0")).isEqualTo("v0");
        assertThat(text(fold, "k299")).isEqualTo("v299");
    }

    /// A substrate that throws instead of returning a promise ends the chase as a failure; it must not leave the
    /// replay unsettled.
    @Test
    @Timeout(30)
    void rebuildReplay_withASubstrateThatThrowsMidway_failsRatherThanHanging() {
        var substrate = new OneRecordPerReadSubstrate();

        substrate.appendUpserts(50);
        substrate.throwFromRead = 25;

        EntityFold.entityFold(KEYSPACE, substrate)
                  .ready(PARTITION)
                  .await()
                  .onSuccess(_ -> fail("a replay whose read threw must refuse"))
                  .onFailure(cause -> assertThat(cause.message()).contains("substrate threw"));
    }

    private static EntityFold ready(OneRecordPerReadSubstrate substrate) {
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        fold.ready(PARTITION).await().onFailure(cause -> fail("fold must be ready: " + cause.message()));

        return fold;
    }

    private static String text(EntityFold fold, String key) {
        return fold.get(PARTITION, key)
                   .map(value -> new String(value, StandardCharsets.UTF_8))
                   .or(() -> fail("key " + key + " must be present"));
    }

    /// Serves ONE record per read whatever `maxRecords` asks (a legal "up to" answer), so a log of N records is N
    /// batches, and records the stack depth at every read.
    private static final class OneRecordPerReadSubstrate implements EntityLogSubstrate {
        private final List<byte[]> records = new CopyOnWriteArrayList<>();
        private final List<Integer> depths = new CopyOnWriteArrayList<>();
        private final AtomicInteger reads = new AtomicInteger();
        private volatile boolean settleOffThread;
        private volatile int throwFromRead = -1;

        void appendUpserts(int count) {
            var first = records.size();

            for (var i = first; i < first + count; i++) {
                records.add(EntityLogRecord.upsert("k" + i, ("v" + i).getBytes(StandardCharsets.UTF_8)).encode());
            }
        }

        int maxDepth() {
            return new ArrayList<>(depths).stream().mapToInt(Integer::intValue).max().orElse(0);
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

            depths.add(Thread.currentThread().getStackTrace().length);

            if (throwFromRead > 0 && count >= throwFromRead) {
                throw new IllegalStateException("substrate threw instead of failing its promise");
            }

            var snapshot = List.copyOf(records);
            var start = (int) fromOffset;
            List<byte[]> batch = start < 0 || start >= snapshot.size() ? List.of() : snapshot.subList(start, start + 1);

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
