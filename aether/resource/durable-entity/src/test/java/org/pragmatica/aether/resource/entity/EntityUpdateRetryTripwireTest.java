// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.pragmatica.lang.utils.Causes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource.CommittedOwner;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// Streams audit B-09 (#2201), per 1b-core: an entity `update` whose replication barrier is unmet is applied to the owner's write-path state and
/// reported failed, so a caller that retries applies its mutator twice. `update(K, C)` carries no caller token to dedup on.
///
/// What is NOT claimed (1b-core REFUTED it, #2087): that the unacknowledged value is visible to reads. `get` serves committed state only, and the
/// first group below pins that as a control. The second half of the finding is the cause's own advice ("re-read and decide from the observed
/// state"): it predates #2087, and during the very outage that raises the cause the read-back waits and fails `NotYetCommitted`.
///
/// Each finding holds a TRIPWIRE (enabled, GREEN) and the REAL assertion beside it (`@Disabled`, RED today). The fixture is a stub
/// [EntityLogSubstrate] whose commit position the test moves; the entity, the fold and the cause are the real classes.
class EntityUpdateRetryTripwireTest {
    private static final String KEYSPACE = "orders";
    private static final NodeId SELF = new NodeId("self-node");
    private static final String ENABLE_RETRY = "B-09 tripwire: a retried update after an unmet barrier no longer applies the mutator twice; delete this tripwire and enable the real assertion beside it (#2201)";
    private static final String ENABLE_ADVICE = "B-09 tripwire: the cause's re-read advice now works during the outage (or no longer gives it); delete this tripwire and enable the real assertion beside it (#2201)";

    private CommitGatedSubstrate substrate;
    private DurableEntity<String, Integer, IntOp> entity;

    @BeforeEach
    void setUp() {
        substrate = new CommitGatedSubstrate();
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

    /// Key `k` holds 10 at committed offset 0; the next update's barrier is unmet: its record lands at offset 1, the call fails.
    private Result<Integer> updateWithUnmetBarrier() {
        assertThat(entity.create("k", 10).await().isSuccess()).as("control: the create landed").isTrue();
        substrate.commitThrough(0L);
        substrate.barrierUnmetOnNextAppend = true;

        return entity.update("k", new IntOp.Add(1)).await();
    }

    @Test
    void update_afterAnUnmetBarrier_failsWithBarrierUnmet_andTheValueIsHeldBackFromReads_control() {
        var failed = updateWithUnmetBarrier();

        assertThat(failed.isFailure()).isTrue();
        failed.onFailure(cause -> assertThat(cause).isInstanceOf(EntityLogError.ReplicationBarrierUnmet.class));
        var read = entity.get("k").await();

        assertThat(read.isFailure()).as("control, 1b-core's refutation: the unacknowledged increment is NOT visible to a client read").isTrue();
        read.onFailure(cause -> assertThat(cause).isInstanceOf(EntityLogError.NotYetCommitted.class));
    }

    @Test
    void retriedUpdate_afterAnUnmetBarrier_appliesTheMutatorTwice_tripwire() {
        updateWithUnmetBarrier();

        var retried = entity.update("k", new IntOp.Add(1)).await();

        assertThat(retried.isSuccess()).as("control: the retry's barrier is met").isTrue();
        substrate.commitThrough(2L);
        assertThat(entity.get("k").await().unwrap()).as(ENABLE_RETRY).isEqualTo(Option.some(12));
    }

    @Disabled("B-09 real assertion, RED today: one logical update must apply once; needs the additive update(K, token, C) overload or equivalent dedup (#2201)")
    @Test
    void retriedUpdate_afterAnUnmetBarrier_appliesTheMutatorOnce() {
        updateWithUnmetBarrier();

        entity.update("k", new IntOp.Add(1)).await();
        substrate.commitThrough(2L);

        assertThat(entity.get("k").await().unwrap()).isEqualTo(Option.some(11));
    }

    /// Positive control for the real assertion above: it is satisfiable. When the failed attempt leaves NO record in the log (what a pre-append
    /// refusal does), the same retry applies once. 1b-core proposed deleting the fold apply in `translateAppendFailure` as the mutation proving
    /// reach; it does not: the record IS in the log, and the fold's catch-up on access replays it, so the retry still sees 11 and applies to 12.
    @Test
    void retriedUpdate_whenTheFailedAttemptLeftNoRecord_appliesTheMutatorOnce_control() {
        assertThat(entity.create("k", 10).await().isSuccess()).isTrue();
        substrate.commitThrough(0L);
        substrate.refuseNextAppendBeforeLanding = true;

        assertThat(entity.update("k", new IntOp.Add(1)).await().isFailure()).as("control: refused before the append").isTrue();
        assertThat(entity.update("k", new IntOp.Add(1)).await().isSuccess()).isTrue();
        substrate.commitThrough(1L);

        assertThat(entity.get("k").await().unwrap()).isEqualTo(Option.some(11));
    }

    @Test
    void theCausesReReadAdvice_failsDuringTheOutageItIsRaisedFor_tripwire() {
        var failed = updateWithUnmetBarrier();
        var advice = failed.fold(Cause::message, _ -> "");

        assertThat(advice).as("control: the cause tells the caller to re-read").contains("re-read");
        var reread = entity.get("k").await();

        assertThat(reread.isFailure()).as(ENABLE_ADVICE).isTrue();
        reread.onFailure(cause -> assertThat(cause).as(ENABLE_ADVICE).isInstanceOf(EntityLogError.NotYetCommitted.class));
    }

    @Disabled("B-09 real assertion, RED today: the cause's recovery advice must be actionable -- either the read-back works during the outage or the message stops advising it (#2201)")
    @Test
    void theCausesReReadAdvice_isActionable() {
        var failed = updateWithUnmetBarrier();
        var advisesReRead = failed.fold(Cause::message, _ -> "").contains("re-read");
        var rereadWorks = entity.get("k").await().isSuccess();

        assertThat(!advisesReRead || rereadWorks).as("the message advises a re-read that fails with NotYetCommitted for as long as the replica is missing").isTrue();
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
            return TimeSpan.timeSpan(50).millis();
        }

        @Override
        public Result<Unit> ensureLog(String keyspace, int partitionCount, org.pragmatica.aether.slice.ReplicationFactors replication) {
            return Result.unitResult();
        }

        /// The next append lands in the log (fsync-durable on the owner) but its confirmation barrier is not met.
        volatile boolean barrierUnmetOnNextAppend;
        /// The next append is refused BEFORE it lands, as a pre-append floor would: nothing enters the log.
        volatile boolean refuseNextAppendBeforeLanding;

        @Override
        public Promise<Long> append(String keyspace, int partition, byte[] record) {
            if (refuseNextAppendBeforeLanding) {
                refuseNextAppendBeforeLanding = false;

                return Causes.cause("not enough in-sync replicas").promise();
            }

            var offset = appendRaw(record);

            if (barrierUnmetOnNextAppend) {
                barrierUnmetOnNextAppend = false;

                return new EntityLogError.ReplicationBarrierUnmet(keyspace, partition, offset, 2, Causes.cause("replica down")).promise();
            }

            return Promise.success(offset);
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

        @Override
        public boolean recordsLineage() {
            return true;
        }

        /// Runs inside every `lineageAt`: a cut that lands while a checkpoint candidate is being assembled.
        volatile Runnable duringLineageRead = () -> {};
        /// A copy that holds the record but cannot vouch for its owner epoch.
        volatile boolean unvouched;

        /// The identity of the records ending at `offset` as this copy holds them (a digest of the last four), none below `sealedBelow`.
        @Override
        public Option<byte[]> lineageAt(String keyspace, int partition, long offset) {
            duringLineageRead.run();

            if (unvouched && offset >= sealedBelow && offset < log.size()) {
                return Option.some(UNVOUCHED);
            }

            if (offset < sealedBelow || offset >= log.size()) {
                return Option.none();
            }

            var window = log.subList((int) Math.max(0, offset - 3), (int) offset + 1);

            return Option.some(ByteBuffer.allocate(Integer.BYTES)
                                                  .putInt(window.stream().mapToInt(Arrays::hashCode).reduce(17, (h, e) -> 31 * h + e))
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
