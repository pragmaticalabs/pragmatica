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


/// #1302 / #1330 — the per-(keyspace, partition) checkpoint lag: log head minus the COMMITTED checkpoint
/// in consensus KV, for the partitions this node OWNS, and its node-wide maximum reported to the lag sink
/// after every tick, whatever the tick's outcome.
///
/// A REAL [EntityFold] over a small substrate. The committed checkpoint and the ownership answer are the
/// substrate fake's, standing in for the consensus-KV pointer and the entity's owner admission.
class EntityCheckpointDriverLagTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITION = 0;

    /// The lag is head minus the committed checkpoint, and it follows the COMMITTED pointer: while
    /// nothing is committed it counts from offset -1 and grows with the log; once the cluster commits a
    /// checkpoint it drops to exactly head minus that.
    @Test
    void checkpointLag_tracksHeadMinusTheCommittedCheckpoint() {
        var substrate = new LagSubstrate();
        var reported = new CopyOnWriteArrayList<Long>();
        var driver = driverReportingTo(reported, substrate);

        substrate.appendUpserts(3);
        driver.register(KEYSPACE, 1, EntityFold.entityFold(KEYSPACE, substrate), substrate, substrate::owns);
        driver.tick();

        assertThat(lagOf(driver)).describedAs("head 2, nothing committed: the lag counts from offset -1")
                                 .containsEntry(PARTITION, 3L);

        substrate.appendUpserts(5);
        driver.tick();

        assertThat(lagOf(driver)).describedAs("with nothing committed the lag grows with the head (now 7)")
                                 .containsEntry(PARTITION, 8L);

        substrate.committed = Option.some(2L);
        driver.tick();

        assertThat(lagOf(driver)).describedAs("committed through 2 leaves head 7 minus 2")
                                 .containsEntry(PARTITION, 5L);
        assertThat(reported).describedAs("every tick reports the node-wide maximum to the lag sink")
                            .containsExactly(3L, 8L, 5L);
    }

    /// #1330 B1 — a LOCAL save the cluster did not commit must never count. A fenced (lower) save still
    /// resolves success (#700), so a baseline taken from this node's own record would claim coverage the
    /// committed pointer does not have.
    @Test
    void checkpointLag_ignoresALocalSaveTheClusterDidNotCommit() {
        var substrate = new LagSubstrate();
        var driver = driverReportingTo(new CopyOnWriteArrayList<>(), substrate);
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        substrate.appendUpserts(10);
        fold.ready(PARTITION).await().onFailure(cause -> fail("fold must be ready: " + cause.message()));
        driver.register(KEYSPACE, 1, fold, substrate, substrate::owns);
        driver.tick();

        assertThat(lagOf(driver)).describedAs("the local save of offset 9 resolved success but committed nothing:"
                                              + " the lag is still measured from offset -1")
                                 .containsEntry(PARTITION, 10L);
    }

    /// #1330 B1 (review probe R1) — a REPLICA that folded the partition once and then saw no reads. Its
    /// fold falls far behind a ring head that replication keeps advancing, while the OWNER commits at the
    /// head. A replica has no lag to report: its fold is a read-side cache, and the owner's committed
    /// checkpoint is current.
    @Test
    void checkpointLag_ofAReplica_isNotReported() {
        var substrate = new LagSubstrate();
        var reported = new CopyOnWriteArrayList<Long>();
        var driver = driverReportingTo(reported, substrate);
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        substrate.owner = false;
        substrate.appendUpserts(3);
        fold.ready(PARTITION).await().onFailure(cause -> fail("fold must be ready: " + cause.message()));
        driver.register(KEYSPACE, 1, fold, substrate, substrate::owns);
        driver.tick();
        substrate.appendUpserts(20_000);
        substrate.committed = Option.some(substrate.head());
        driver.tick();

        assertThat(lagOf(driver)).describedAs("a partition this node does not own is ABSENT, not a lag").isEmpty();
        assertThat(reported.getLast()).isZero();
    }

    /// #1330 B1 — a replica PROMOTED to owner keeps its stale fold; measured against the committed pointer
    /// (the previous owner committed at the head) it reports no lag, so the takeover raises no alert — on
    /// its first tick or any other.
    @Test
    void checkpointLag_ofAPromotedReplica_raisesNoAlert() {
        var substrate = new LagSubstrate();
        var reported = new CopyOnWriteArrayList<Long>();
        var driver = driverReportingTo(reported, substrate);
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        substrate.owner = false;
        substrate.appendUpserts(3);
        fold.ready(PARTITION).await().onFailure(cause -> fail("fold must be ready: " + cause.message()));
        driver.register(KEYSPACE, 1, fold, substrate, substrate::owns);
        substrate.appendUpserts(20_000);
        substrate.committed = Option.some(substrate.head());
        substrate.owner = true;
        driver.tick();

        assertThat(lagOf(driver)).describedAs("the promoted owner measures from the committed head").containsEntry(PARTITION, 0L);
        assertThat(reported).containsExactly(0L);
    }

    /// #1330 M3 (review probe R2) — a tick whose checkpoint work THROWS must still report the lag. A
    /// checkpointer that throws every tick is the stalled checkpointer this alert exists for; if the throw
    /// also skipped the report, the metric would freeze and the alert could neither fire nor clear.
    @Test
    void tick_thatThrows_stillReportsTheLag() {
        var substrate = new LagSubstrate();
        var reported = new CopyOnWriteArrayList<Long>();
        var driver = driverReportingTo(reported, substrate);
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        substrate.appendUpserts(3);
        fold.ready(PARTITION).await().onFailure(cause -> fail("fold must be ready: " + cause.message()));
        driver.register(KEYSPACE, 1, fold, substrate, substrate::owns);
        substrate.saveThrows = true;
        driver.tick();
        driver.tick();

        assertThat(reported).describedAs("one report per tick, even when the save throws").containsExactly(3L, 3L);
    }

    /// A partition whose head cannot be read this tick is left out of the report; the others still report.
    @Test
    void tick_withAnUnreadableHead_stillReportsTheOtherPartitions() {
        var substrate = new LagSubstrate();
        var reported = new CopyOnWriteArrayList<Long>();
        var driver = driverReportingTo(reported, substrate);

        substrate.appendUpserts(3);
        substrate.headThrowsFor = Option.some(1);
        driver.register(KEYSPACE, 2, EntityFold.entityFold(KEYSPACE, substrate), substrate, substrate::owns);
        driver.tick();

        assertThat(lagOf(driver).keySet()).containsExactly(PARTITION);
        assertThat(reported).containsExactly(3L);
    }

    @Test
    void maxCheckpointLag_isZero_whenNothingIsOwned() {
        var substrate = new LagSubstrate();
        var driver = driverReportingTo(new CopyOnWriteArrayList<>(), substrate);

        substrate.owner = false;
        substrate.appendUpserts(3);
        driver.register(KEYSPACE, 2, EntityFold.entityFold(KEYSPACE, substrate), substrate, substrate::owns);
        driver.tick();

        assertThat(driver.maxCheckpointLag()).isZero();
    }

    private static EntityCheckpointDriver driverReportingTo(List<Long> reported, LagSubstrate substrate) {
        return EntityCheckpointDriver.entityCheckpointDriver(reported::add, substrate::committedThrough);
    }

    private static Map<Integer, Long> lagOf(EntityCheckpointDriver driver) {
        return driver.snapshot()
                     .keyspaces()
                     .getFirst()
                     .checkpointLag();
    }

    /// Partition 0's log with a controllable head, the cluster's committed checkpoint and this node's
    /// ownership. Saves succeed (as a fenced save does, #700), fail, or throw on demand.
    private static final class LagSubstrate implements EntityLogSubstrate {
        private final List<byte[]> records = new CopyOnWriteArrayList<>();
        private volatile boolean saveFails;
        private volatile boolean saveThrows;
        private volatile boolean owner = true;
        private volatile Option<Long> committed = Option.none();
        private volatile Option<Integer> headThrowsFor = Option.none();

        void appendUpserts(int count) {
            for (var i = 0; i < count; i++) {
                records.add(EntityLogRecord.upsert("k" + records.size(), "v".getBytes(StandardCharsets.UTF_8))
                                           .encode());
            }
        }

        long head() {
            return records.size() - 1;
        }

        boolean owns(int partition) {
            return owner;
        }

        Option<Long> committedThrough(String keyspace, int partition) {
            return partition == PARTITION
                   ? committed
                   : Option.none();
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
            if (headThrowsFor.filter(failing -> failing == partition).isPresent()) {
                throw new IllegalStateException("head unreadable");
            }
            return partition == PARTITION
                   ? head()
                   : -1L;
        }

        @Override
        public long earliestRetainedOffset(String keyspace, int partition) {
            return partition == PARTITION && !records.isEmpty()
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
            return Promise.success(Option.none());
        }
    }
}
