// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource.CommittedOwner;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// #2087 (B4): a durable-entity READ is a read, so it serves only what the log has committed. The single writer sequences new
/// mutations against its own pending entries (create, update, delete, timers keep seeing them); a client `get` of a key whose
/// newest record is not committed waits a bounded time for it and then refuses, transiently, rather than reveal a value a
/// failover could still replace. The log here is a fake whose committed position the test moves, standing in for the ring's
/// visible position.
class EntityCommittedReadTest {
    private static final String KEYSPACE = "orders";
    private static final NodeId SELF = new NodeId("self-node");

    private CommitGatedSubstrate substrate;
    private EntityFold fold;
    private DurableEntity<String, Integer, IntOp> entity;

    @BeforeEach
    void setUp() {
        substrate = new CommitGatedSubstrate();
        fold = EntityFold.entityFold(KEYSPACE, substrate);
        entity = PartitionFencedDurableEntity.<String, Integer, IntOp> partitionFencedDurableEntity(KEYSPACE,
                                                                                                    substrate,
                                                                                                    EntityPartitionArc.entityPartitionArc(KEYSPACE, 1),
                                                                                                    new IntSerializer(),
                                                                                                    new IntDeserializer(),
                                                                                                    SELF,
                                                                                                    (_, _) -> Option.some(new CommittedOwner(SELF, Epoch.ZERO)),
                                                                                                    Option.none(),
                                                                                                    Option.some((_, _) -> Promise.success(Unit.unit())));
    }

    @Test
    void get_ofAKeyWithAnUnacknowledgedWrite_isRefusedTransiently_andAnUntouchedKeyIsServed() {
        substrate.commitThrough(-1L);
        append("held", 5);
        substrate.commitThrough(0L);
        append("pending", 7);

        assertThat(entity.get("held").await().unwrap()).as("committed record").isEqualTo(Option.some(5));
        assertThat(entity.get("untouched").await().unwrap()).as("a key nothing wrote").isEqualTo(Option.none());

        var refused = entity.get("pending").await();

        assertThat(refused.isFailure()).as("the unacknowledged value must not be shown").isTrue();
        refused.onFailure(cause -> assertThat(cause).isInstanceOf(EntityLogError.NotYetCommitted.class)
                                                    .isInstanceOf(org.pragmatica.lang.Cause.Transient.class));
    }

    @Test
    void get_waitsForTheRecordToCommit_andThenAnswers() {
        append("k", 9);
        substrate.commitThrough(-1L);
        append("k", 10);

        var read = entity.get("k");

        assertThat(read.isResolved()).as("waiting for the acknowledgement of offset 1").isFalse();

        substrate.commitThrough(1L);

        assertThat(read.await().unwrap()).isEqualTo(Option.some(10));
    }

    @Test
    void ownerSequencing_seesThePendingState_whileTheReadDoesNot() {
        substrate.commitThrough(-1L);
        append("k", 3);

        var updated = entity.update("k", new IntOp.Add(4)).await();

        assertThat(updated.isSuccess()).as("the writer sequences against its own pending record").isTrue();
        int state = updated.fold(cause -> -1, value -> value);

        assertThat(state).isEqualTo(7);
        assertThat(entity.get("k").await().isFailure()).as("but a client read still waits for the commit").isTrue();
    }

    @Test
    void aCheckpointNeverClaimsAnUncommittedOffset() {
        append("a", 1);
        append("b", 2);
        substrate.commitThrough(0L);

        var candidate = fold.checkpointCandidate(0, -1L);

        assertThat(candidate.map(EntityFold.CheckpointCandidate::throughOffset).or(-2L)).as("applied through 1, committed through 0").isEqualTo(0L);
        assertThat(fold.checkpointableThrough(0)).isEqualTo(0L);
    }

    @Test
    void theOwnersOwnWrite_afterTheFoldIsBuilt_isHeldBackFromReadsUntilItCommits() {
        substrate.commitThrough(-1L);
        entity.get("warm").await();

        assertThat(entity.create("mine", 3).await().isSuccess()).as("the writer's own create completes (its barrier is met in this fixture)").isTrue();

        var refused = entity.get("mine").await();

        assertThat(refused.isFailure()).as("applied on the append path, not committed by the log").isTrue();
        refused.onFailure(cause -> assertThat(cause).isInstanceOf(EntityLogError.NotYetCommitted.class));

        substrate.commitThrough(0L);

        assertThat(entity.get("mine").await().unwrap()).isEqualTo(Option.some(3));
    }

    @Test
    void aRecordLandingBehindTheFoldsBack_isHeldBackUntilItCommits() {
        substrate.commitThrough(-1L);
        entity.get("warm").await();

        substrate.appendRaw(EntityLogRecord.upsert("late", "8".getBytes(StandardCharsets.UTF_8)).encode());

        var refused = entity.get("late").await();

        assertThat(refused.isFailure()).as("applied by catch-up, not yet committed").isTrue();
        refused.onFailure(cause -> assertThat(cause).isInstanceOf(EntityLogError.NotYetCommitted.class));

        substrate.commitThrough(0L);

        assertThat(entity.get("late").await().unwrap()).isEqualTo(Option.some(8));
    }

    /// Every write is applied BEFORE its acknowledgement, so every written key is tracked; without the sweep the map would grow with
    /// every key ever written and never read.
    @Test
    void pendingKeys_areSweptOnceTheyCommit_soTheMapTracksTheUnacknowledgedWindow() {
        substrate.commitThrough(-1L);
        fold.ready(0).await();
        for (var i = 0; i < 1500; i++) {
            append("k" + i, i);
        }
        assertThat(fold.pendingKeys(0)).as("control: all 1500 written keys are tracked while nothing commits").isEqualTo(1500);

        substrate.commitThrough(1499L);
        append("last", 1);

        assertThat(fold.pendingKeys(0)).as("the keys that committed left; only the unacknowledged one remains").isEqualTo(1);
    }

    @Test
    void aCut_dropsTheFold_soTheCutValueIsNeverServed_andTheSurvivingLogIsRebuilt() {
        append("k", 5);
        append("j", 9);
        assertThat(entity.get("k").await().unwrap()).as("control: served before the cut").isEqualTo(Option.some(5));

        substrate.cutTo(0L);

        assertThat(entity.get("k").await().unwrap()).as("k, at offset 0, survived the cut").isEqualTo(Option.some(5));
        assertThat(entity.get("j").await().unwrap()).as("j was cut: the rebuilt fold does not hold it").isEqualTo(Option.none());
    }

    @Test
    void aStaleFold_isNeverCheckpointed_afterACut() {
        append("k", 5);
        append("j", 9);
        assertThat(fold.checkpointableThrough(0)).as("control: before the cut the fold claims what the log committed").isEqualTo(1L);

        substrate.cutTo(0L);

        assertThat(fold.checkpointCandidate(0, -1L).isPresent()).as("the contents hold j, which the cut removed, although offset 0 is still committed").isFalse();
        assertThat(fold.checkpointableThrough(0)).isEqualTo(-1L);
    }

    /// The window between a successful `ready` and the read: a cut in it must not let the read answer from the old fold.
    @Test
    void getCommitted_ofAFoldBuiltBeforeACut_refusesInsteadOfAnswering() {
        append("k", 5);
        assertThat(fold.getCommitted(0, "k").await().unwrap().isPresent()).as("control: served before the cut").isTrue();

        substrate.cutTo(-1L);
        var refused = fold.getCommitted(0, "k").await();

        assertThat(refused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause).isInstanceOf(EntityLogError.FoldInProgress.class));
    }

    /// A rebuild whose replay overlapped a cut holds records the cut removed: it is discarded, never published.
    @Test
    void aRebuildThatOverlappedACut_isDiscarded_andTheRetryPublishesTheSurvivingLog() {
        substrate.log.add(EntityLogRecord.upsert("k", "5".getBytes(StandardCharsets.UTF_8)).encode());
        substrate.cutDuringNextRead = true;

        var first = fold.ready(0).await();

        assertThat(first.isFailure()).as("the cut happened while the rebuild replayed").isTrue();
        assertThat(fold.ready(0).await().isSuccess()).as("the retry rebuilds from the surviving log").isTrue();
        assertThat(fold.get(0, "k").isPresent()).as("offset 0 survived the cut").isTrue();
    }

    /// R7: a catch-up still in flight when the log is cut belongs to the dropped fold. If its memo survived the cut, every later catch-up
    /// would join it and wait for a read that never completes.
    @Test
    void aCatchUpInFlightAtTheCut_doesNotBlockTheCatchUpsAfterIt() {
        append("k", 1);
        substrate.log.add(EntityLogRecord.upsert("a", "2".getBytes(StandardCharsets.UTF_8)).encode());
        substrate.blockNextRead = true;
        var stuck = fold.caughtUp(0);

        assertThat(stuck.isResolved()).as("control: the catch-up read is stuck, its memo is held").isFalse();
        substrate.cutTo(0L);
        assertThat(fold.ready(0).await().isSuccess()).as("the fold is rebuilt from the surviving log").isTrue();
        substrate.log.add(EntityLogRecord.upsert("b", "3".getBytes(StandardCharsets.UTF_8)).encode());

        var landed = fold.caughtUp(0).await(org.pragmatica.lang.io.TimeSpan.timeSpan(3).seconds());

        assertThat(landed.isSuccess()).as("a catch-up after the cut runs instead of joining the dropped one").isTrue();
        assertThat(fold.get(0, "b").isPresent()).isTrue();
    }

    /// A cut that has begun but not finished (the ring has not shrunk yet, or has just shrunk) makes no fold valid: a read is refused,
    /// and a rebuild that read the log in that window is not published.
    @Test
    void whileACutIsInProgress_noFoldIsServed_andARebuildInTheWindowIsNotPublished() {
        append("k", 5);
        assertThat(entity.get("k").await().isSuccess()).as("control").isTrue();

        substrate.cutGeneration++;

        var during = entity.get("k").await();

        assertThat(during.isFailure()).as("odd generation: a cut is between begun and done").isTrue();
        during.onFailure(cause -> assertThat(cause).isInstanceOf(EntityLogError.FoldInProgress.class));

        substrate.cutGeneration++;

        assertThat(entity.get("k").await().unwrap()).as("the cut is done: rebuilt from the log").isEqualTo(Option.some(5));
    }

    /// Checkpoint evidence (B4-cut): a checkpoint claims only committed offsets, and a cut of the uncommitted tail leaves the claim valid.
    @Test
    void aCutAboveTheCommittedPosition_leavesTheCheckpointClaimValid_andTheRebuiltFoldHoldsNoCutRecord() {
        append("k", 1);
        append("j", 2);
        append("z", 3);
        substrate.commitThrough(1L);
        assertThat(fold.checkpointableThrough(0)).as("control: the claim is the committed position, not the applied one").isEqualTo(1L);

        substrate.cutTo(1L);
        fold.ready(0).await();

        assertThat(fold.checkpointableThrough(0)).as("still 1: the cut removed only offset 2, above the claim").isEqualTo(1L);
        assertThat(fold.get(0, "z").isPresent()).as("the rebuilt fold holds nothing from the cut").isFalse();
        assertThat(fold.get(0, "j").isPresent()).isTrue();
    }

    /// A cut BELOW the committed position (outside the replication protocol: acknowledged records are on every in-sync copy) lowers the
    /// committed position with it, so a checkpoint taken afterwards never claims a removed offset.
    @Test
    void aCutBelowTheCommittedPosition_cannotBeFollowedByAClaimAboveTheCut() {
        append("k", 1);
        append("j", 2);
        substrate.commitThrough(1L);
        assertThat(fold.checkpointableThrough(0)).isEqualTo(1L);

        substrate.cutTo(0L);
        fold.ready(0).await();

        assertThat(fold.checkpointableThrough(0)).isEqualTo(0L);
        assertThat(fold.get(0, "j").isPresent()).isFalse();
    }

    /// The probe (#2080's bounded escape cuts acknowledged records): `k` and `j` are committed and checkpointed (claim 1); `j` is cut and a
    /// different record `z` lands at offset 1 and commits. The claim is within the log, but the checkpoint folded `j`.
    @Test
    void aCheckpointTakenOverACutRecord_isDiscarded_andTheRebuildFoldsTheNewLineage() {
        append("k", 1);
        append("j", 2);
        substrate.commitThrough(1L);
        var candidate = fold.checkpointCandidate(0, -1L).unwrap();

        assertThat(candidate.lineage().isPresent()).as("control: the lineage of the record at the claim is recorded").isTrue();
        substrate.saveCheckpoint(KEYSPACE, 0, candidate.throughOffset(), candidate.snapshot(), candidate.lineage()).await();

        substrate.cutTo(0L);
        substrate.appendRaw(EntityLogRecord.upsert("z", "9".getBytes(StandardCharsets.UTF_8)).encode());
        substrate.commitThrough(1L);

        assertThat(fold.ready(0).await().isSuccess()).isTrue();
        assertThat(fold.get(0, "j").isPresent()).as("j was cut: the rebuilt fold must not hold it").isFalse();
        assertThat(fold.get(0, "z").isPresent()).as("z is the new lineage's record at offset 1").isTrue();
    }

    /// Control: where the local record at the claim cannot be read (below the sealed floor, which a cut never crosses) the checkpoint is
    /// accepted and is what makes the unreadable prefix a non-event.
    @Test
    void aCheckpointWhoseClaimThisCopyCannotReadAnyMore_isStillUsed() {
        append("k", 1);
        append("j", 2);
        append("z", 3);
        substrate.commitThrough(2L);
        var through = fold.checkpointCandidate(0, -1L).unwrap();

        substrate.saveCheckpoint(KEYSPACE, 0, 1L, through.snapshot(), substrate.lineageAt(KEYSPACE, 0, 1L)).await();
        substrate.earliestRetained = 2L;
        substrate.sealedBelow = 2L;
        substrate.cutGeneration += 2;

        assertThat(fold.ready(0).await().isSuccess()).as("the log below 2 is gone; only the checkpoint can seed the fold").isTrue();
        assertThat(fold.get(0, "k").isPresent()).isTrue();
    }

    private void append(String key, int value) {
        var record = EntityLogRecord.upsert(key, String.valueOf(value).getBytes(StandardCharsets.UTF_8)).encode();
        var offset = substrate.appendRaw(record);

        fold.ready(0).await();
        fold.apply(0, offset, EntityLogRecord.decode(record).unwrap());
    }

    private static final class CommitGatedSubstrate implements EntityLogSubstrate {
        private final List<byte[]> log = new CopyOnWriteArrayList<>();
        private final List<Waiter> waiters = new CopyOnWriteArrayList<>();
        private volatile long committed = Long.MAX_VALUE;

        private record Waiter(long offset, Promise<Unit> promise) {}

        private volatile long cutGeneration;

        /// Removes every record above `keepThrough` and counts a cut, as the divergent-tail repair does on the ring.
        void cutTo(long keepThrough) {
            while (log.size() - 1L > keepThrough) {
                log.remove(log.size() - 1);
            }
            cutGeneration += 2;
        }

        @Override
        public long cutGeneration(String keyspace, int partition) {
            return cutGeneration;
        }

        volatile boolean cutDuringNextRead;
        volatile boolean blockNextRead;
        volatile long earliestRetained;
        volatile long sealedBelow;
        volatile Option<EntityCheckpoint> saved = Option.none();

        long appendRaw(byte[] record) {
            log.add(record);

            return log.size() - 1L;
        }

        void commitThrough(long offset) {
            committed = offset;
            new ArrayList<>(waiters).stream()
                                    .filter(waiter -> waiter.offset() <= offset)
                                    .forEach(waiter -> {
                                        waiters.remove(waiter);
                                        waiter.promise().resolve(Result.unitResult());
                                    });
        }

        @Override
        public long committedOffset(String keyspace, int partition) {
            return Math.min(committed, log.size() - 1L);
        }

        @Override
        public Promise<Unit> awaitCommitted(String keyspace, int partition, long offset, TimeSpan timeout) {
            if (committedOffset(keyspace, partition) >= offset) {
                return Promise.unitPromise();
            }

            var promise = Promise.<Unit> promise();

            waiters.add(new Waiter(offset, promise));

            return promise.timeout(timeout);
        }

        @Override
        public TimeSpan commitWait() {
            return TimeSpan.timeSpan(300).millis();
        }

        @Override
        public Result<Unit> ensureLog(String keyspace, int partitionCount, org.pragmatica.aether.slice.ReplicationFactors replication) {
            return Result.unitResult();
        }

        @Override
        public Promise<Long> append(String keyspace, int partition, byte[] record) {
            return Promise.success(appendRaw(record));
        }

        @Override
        public Promise<List<byte[]>> read(String keyspace, int partition, long fromOffset, int maxRecords) {
            if (blockNextRead) {
                blockNextRead = false;

                return Promise.<List<byte[]>> promise();
            }
            if (cutDuringNextRead) {
                cutDuringNextRead = false;
                cutGeneration += 2;
            }
            if (fromOffset >= log.size()) {
                return Promise.success(List.of());
            }

            return Promise.success(List.copyOf(log.subList((int) fromOffset, (int) Math.min(log.size(), fromOffset + maxRecords))));
        }

        @Override
        public long headOffset(String keyspace, int partition) {
            return log.size() - 1L;
        }

        @Override
        public long earliestRetainedOffset(String keyspace, int partition) {
            return earliestRetained;
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
            return saveCheckpoint(keyspace, partition, throughOffset, snapshot, Option.none());
        }

        @Override
        public Promise<Unit> saveCheckpoint(String keyspace, int partition, long throughOffset, byte[] snapshot, Option<byte[]> lineage) {
            saved = Option.some(EntityCheckpoint.entityCheckpoint(throughOffset, snapshot, lineage));

            return Promise.unitPromise();
        }

        @Override
        public Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition) {
            return Promise.success(saved);
        }

        /// The identity of the records ending at `offset` as this copy holds them (a digest of the last four), none below `sealedBelow`.
        @Override
        public Option<byte[]> lineageAt(String keyspace, int partition, long offset) {
            if (offset < sealedBelow || offset >= log.size()) {
                return Option.none();
            }

            var window = log.subList((int) Math.max(0, offset - 3), (int) offset + 1);

            return Option.some(java.nio.ByteBuffer.allocate(Integer.BYTES)
                                                  .putInt(window.stream().mapToInt(java.util.Arrays::hashCode).reduce(17, (h, e) -> 31 * h + e))
                                                  .array());
        }
    }

    private static final class IntSerializer implements Serializer {
        @Override
        public byte[] encode(Object value) {
            return switch (value) {
                case IntOp.Add add -> ("Add:" + add.delta()).getBytes(StandardCharsets.UTF_8);
                case null, default -> String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            };
        }

        @Override
        public <T> void write(ByteBuf byteBuf, T object) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static final class IntDeserializer implements Deserializer {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(byte[] bytes) {
            var text = new String(bytes, StandardCharsets.UTF_8);

            return text.startsWith("Add:")
                   ? (T) new IntOp.Add(Integer.parseInt(text.substring(4)))
                   : (T) Integer.valueOf(text);
        }

        @Override
        public <T> T read(ByteBuf byteBuf) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
