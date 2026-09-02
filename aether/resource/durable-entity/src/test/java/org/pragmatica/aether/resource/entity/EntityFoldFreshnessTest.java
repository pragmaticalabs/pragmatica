// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource.CommittedOwner;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #596 review S1: a fold is rebuilt ONCE, but must not be FROZEN there. Records replication lands
/// behind this node's back (a replica's normal life, and a just-promoted owner's first moments) must be
/// visible to the next access — otherwise `BOUNDED_STALE` is a snapshot rather than a bounded lag, and
/// a promoted owner mutates on top of a stale view, silently dropping replicated records.
///
/// "Replication landing" is simulated by appending to the fake substrate's log DIRECTLY — exactly what
/// the production ring looks like to the fold: the log grew and nobody told it.
class EntityFoldFreshnessTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITIONS = 1; // one partition so every key shares one fold
    private static final NodeId SELF = new NodeId("self-node");

    private GrowableSubstrate substrate;
    private EntityPartitionArc arc;

    @BeforeEach
    void setUp() {
        substrate = new GrowableSubstrate();
        arc = EntityPartitionArc.entityPartitionArc(KEYSPACE, PARTITIONS);
    }

    @Test
    void get_recordReplicatedAfterFirstRead_isVisibleToTheNextRead() {
        var entity = ownedEntity();

        assertThat(entity.get("k1").await().unwrap().isEmpty()).isTrue(); // rebuild happens here, log empty

        replicate(upsert("k2", 42)); // lands behind the fold's back

        var read = entity.get("k2").await();

        assertThat(read.isSuccess()).isTrue();
        assertThat(read.unwrap()).isEqualTo(Option.some(42));
    }

    /// The promotion shape: this node rebuilt (as a replica would), MORE history was replicated, and it
    /// then mutates as the new owner. The mutator must run on the replicated state, not the frozen view —
    /// pre-fix this failed with EntityNotFound because the frozen fold had never seen the key at all.
    @Test
    void update_afterRecordsReplicatedPastTheRebuild_mutatesTheReplicatedState_notTheFrozenView() {
        var entity = ownedEntity();

        assertThat(entity.get("k1").await().unwrap().isEmpty()).isTrue(); // fold rebuilt at head=-1

        replicate(upsert("k1", 500)); // the previous owner's committed write, replicated in

        var updated = entity.update("k1", new IntOp.Add(7)).await();

        assertThat(updated.isSuccess()).isTrue();

        int state = updated.fold(cause -> fail(cause.message()), value -> value);

        assertThat(state).isEqualTo(507);
    }

    /// Watermark accounting when catch-up and the owner's direct applies interleave: a directly-applied
    /// offset parked ABOVE the watermark is accounted (not re-applied) by catch-up, the parked set ends
    /// empty, and the checkpointable watermark reaches the head. A watermark that stalled below a parked
    /// offset would hold checkpoints back forever; one that double-counted would let a checkpoint claim
    /// records the state map never absorbed.
    @Test
    void caughtUp_directlyAppliedParkedOffset_isAccountedNotReapplied_andWatermarkReachesHead() {
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        replicate(upsert("k1", 1)); // offset 0
        replicate(upsert("k2", 2)); // offset 1
        replicate(upsert("k3", 3)); // offset 2

        assertThat(fold.ready(0).await().isSuccess()).isTrue();

        replicate(upsert("k4", 4));                              // offset 3, not yet applied
        fold.apply(0, 4, decode(upsert("k5", 5)));               // offset 4 applied DIRECTLY -> parked
        replicate(upsert("k5", 5));                              // ...and its log copy at offset 4

        assertThat(fold.caughtUp(0).await().isSuccess()).isTrue();

        assertThat(fold.checkpointableThrough(0)).isEqualTo(4L);
        assertThat(decodedState(fold, "k4")).isEqualTo(4);
        assertThat(decodedState(fold, "k5")).isEqualTo(5);
    }

    /// A fold whose watermark fell behind what the log RETAINS cannot catch up record by record. The
    /// access fails transiently, the memo is cleared, and the NEXT access rebuilds from the (newer)
    /// checkpoint — the only bridge over the truncated range.
    @Test
    void caughtUp_watermarkBehindRetention_failsTransiently_thenRebuildsFromCheckpoint() {
        var entity = ownedEntity();

        replicate(upsert("k1", 10)); // offset 0
        assertThat(entity.get("k1").await().unwrap()).isEqualTo(Option.some(10)); // rebuilt, watermark 0

        // Retention moves past the fold's watermark; a checkpoint bridges to offset 4; head grows to 5.
        substrate.checkpoint = Option.some(new EntityCheckpointOf(4L, Map.of("k1", encodeInt(77))));
        substrate.earliestRetained = 5L;
        for (var i = 0; i < 5; i++) {
            replicate(upsert("ignored-" + i, i)); // offsets 1..5; only offset 5 is readable
        }

        assertThat(entity.get("k1").await().isFailure())
            .as("catch-up over a truncated range must fail transiently, never serve the frozen view as current")
            .isTrue();

        var reread = entity.get("k1").await();

        assertThat(reread.isSuccess()).isTrue();
        assertThat(reread.unwrap())
            .as("the retry rebuilds from the checkpoint and replays the retained tail")
            .isEqualTo(Option.some(77));
    }

    // --- fixtures ---

    private DurableEntity<String, Integer, IntOp> ownedEntity() {
        return PartitionFencedDurableEntity.<String, Integer, IntOp> partitionFencedDurableEntity(KEYSPACE,
                                                                                                  substrate,
                                                                                                  arc,
                                                                                                  new IntSerializer(),
                                                                                                  new IntDeserializer(),
                                                                                                  SELF,
                                                                                                  (_, _) -> Option.some(new CommittedOwner(SELF, Epoch.ZERO)),
                                                                                                  Option.none(),
                                                                                                  Option.some((_, _) -> Promise.success(Unit.unit())));
    }

    private void replicate(byte[] record) {
        substrate.append(KEYSPACE, 0, record).await().unwrap();
    }

    private static byte[] upsert(String key, int value) {
        return EntityLogRecord.upsert(key, encodeInt(value)).encode();
    }

    private static byte[] encodeInt(int value) {
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }

    private static EntityLogRecord decode(byte[] raw) {
        return EntityLogRecord.decode(raw).unwrap();
    }

    private int decodedState(EntityFold fold, String key) {
        return fold.get(0, key)
                   .map(bytes -> Integer.parseInt(new String(bytes, StandardCharsets.UTF_8)))
                   .or(-1);
    }

    private record EntityCheckpointOf(long throughOffset, Map<String, byte[]> state) {}

    /// A log that GROWS underneath the fold — the fold-side view of replication. Retention and the
    /// checkpoint are settable so the truncation path is reachable.
    private static final class GrowableSubstrate implements EntityLogSubstrate {
        private final List<byte[]> log = new ArrayList<>();
        private final Map<Integer, Object> unusedGuard = new ConcurrentHashMap<>();
        private long earliestRetained = 0L;
        private Option<EntityCheckpointOf> checkpoint = Option.none();

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
            if (fromOffset < earliestRetained || fromOffset >= log.size()) {
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
            return Promise.unitPromise();
        }

        @Override
        public Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition) {
            return Promise.success(checkpoint.map(c -> EntityCheckpoint.entityCheckpoint(c.throughOffset(),
                                                                                         EntityFoldSnapshot.encode(c.state(), Map.of()))));
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
