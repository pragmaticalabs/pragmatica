// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource.CommittedOwner;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Per-`(keyspace, partition)` fence proof for [PartitionFencedDurableEntity] (#345 I3).
///
/// ## Why this test was rewritten, and what did NOT change
/// Before I3 the entity stamped each write with the partition's owner epoch and a real
/// `MemoryStorageEngine` fenced by a real `PartitionOwnerEpochGate` rejected a stale stamp. I3 moved
/// entity state onto a durable log, and the fence moved with it: the log's own append gate derives and
/// checks that epoch ahead of both the ring append and the WAL fsync.
///
/// The ENFORCEMENT POINT moved; the GUARANTEE did not, and neither did the cause a caller sees. So this
/// test still pins exactly the properties the storage-engine version pinned — a deposed owner rejected
/// with [DurableEntityError.StaleOwner], the current owner accepted, and the fence proven per-partition
/// rather than coarse — but against the log, which is where writes now land.
///
/// ## The non-negotiable acceptance (the gap the coarse fence misses)
/// The coarse `"core"` governor arc moves only on a GOVERNOR change. Every case below advances ONLY one
/// `(keyspace, partition)` high-water — no governor change, no community-arc change — which is exactly a
/// same-generation reshuffle, the case a coarse fence waves through.
class PartitionFencedDurableEntityFenceTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITIONS = 8;
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId OTHER = new NodeId("other-node");

    private FencedLogSubstrate substrate;
    private EntityPartitionArc arc;

    @BeforeEach
    void setUp() {
        substrate = new FencedLogSubstrate();
        arc = EntityPartitionArc.entityPartitionArc(KEYSPACE, PARTITIONS);
    }

    @Nested
    class CurrentOwner {
        @Test
        void create_commitsFencedState_atPartitionOwnerEpoch() {
            var entity = unwiredEntity();

            entity.create("k1", 1).await().onFailure(PartitionFencedDurableEntityFenceTest::failCause);

            assertThat(read(entity, "k1")).isEqualTo(Option.some(1));
        }

        @Test
        void update_commitsAndReadsBack_atPartitionOwnerEpoch() {
            var entity = unwiredEntity();

            entity.create("k1", 1).await();
            entity.update("k1", value -> value + 41)
                  .await()
                  .onFailure(PartitionFencedDurableEntityFenceTest::failCause);

            assertThat(read(entity, "k1")).isEqualTo(Option.some(42));
        }
    }

    @Nested
    class DeposedPartitionOwnerSameGeneration {
        /// The write fence firing. The key's partition reshuffled to a new owner — its high-water advanced
        /// while this node's epoch did not — so the append is refused and surfaces as the SAME
        /// [DurableEntityError.StaleOwner] callers saw before the fence moved.
        @Test
        void update_rejectedWithStaleOwner_afterSameGenerationReshuffle() {
            var entity = unwiredEntity();

            entity.create("k1", 1).await();
            substrate.reshuffle(arc.partitionOf("k1"));

            entity.update("k1", value -> value + 1)
                  .await()
                  .onSuccess(_ -> fail("a deposed partition owner's update must be fenced out"))
                  .onFailure(PartitionFencedDurableEntityFenceTest::assertStaleOwner);
        }

        @Test
        void create_rejectedWithStaleOwner_afterSameGenerationReshuffle() {
            var entity = unwiredEntity();

            substrate.reshuffle(arc.partitionOf("fresh"));

            entity.create("fresh", 1)
                  .await()
                  .onSuccess(_ -> fail("a deposed partition owner's create must be fenced out"))
                  .onFailure(PartitionFencedDurableEntityFenceTest::assertStaleOwner);
        }

        /// The fence rejects a STALE epoch, not every write: once this node's epoch catches up to the
        /// advanced high-water it is the current owner again and writes commit.
        @Test
        void update_acceptedForNewPartitionOwner_atTheAdvancedEpoch() {
            var entity = unwiredEntity();

            entity.create("k1", 1).await();

            var partition = arc.partitionOf("k1");

            substrate.reshuffle(partition);
            substrate.adoptCurrentEpoch(partition);

            entity.update("k1", value -> value + 41)
                  .await()
                  .onFailure(PartitionFencedDurableEntityFenceTest::failCause);

            assertThat(read(entity, "k1")).isEqualTo(Option.some(42));
        }
    }

    /// The property a coarse governor-level fence cannot provide: reshuffling ONE partition must not
    /// fence a key that hashes to a different one.
    @Nested
    class PerPartitionGranularity {
        @Test
        void update_unaffected_whenADifferentPartitionReshuffles() {
            var entity = unwiredEntity();
            var keys = distinctPartitionKeys();

            entity.create(keys.reshuffled(), 1).await();
            entity.create(keys.untouched(), 1).await();

            substrate.reshuffle(arc.partitionOf(keys.reshuffled()));

            entity.update(keys.untouched(), value -> value + 41)
                  .await()
                  .onFailure(PartitionFencedDurableEntityFenceTest::failCause);

            assertThat(read(entity, keys.untouched())).isEqualTo(Option.some(42));
        }

        @Test
        void update_rejected_forTheReshuffledPartition_inTheSameRun() {
            var entity = unwiredEntity();
            var keys = distinctPartitionKeys();

            entity.create(keys.reshuffled(), 1).await();
            entity.create(keys.untouched(), 1).await();

            substrate.reshuffle(arc.partitionOf(keys.reshuffled()));

            entity.update(keys.reshuffled(), value -> value + 1)
                  .await()
                  .onSuccess(_ -> fail("the reshuffled partition's key must be fenced out"))
                  .onFailure(PartitionFencedDurableEntityFenceTest::assertStaleOwner);
        }

        private KeyPair distinctPartitionKeys() {
            for (var i = 0; i < 200; i++) {
                var candidate = "key-" + i;

                if (arc.partitionOf(candidate) != arc.partitionOf("anchor")) {
                    return new KeyPair("anchor", candidate);
                }
            }

            return fail("no key hashing to a different partition than the anchor was found");
        }

        private record KeyPair(String reshuffled, String untouched) {}
    }

    /// The second, orthogonal guard: admission rejects a LIVE non-owner before any read-modify-write,
    /// where the fence rejects a DEPOSED one. Both are needed and neither subsumes the other — every node
    /// reads the same committed record and would derive the same current epoch, so the fence alone cannot
    /// tell five live contenders apart.
    @Nested
    class OwnerAdmission {
        @Test
        void create_admitted_whenSelfIsCommittedOwner() {
            var entity = wiredEntity(SELF);

            entity.create("k1", 1).await().onFailure(PartitionFencedDurableEntityFenceTest::failCause);

            assertThat(read(entity, "k1")).isEqualTo(Option.some(1));
        }

        @Test
        void create_rejectedWithNotCurrentOwner_whenAnotherNodeOwnsTheArc() {
            wiredEntity(OTHER).create("k1", 1)
                              .await()
                              .onSuccess(_ -> fail("a live non-owner's create must be refused by admission"))
                              .onFailure(PartitionFencedDurableEntityFenceTest::assertNotCurrentOwner);
        }

        /// THE #345 I1 gate, preserved across the I3 rewrite. One key, five nodes, one committed owner —
        /// exactly one accepted and four rejected. Five handles over ONE log, each believing it is a
        /// different node.
        @Test
        void create_acceptedByExactlyOneNode_whenFiveContendForOneKey() {
            var owners = fixedOwner(new NodeId("node-2"));
            var outcomes = java.util.stream.IntStream.rangeClosed(1, 5)
                                                     .mapToObj(index -> entityAs(new NodeId("node-" + index), owners))
                                                     .map(entity -> entity.create("o-1", 7).await().isSuccess())
                                                     .toList();

            assertThat(outcomes).describedAs("exactly the committed owner may accept")
                                .containsExactly(false, true, false, false, false);
        }

        @Test
        void update_rejectedWithNotCurrentOwner_whenAnotherNodeOwnsTheArc() {
            seed();

            wiredEntity(OTHER).update("k1", value -> value + 1)
                              .await()
                              .onSuccess(_ -> fail("a non-owner must not accept an update"))
                              .onFailure(PartitionFencedDurableEntityFenceTest::assertNotCurrentOwner);
        }

        @Test
        void delete_rejectedWithNotCurrentOwner_whenAnotherNodeOwnsTheArc() {
            seed();

            wiredEntity(OTHER).delete("k1")
                              .await()
                              .onSuccess(_ -> fail("a non-owner must not accept a delete"))
                              .onFailure(PartitionFencedDurableEntityFenceTest::assertNotCurrentOwner);
        }

        /// The ownership-reconcile window: records are minted asynchronously, so a freshly provisioned
        /// keyspace has a period with no owner on any arc. Admitting there would readmit every node at
        /// once — the exact hole this closes — so it refuses, with a cause that says it is transient.
        @Test
        void create_rejectedWithOwnershipNotYetCommitted_whenNoOwnerIsCommitted() {
            entityAs(SELF, CommittedPartitionOwnerSource.none()).create("k1", 1)
                                                                .await()
                                                                .onSuccess(_ -> fail("an unowned arc must not accept a write"))
                                                                .onFailure(cause -> assertThat(cause.stream()).hasAtLeastOneElementOfType(DurableEntityError.OwnershipNotYetCommitted.class));
        }

        /// The transient refusal stays distinguishable from the stable one, so a caller can tell "retry
        /// here" from "go somewhere else" without parsing prose.
        @Test
        void create_distinguishesTransientFromStableRefusal() {
            entityAs(SELF, CommittedPartitionOwnerSource.none()).create("k1", 1)
                                                                .await()
                                                                .onFailure(cause -> assertThat(cause).isNotInstanceOf(DurableEntityError.NotCurrentOwner.class));

            wiredEntity(OTHER).create("k2", 1)
                              .await()
                              .onFailure(cause -> assertThat(cause).isNotInstanceOf(DurableEntityError.OwnershipNotYetCommitted.class));
        }

        /// Reads are deliberately NOT admitted: a BOUNDED_STALE read promises only this node's committed
        /// prefix, which a non-owner can answer honestly.
        @Test
        void get_servesBoundedStale_evenWhenAnotherNodeOwnsTheArc() {
            seed();

            assertThat(read(wiredEntity(OTHER), "k1")).isEqualTo(Option.some(1));
        }
    }

    /// The `LinearizableEntityServe` port (#345 I1(d)) must survive the I3 rewrite: a wired entity routes
    /// a `LINEARIZABLE` read through the pipeline over the SAME arc its write fence uses, rather than
    /// silently serving a local read — the claim-vs-reality gap the owner ruling refused to leave open.
    @Nested
    class LinearizableReads {
        @Test
        void get_servesLinearizable_whenSelfIsCommittedOwnerAndBarrierWired() {
            var entity = entityAs(SELF, fixedOwner(SELF), Option.some(noOpBarrier()));

            entity.create("k1", 7).await().onFailure(PartitionFencedDurableEntityFenceTest::failCause);

            entity.get("k1", ReadConsistency.LINEARIZABLE)
                  .await()
                  .onFailure(PartitionFencedDurableEntityFenceTest::failCause)
                  .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(7));
        }

        @Test
        void get_rejectsNotCurrentOwner_whenCommittedOwnerIsRemote() {
            seed();

            entityAs(SELF, fixedOwner(OTHER), Option.some(noOpBarrier())).get("k1", ReadConsistency.LINEARIZABLE)
                                                                         .await()
                                                                         .onSuccess(_ -> fail("a non-owner must reject a LINEARIZABLE read"))
                                                                         .onFailure(PartitionFencedDurableEntityFenceTest::assertNotCurrentOwner);
        }

        /// Barrier absent: `LINEARIZABLE` refuses per-READ rather than quietly serving the local copy.
        @Test
        void get_rejectsLinearizableUnavailable_whenNoBarrierWired() {
            var entity = entityAs(SELF, fixedOwner(SELF), Option.none());

            entity.create("k1", 7).await().onFailure(PartitionFencedDurableEntityFenceTest::failCause);

            entity.get("k1", ReadConsistency.LINEARIZABLE)
                  .await()
                  .onSuccess(state -> fail("expected LinearizableUnavailable, got " + state))
                  .onFailure(cause -> assertThat(cause.stream()).hasAtLeastOneElementOfType(DurableEntityError.LinearizableUnavailable.class));
        }

        /// ...and BOUNDED_STALE on that same entity keeps working — the refusal is per-read, not
        /// per-resource.
        @Test
        void get_servesBoundedStale_whenNoBarrierWired() {
            var entity = entityAs(SELF, fixedOwner(SELF), Option.none());

            entity.create("k1", 7).await().onFailure(PartitionFencedDurableEntityFenceTest::failCause);

            entity.get("k1", ReadConsistency.BOUNDED_STALE)
                  .await()
                  .onFailure(PartitionFencedDurableEntityFenceTest::failCause)
                  .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(7));
        }
    }

    private void seed() {
        wiredEntity(SELF).create("k1", 1).await().onFailure(PartitionFencedDurableEntityFenceTest::failCause);
    }

    private static EntityLinearizableBarrier noOpBarrier() {
        return (_, _) -> Promise.success(Unit.unit());
    }

    private DurableEntity<String, Integer> unwiredEntity() {
        return PartitionFencedDurableEntity.partitionFencedDurableEntity(KEYSPACE,
                                                                         substrate,
                                                                         arc,
                                                                         new IntSerializer(),
                                                                         new IntDeserializer());
    }

    private DurableEntity<String, Integer> wiredEntity(NodeId committedOwner) {
        return entityAs(SELF, fixedOwner(committedOwner), Option.some(noOpBarrier()));
    }

    private DurableEntity<String, Integer> entityAs(NodeId self, CommittedPartitionOwnerSource owners) {
        return entityAs(self, owners, Option.some(noOpBarrier()));
    }

    private DurableEntity<String, Integer> entityAs(NodeId self,
                                                    CommittedPartitionOwnerSource owners,
                                                    Option<EntityLinearizableBarrier> barrier) {
        return PartitionFencedDurableEntity.partitionFencedDurableEntity(KEYSPACE,
                                                                         substrate,
                                                                         arc,
                                                                         new IntSerializer(),
                                                                         new IntDeserializer(),
                                                                         self,
                                                                         owners,
                                                                         Option.none(),
                                                                         barrier);
    }

    private static CommittedPartitionOwnerSource fixedOwner(NodeId owner) {
        return (_, _) -> Option.some(new CommittedOwner(owner, Epoch.ZERO));
    }

    private static Option<Integer> read(DurableEntity<String, Integer> entity, String key) {
        return entity.get(key).await().fold(cause -> fail(cause.message()), value -> value);
    }

    private static void assertNotCurrentOwner(Cause cause) {
        assertThat(cause.stream()).hasAtLeastOneElementOfType(DurableEntityError.NotCurrentOwner.class);
    }

    private static void assertStaleOwner(Cause cause) {
        assertThat(cause.stream()).hasAtLeastOneElementOfType(DurableEntityError.StaleOwner.class);
    }

    private static void failCause(Cause cause) {
        fail(cause.message());
    }

    /// A log that enforces the SAME per-partition epoch fence the real stream's `ensureNotStale` does: an
    /// append is refused when this node's epoch for the partition is older than that partition's
    /// high-water.
    ///
    /// Deliberately not permissive. [#reshuffle] advances one partition's high-water WITHOUT advancing
    /// this node's epoch, which is precisely a same-generation handover — the case a coarse fence misses.
    /// A fake that accepted every append would let all three DeposedPartitionOwner tests pass against an
    /// entity with no fence at all.
    private static final class FencedLogSubstrate implements EntityLogSubstrate {
        private final Map<Integer, List<byte[]>> log = new ConcurrentHashMap<>();
        private final Map<Integer, Long> highWater = new ConcurrentHashMap<>();
        private final Map<Integer, Long> nodeEpoch = new ConcurrentHashMap<>();

        void reshuffle(int partition) {
            highWater.merge(partition, 1L, Long::sum);
        }

        void adoptCurrentEpoch(int partition) {
            nodeEpoch.put(partition, highWater.getOrDefault(partition, 0L));
        }

        @Override
        public Result<Unit> ensureLog(String keyspace, int partitionCount, int replicationFactor, int minSyncReplicas) {
            return Result.unitResult();
        }

        @Override
        public Promise<Long> append(String keyspace, int partition, byte[] record) {
            if (nodeEpoch.getOrDefault(partition, 0L) < highWater.getOrDefault(partition, 0L)) {
                return new EntityLogError.StaleOwnerAppend(keyspace,
                                                           partition,
                                                           "presented " + nodeEpoch.getOrDefault(partition, 0L)
                                                           + ", current " + highWater.get(partition)).promise();
            }

            var records = log.computeIfAbsent(partition, _ -> new ArrayList<>());

            records.add(record);

            return Promise.success((long) records.size() - 1);
        }

        @Override
        public Promise<List<byte[]>> read(String keyspace, int partition, long fromOffset, int maxRecords) {
            var records = log.getOrDefault(partition, List.of());
            var start = (int) fromOffset;

            return Promise.success(start >= records.size()
                                   ? List.of()
                                   : List.copyOf(records.subList(start, Math.min(records.size(), start + maxRecords))));
        }

        @Override
        public long headOffset(String keyspace, int partition) {
            return log.getOrDefault(partition, List.of()).size() - 1L;
        }

        @Override
        public long earliestRetainedOffset(String keyspace, int partition) {
            return log.getOrDefault(partition, List.of()).isEmpty() ? -1L : 0L;
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
            return Promise.unitPromise();
        }

        @Override
        public Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition) {
            return Promise.success(Option.none());
        }
    }

    private static final class IntSerializer implements Serializer {
        @Override
        public byte[] encode(Object value) {
            return String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public <T> void write(ByteBuf byteBuf, T object) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static final class IntDeserializer implements Deserializer {
        @Override
        public <T> T decode(byte[] bytes) {
            @SuppressWarnings("unchecked")
            var value = (T) Integer.valueOf(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));

            return value;
        }

        @Override
        public <T> T read(ByteBuf byteBuf) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
