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

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.entity.EntityLogSubstrate.CheckpointLoad;
import org.pragmatica.aether.resource.entity.EntityLogSubstrate.UnreadableCause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;

/// #3303 A0b (I-LOST): [EntityFold] consumes the TYPED checkpoint outcome. Pinned over a fake substrate whose `loadCheckpoint` and
/// `loadCheckpointOutcome` answer DIFFERENTLY, so a fold that called the legacy method goes red, and the EF-side WARN for an
/// `Unreadable` outcome is read off the log (cause and block id). Lives beside the real-substrate arm in `SealedHistoryRecoveryTest`,
/// which cannot tell which method the fold calls.
class EntityFoldCheckpointOutcomeTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITION = 3;

    private LogCapture warns;

    @BeforeEach
    void setUp() {
        warns = LogCapture.capture(EntityFold.class, Level.WARN, true);
    }

    @AfterEach
    void tearDown() {
        warns.close();
    }

    @Test
    void anUnreadableOutcome_warnsOnceWithTheCauseAndTheBlockId_andFoldsFromTheLog() {
        var substrate = new FakeSubstrate(CheckpointLoad.unreadable(UnreadableCause.FETCH_FAILED, "beef03"), true);
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        assertThat(fold.ready(PARTITION).await().isSuccess()).isTrue();

        assertThat(warns.messages()).as("exactly one EF WARN, naming the block and the cause")
                                    .singleElement()
                                    .satisfies(message -> {
                                        assertThat(message).contains("beef03");
                                        assertThat(message).contains("FETCH_FAILED");
                                    });
        assertThat(valueOf(fold)).isEqualTo("from-log");
    }

    /// The legacy answer is a checkpoint seeding "legacy"; the typed answer is Unreadable. A fold that calls the legacy method would
    /// seed from it and read "legacy".
    @Test
    void theFold_readsTheTypedOutcome_notTheLegacyLoad() {
        var substrate = new FakeSubstrate(CheckpointLoad.unreadable(UnreadableCause.BLOCK_LOST, "beef04"), true);
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        assertThat(fold.ready(PARTITION).await().isSuccess()).isTrue();

        assertThat(substrate.legacyLoads.get()).as("EF never calls the legacy loadCheckpoint").isZero();
        assertThat(substrate.outcomeLoads.get()).as("EF asks the typed outcome once").isEqualTo(1);
        assertThat(valueOf(fold)).as("the legacy seed is not used").isEqualTo("from-log");
    }

    @Test
    void anAbsentOutcome_isNotAWarning() {
        var substrate = new FakeSubstrate(CheckpointLoad.absent(), true);

        assertThat(EntityFold.entityFold(KEYSPACE, substrate).ready(PARTITION).await().isSuccess()).isTrue();

        assertThat(warns.messages()).as("an absent checkpoint is not a warning").isEmpty();
    }

    /// A substrate that only knows the legacy load reaches the fold through the DEFAULT `loadCheckpointOutcome`: none is `Absent`
    /// (no WARN), a present checkpoint is `Loaded` (seeds the fold).
    @Test
    void aLegacyOnlySubstrate_noCheckpoint_isAbsentThroughTheDefault_andDoesNotWarn() {
        var substrate = new FakeSubstrate(null, false);

        assertThat(EntityFold.entityFold(KEYSPACE, substrate).ready(PARTITION).await().isSuccess()).isTrue();

        assertThat(warns.messages()).as("the default maps none to Absent, never Unreadable").isEmpty();
    }

    @Test
    void aLegacyOnlySubstrate_withACheckpoint_isLoadedThroughTheDefault() {
        var substrate = new FakeSubstrate(null, true);
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        assertThat(fold.ready(PARTITION).await().isSuccess()).isTrue();

        assertThat(valueOf(fold)).as("the legacy checkpoint seeds the fold").isEqualTo("legacy");
        assertThat(warns.messages()).isEmpty();
    }

    private static String valueOf(EntityFold fold) {
        return new String(fold.get(PARTITION, "k").unwrap(), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /// One log record (offset 0, key `k` = "from-log"). `loadCheckpoint` answers a checkpoint through offset 0 seeding "legacy" when
    /// `legacyCheckpoint` is set; `loadCheckpointOutcome` answers `outcome` when one is given and otherwise is the interface default.
    private static final class FakeSubstrate implements EntityLogSubstrate {
        final List<byte[]> records = new CopyOnWriteArrayList<>();
        final AtomicInteger legacyLoads = new AtomicInteger();
        final AtomicInteger outcomeLoads = new AtomicInteger();
        final CheckpointLoad outcome;
        final boolean legacyCheckpoint;

        FakeSubstrate(CheckpointLoad outcome, boolean legacyCheckpoint) {
            this.outcome = outcome;
            this.legacyCheckpoint = legacyCheckpoint;
            records.add(EntityLogRecord.upsert("k", bytes("from-log")).encode());
        }

        @Override
        public Result<Unit> ensureLog(String keyspace, int partitions, org.pragmatica.aether.slice.ReplicationFactors factors) {
            return Result.unitResult();
        }

        @Override
        public Promise<Long> append(String keyspace, int partition, byte[] record) {
            records.add(record);
            return Promise.success((long) records.size() - 1);
        }

        @Override
        public Promise<List<byte[]>> read(String keyspace, int partition, long fromOffset, int max) {
            var snapshot = List.copyOf(records);
            var start = (int) fromOffset;

            return Promise.success(start >= snapshot.size()
                                   ? List.<byte[]>of()
                                   : snapshot.subList(start, Math.min(snapshot.size(), start + max)));
        }

        @Override
        public long headOffset(String keyspace, int partition) {
            return records.size() - 1;
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
            return Promise.unitPromise();
        }

        @Override
        public Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition) {
            legacyLoads.incrementAndGet();

            return Promise.success(legacyCheckpoint
                                   ? Option.some(EntityCheckpoint.entityCheckpoint(0L, EntityFoldSnapshot.encode(Map.of("k", bytes("legacy")), Map.of())))
                                   : Option.none());
        }

        @Override
        public Promise<CheckpointLoad> loadCheckpointOutcome(String keyspace, int partition) {
            outcomeLoads.incrementAndGet();

            return outcome == null
                   ? EntityLogSubstrate.super.loadCheckpointOutcome(keyspace, partition)
                   : Promise.success(outcome);
        }
    }
}
