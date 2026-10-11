// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource.CommittedOwner;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.resource.Mutator;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.replication.ReplicationReceiveHandler;
import org.pragmatica.aether.stream.replication.ReplicationTransport;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.StreamEntityLogSubstrate.streamEntityLogSubstrate;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;

/// #2087 (B4-cut): the entity fold is invalidated by a divergent-tail cut. Two nodes (owner + one replica, replication factor 2 and
/// confirmation factor 2) wired by loopback over the real ring, replication manager, receive handler and entity fold.
class EntityCutInvalidatesFoldTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITION = 0;
    private static final NodeId OWNER = new NodeId("owner-node");
    private static final NodeId REPLICA = new NodeId("replica-node");

    private final AtomicBoolean acksReachOwner = new AtomicBoolean(false);
    private final AtomicBoolean announcementsReachReplica = new AtomicBoolean(false);
    private StreamPartitionManager ownerManager;
    private StreamPartitionManager replicaManager;
    private DurableEntity<String, Integer, IntOp> ownerEntity;
    private DurableEntity<String, Integer, IntOp> replicaEntity;

    sealed interface IntOp extends Mutator<Integer> {
        record Add(int delta) implements IntOp {
            @Override
            public Integer apply(Integer state) {
                return state + delta;
            }
        }
    }

    @BeforeEach
    void setUp() {
        var arc = EntityPartitionArc.arcName(KEYSPACE);
        var registry = replicaRegistry();

        registry.registerReplica(arc, PARTITION, OWNER);
        registry.registerReplica(arc, PARTITION, REPLICA);
        replicaManager = StreamPartitionManager.streamPartitionManager(64L * 1024 * 1024);
        replicaManager.placementRoleSupplier((_, _) -> org.pragmatica.aether.stream.replication.ReplicaSetController.Role.REPLICA);
        var ownerReplication = new ReplicationManager[1];
        ReplicationTransport ackTransport = (target, message) -> {
            if (message instanceof ReplicationMessage.ReplicateAck ack && acksReachOwner.get()) {
                ownerReplication[0].handleAck(ack);
            }
        };
        var receive = ReplicationReceiveHandler.replicationReceiveHandler(REPLICA,
                                                                          replicaManager::appendRecovered,
                                                                          replicaManager::nextExpectedOffset,
                                                                          ackTransport,
                                                                          (_, _) -> {},
                                                                          replicaManager::syncReplicated);

        receive.commitSink(replicaManager::commitAdvanced);
        ReplicationTransport toReplica = (target, message) -> {
            switch (message) {
                case ReplicationMessage.ReplicateEvents events -> receive.onReplicateEvents(events);
                case ReplicationMessage.CommitAdvance advance when announcementsReachReplica.get() -> receive.onCommitAdvance(advance);
                default -> {}
            }
        };

        ownerReplication[0] = replicationManager(OWNER, registry, toReplica);
        ownerManager = StreamPartitionManager.streamPartitionManager(64L * 1024 * 1024, EvictionListener.NOOP, ownerReplication[0]);
        var ownerSubstrate = substrate(ownerManager);
        var replicaSubstrate = substrate(replicaManager);

        ownerSubstrate.ensureLog(KEYSPACE, 1, new ReplicationFactors(2, 2)).unwrap();
        replicaSubstrate.ensureLog(KEYSPACE, 1, new ReplicationFactors(2, 2)).unwrap();
        ownerEntity = entity(ownerSubstrate, OWNER);
        replicaEntity = entity(replicaSubstrate, REPLICA);
    }

    @AfterEach
    void tearDown() {
        ownerManager.close();
        replicaManager.close();
    }

    /// A read of a key whose write is later CUT must never return the cut value (#2087 B4-cut). The replica holds two unacknowledged records
    /// (k=5 at offset 0, j=9 at offset 1); a read of k is pending. A new lineage then puts a DIFFERENT record at offset 0, the replica's
    /// divergent tail is cut, and the new record lands and is reported committed. The entity fold was built from the pre-cut log, so
    /// without invalidation it kept serving k=5 although offset 0 now holds another entry.
    @Test
    void aReadOfAKeyWhoseWriteIsLaterCut_neverReturnsTheCutValue() throws Exception {
        ownerEntity.create("k", 5);
        awaitHeld(replicaManager);
        ownerEntity.create("j", 9);
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (replicaManager.appendedHead(arc(), PARTITION) < 1 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(replicaManager.appendedHead(arc(), PARTITION)).as("control: the replica holds both records").isEqualTo(1L);

        var pendingReadOfK = replicaEntity.get("k");

        Thread.sleep(300);
        assertThat(pendingReadOfK.isResolved()).as("control: the read waits for the owner's committed position").isFalse();

        var rawJ = replicaManager.readAppended(arc(), PARTITION, 1L, 1).unwrap().getFirst().data();
        var newLineage = Epoch.epoch(1L, 2L, 1L);

        assertThat(replicaManager.appendRecovered(arc(), PARTITION, 0L, rawJ, 5L, newLineage).isFailure()).as("control: a different record at offset 0 is a divergence").isTrue();
        assertThat(replicaManager.repairDivergence(arc(), PARTITION, _ -> true).isSuccess()).as("control: the divergent tail is cut").isTrue();
        assertThat(replicaManager.appendRecovered(arc(), PARTITION, 0L, rawJ, 5L, newLineage).isSuccess()).as("control: the new lineage lands").isTrue();
        replicaManager.commitAdvanced(arc(), PARTITION, 0L, newLineage);

        var settled = pendingReadOfK.await(org.pragmatica.lang.io.TimeSpan.timeSpan(10).seconds());
        var freshK = replicaEntity.get("k").await(org.pragmatica.lang.io.TimeSpan.timeSpan(10).seconds());
        var freshJ = replicaEntity.get("j").await(org.pragmatica.lang.io.TimeSpan.timeSpan(10).seconds());

        assertThat(String.valueOf(settled)).as("the pending read of the cut key").doesNotContain("Some(5)");
        assertThat(String.valueOf(freshK)).as("a fresh read of the cut key").doesNotContain("Some(5)");
        assertThat(freshJ.or(Option.none())).as("the surviving log (the new lineage's offset 0) is served after the rebuild").isEqualTo(Option.some(9));
    }

    /// A deposed owner that rejoins WITHOUT a restart keeps the fold it built while it owned the partition. The new lineage's divergent
    /// record cuts its log; when it is promoted again its fold must reflect the surviving log, not the cut write: a create of the cut key
    /// must not be refused as "already exists".
    @Test
    void aDeposedOwnerWhoseTailIsCut_andIsLaterPromoted_doesNotSequenceOnTheCutWrite() throws Exception {
        var role = new java.util.concurrent.atomic.AtomicReference<>(org.pragmatica.aether.stream.replication.ReplicaSetController.Role.OWNER);

        ownerManager.placementRoleSupplier((_, _) -> role.get());
        ownerEntity.create("k", 5);
        awaitHeld(ownerManager);
        assertThat(ownerEntity.get("k").await(org.pragmatica.lang.io.TimeSpan.timeSpan(300).millis()).isFailure()).as("control: k is pending, so the read refuses").isTrue();

        role.set(org.pragmatica.aether.stream.replication.ReplicaSetController.Role.REPLICA);
        var otherRecord = EntityLogRecord.upsert("j", "9".getBytes(StandardCharsets.UTF_8)).encode();
        var newLineage = Epoch.epoch(1L, 2L, 1L);

        assertThat(ownerManager.appendRecovered(arc(), PARTITION, 0L, otherRecord, 5L, newLineage).isFailure()).as("control: the new lineage's record at offset 0 diverges").isTrue();
        assertThat(ownerManager.repairDivergence(arc(), PARTITION, _ -> true).isSuccess()).as("control: the cut").isTrue();
        assertThat(ownerManager.appendRecovered(arc(), PARTITION, 0L, otherRecord, 5L, newLineage).isSuccess()).isTrue();
        role.set(org.pragmatica.aether.stream.replication.ReplicaSetController.Role.OWNER);

        var created = ownerEntity.create("k", 7).await(org.pragmatica.lang.io.TimeSpan.timeSpan(15).seconds());

        created.onFailure(cause -> assertThat(cause).as("the cut write must not make the key exist").isNotInstanceOf(EntityError.EntityAlreadyExists.class));
    }

    /// The window the cut opens between "the ring has not shrunk" and "the generation says so": the generation is ODD from before the ring
    /// shrinks until after, so a fold built or checked in that window never matches. Observed from inside the cut, through the repair
    /// authority, which the cut asks while it holds the ring's ordered section. A cut that is refused or fails must not leave it odd.
    @Test
    void theCutGeneration_isOddWhileACutRuns_andEvenAfterIt_refusedOrDone() throws Exception {
        ownerEntity.create("k", 5);
        awaitHeld(replicaManager);
        var rawJ = EntityLogRecord.upsert("j", "9".getBytes(StandardCharsets.UTF_8)).encode();
        var newLineage = Epoch.epoch(1L, 2L, 1L);

        assertThat(replicaManager.appendRecovered(arc(), PARTITION, 0L, rawJ, 5L, newLineage).isFailure()).as("control: a divergence").isTrue();
        var before = replicaManager.cutGeneration(arc(), PARTITION);
        var seenWhenRefused = new java.util.concurrent.atomic.AtomicLong(-1L);

        assertThat(replicaManager.repairDivergence(arc(), PARTITION, _ -> {
            seenWhenRefused.set(replicaManager.cutGeneration(arc(), PARTITION));
            return false;
        }).isFailure()).as("control: the cut is refused").isTrue();
        assertThat(seenWhenRefused.get() & 1L).as("odd while the cut runs").isEqualTo(1L);
        assertThat(replicaManager.cutGeneration(arc(), PARTITION) & 1L).as("a refused cut leaves it even").isZero();

        var seenWhenDone = new java.util.concurrent.atomic.AtomicLong(-1L);

        assertThat(replicaManager.repairDivergence(arc(), PARTITION, _ -> {
            seenWhenDone.set(replicaManager.cutGeneration(arc(), PARTITION));
            return true;
        }).isSuccess()).as("control: the cut proceeds").isTrue();
        assertThat(seenWhenDone.get() & 1L).as("odd while the cut runs").isEqualTo(1L);
        assertThat(replicaManager.cutGeneration(arc(), PARTITION)).as("even, and different from before the cut").isNotEqualTo(before).matches(value -> (value & 1L) == 0L);
    }

    /// A stream deleted and created again under the same name gets a new ring; the entity fold built over the old one must not survive into it.
    @Test
    void aStreamDeletedAndCreatedAgain_doesNotKeepServingTheOldFold() {
        acksReachOwner.set(true);
        announcementsReachReplica.set(true);
        assertThat(ownerEntity.create("k", 5).await().isSuccess()).as("control: written and acknowledged").isTrue();
        assertThat(ownerEntity.get("k").await().unwrap()).as("control: served").isEqualTo(Option.some(5));

        assertThat(ownerManager.destroyStream(arc()).isSuccess()).isTrue();
        substrate(ownerManager).ensureLog(KEYSPACE, 1, new ReplicationFactors(2, 2)).unwrap();

        var afterRecreate = ownerEntity.get("k").await(org.pragmatica.lang.io.TimeSpan.timeSpan(10).seconds());

        assertThat(String.valueOf(afterRecreate)).as("the new incarnation is empty").doesNotContain("Some(5)");
    }

    private void awaitHeld(StreamPartitionManager manager) {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (manager.appendedHead(arc(), PARTITION) < 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static String arc() {
        return EntityPartitionArc.arcName(KEYSPACE);
    }

    private static EntityLogSubstrate substrate(StreamPartitionManager manager) {
        return streamEntityLogSubstrate(manager,
                                        (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0, true),
                                        null,
                                        new SegmentIndex(),
                                        EvictionListener.NOOP,
                                        null,
                                        new org.pragmatica.cluster.state.kvstore.KVStore<org.pragmatica.aether.slice.kvstore.AetherKey, org.pragmatica.aether.slice.kvstore.AetherValue>(null, null, null),
                                        null);
    }

    private DurableEntity<String, Integer, IntOp> entity(EntityLogSubstrate substrate, NodeId self) {
        return PartitionFencedDurableEntity.<String, Integer, IntOp> partitionFencedDurableEntity(KEYSPACE,
                                                                                                  substrate,
                                                                                                  EntityPartitionArc.entityPartitionArc(KEYSPACE, 1),
                                                                                                  new IntSerializer(),
                                                                                                  new IntDeserializer(),
                                                                                                  self,
                                                                                                  (_, _) -> Option.some(new CommittedOwner(OWNER, Epoch.ZERO)),
                                                                                                  Option.none(),
                                                                                                  Option.some((_, _) -> Promise.success(Unit.unit())));
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
