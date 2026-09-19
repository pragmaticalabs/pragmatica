// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1302 — the per-(keyspace, partition) checkpoint lag: log head minus the last checkpoint this node
/// committed, for the partitions it folds, and its node-wide maximum reported to the lag sink every tick.
///
/// A REAL [EntityFold] over a small substrate, because which partitions carry a lag is decided by the
/// fold's own checkpoint candidate — a stubbed fold would assert the fixture's idea of "folded".
class EntityCheckpointDriverLagTest {
    private static final String KEYSPACE = "orders";
    private static final int FOLDED = 0;

    /// While every checkpoint save fails, the lag is the whole head distance from offset -1 and grows
    /// with the log; once a save lands, it drops to exactly head minus the checkpointed offset.
    @Test
    void checkpointLag_tracksHeadMinusLastCommittedCheckpoint() {
        var substrate = new LagSubstrate();
        var reported = new CopyOnWriteArrayList<Long>();
        var driver = EntityCheckpointDriver.entityCheckpointDriver(reported::add);
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        substrate.appendUpserts(3);
        fold.ready(FOLDED).await().onFailure(cause -> fail("fold must be ready: " + cause.message()));
        driver.register(KEYSPACE, 2, fold, substrate);

        substrate.saveFails = true;
        driver.tick();

        assertThat(lagOf(driver)).describedAs("head 2, nothing checkpointed: the lag counts from offset -1")
                                 .containsEntry(FOLDED, 3L);

        substrate.appendUpserts(5);
        driver.tick();

        assertThat(lagOf(driver)).describedAs("a stalled checkpointer's lag grows with the log head (now 7)")
                                 .containsEntry(FOLDED, 8L);

        substrate.saveFails = false;
        driver.tick();

        assertThat(lagOf(driver)).describedAs("a checkpoint through offset 2 leaves head 7 minus 2")
                                 .containsEntry(FOLDED, 5L);
        assertThat(reported).describedAs("every tick reports the node-wide maximum to the lag sink")
                            .containsExactly(3L, 8L, 5L);
    }

    /// A TAKEOVER: the previous owner committed a checkpoint through offset 4, and this node's fold resumed
    /// from it. The lag is head minus that RESUMED checkpoint — the true replay distance — even before this
    /// node has committed a checkpoint of its own. Measuring from offset -1 instead would report the whole
    /// log and raise a spurious alert on every failover.
    @Test
    void checkpointLag_afterTakeover_isHeadMinusTheResumedCheckpoint() {
        var substrate = new LagSubstrate();
        var reported = new CopyOnWriteArrayList<Long>();
        var driver = EntityCheckpointDriver.entityCheckpointDriver(reported::add);
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        substrate.appendUpserts(10);
        substrate.committedCheckpoint = Option.some(EntityLogSubstrate.EntityCheckpoint.entityCheckpoint(4,
                                                                                                        EntityFoldSnapshot.encode(Map.of(),
                                                                                                                                  Map.of())));
        fold.ready(FOLDED).await().onFailure(cause -> fail("fold must resume from the checkpoint: " + cause.message()));
        driver.register(KEYSPACE, 2, fold, substrate);

        substrate.saveFails = true;
        driver.tick();

        assertThat(lagOf(driver)).describedAs("head 9 minus the resumed checkpoint 4, not minus -1")
                                 .containsEntry(FOLDED, 5L);
        assertThat(reported).containsExactly(5L);
    }

    /// #1330 M3 (review probe R2) — a tick whose checkpoint work THROWS must still report the lag. A
    /// checkpointer that throws every tick is the stalled checkpointer this alert exists for; if the throw
    /// also skipped the report, the metric would freeze and the alert could neither fire nor clear.
    @Test
    void tick_thatThrows_stillReportsTheLag() {
        var substrate = new LagSubstrate();
        var reported = new CopyOnWriteArrayList<Long>();
        var driver = EntityCheckpointDriver.entityCheckpointDriver(reported::add);
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        substrate.appendUpserts(3);
        fold.ready(FOLDED).await().onFailure(cause -> fail("fold must be ready: " + cause.message()));
        driver.register(KEYSPACE, 1, fold, substrate);
        substrate.saveThrows = true;
        driver.tick();
        driver.tick();

        assertThat(reported).describedAs("one report per tick, even when the save throws").hasSize(2);
    }

    /// Only partitions this node FOLDS carry a lag: the second partition was never rebuilt here, so its
    /// recovery is not bounded by this node's checkpoints and it must be absent, not reported as 0.
    @Test
    void checkpointLag_isAbsentForPartitionsThisNodeDoesNotFold() {
        var substrate = new LagSubstrate();
        var driver = EntityCheckpointDriver.entityCheckpointDriver();
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        substrate.appendUpserts(1);
        fold.ready(FOLDED).await().onFailure(cause -> fail("fold must be ready: " + cause.message()));
        driver.register(KEYSPACE, 2, fold, substrate);
        driver.tick();

        assertThat(lagOf(driver).keySet()).containsExactly(FOLDED);
    }

    @Test
    void maxCheckpointLag_isZero_whenNothingIsFolded() {
        var substrate = new LagSubstrate();
        var driver = EntityCheckpointDriver.entityCheckpointDriver();

        driver.register(KEYSPACE, 2, EntityFold.entityFold(KEYSPACE, substrate), substrate);
        driver.tick();

        assertThat(driver.maxCheckpointLag()).isZero();
    }

    private static Map<Integer, Long> lagOf(EntityCheckpointDriver driver) {
        return driver.snapshot()
                     .keyspaces()
                     .getFirst()
                     .checkpointLag();
    }

    /// One partition's log with a controllable head; checkpoint saves succeed or fail on demand.
    private static final class LagSubstrate implements EntityLogSubstrate {
        private final List<byte[]> records = new CopyOnWriteArrayList<>();
        private volatile boolean saveFails;
        // A save that THROWS rather than returning a failed promise — the shape a tick's catch sees.
        private volatile boolean saveThrows;
        // The checkpoint a previous owner committed, which a fold on this node resumes from.
        private volatile Option<EntityCheckpoint> committedCheckpoint = Option.none();

        void appendUpserts(int count) {
            for (var i = 0; i < count; i++) {
                records.add(EntityLogRecord.upsert("k" + records.size(), "v".getBytes(StandardCharsets.UTF_8))
                                           .encode());
            }
        }

        @Override
        public Result<Unit> ensureLog(String keyspace, int partitionCount, int replicationFactor, int minSyncReplicas) {
            return Result.unitResult();
        }

        @Override
        public Promise<Long> append(String keyspace, int partition, byte[] record) {
            records.add(record);

            return Promise.success((long) records.size() - 1);
        }

        @Override
        public Promise<List<byte[]>> read(String keyspace, int partition, long fromOffset, int maxRecords) {
            var snapshot = List.copyOf(records);
            var start = (int) fromOffset;

            return start < 0 || start >= snapshot.size()
                   ? Promise.success(List.of())
                   : Promise.success(snapshot.subList(start, Math.min(snapshot.size(), start + maxRecords)));
        }

        @Override
        public long headOffset(String keyspace, int partition) {
            return partition == FOLDED
                   ? records.size() - 1
                   : -1L;
        }

        @Override
        public long earliestRetainedOffset(String keyspace, int partition) {
            return partition == FOLDED && !records.isEmpty()
                   ? 0L
                   : -1L;
        }

        @Override
        public boolean holdsPartition(String keyspace, int partition) {
            return true;
        }

        @Override
        public boolean localLogComplete(String keyspace, int partition) {
            return true;
        }

        @Override
        public Promise<Unit> saveCheckpoint(String keyspace, int partition, long throughOffset, byte[] snapshot) {
            if (saveThrows) {
                throw new IllegalStateException("substrate threw");
            }
            return saveFails
                   ? Causes.cause("checkpoint store unavailable").promise()
                   : Promise.unitPromise();
        }

        @Override
        public Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition) {
            return Promise.success(committedCheckpoint);
        }
    }
}
