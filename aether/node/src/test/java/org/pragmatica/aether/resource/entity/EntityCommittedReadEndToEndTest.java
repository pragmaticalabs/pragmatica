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

/// #2087 (B4) end to end on the real log: a durable-entity read is answered from committed state only, on the owner and on a replica,
/// and the owner's own acknowledged write is readable at once. Two nodes (owner + one replica, replication factor 2 and confirmation
/// factor 2) wired by loopback, over the real ring, replication manager, receive handler and entity fold. The owner's write is held
/// unacknowledged by dropping the replica's acknowledgement; the test then lets it through.
class EntityCommittedReadEndToEndTest {
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

    @Test
    void anUnacknowledgedWrite_isNotReadableOnTheOwnerOrTheReplica_untilItIsAcknowledged() throws Exception {
        var created = ownerEntity.create("k", 5);

        Thread.sleep(500);
        assertThat(created.isResolved() ? created.await().toString() : "pending").as("create state after 500 ms").isEqualTo("pending");
        awaitHeld(replicaManager);
        assertThat(replicaManager.appendedHead(arc(), PARTITION)).as("control: the replica holds the record").isEqualTo(0L);

        var onOwner = ownerEntity.get("k");
        var onReplica = replicaEntity.get("k");

        Thread.sleep(300);
        assertThat(onOwner.isResolved()).as("owner read waits for the acknowledgement instead of showing the value").isFalse();
        assertThat(onReplica.isResolved()).as("replica read waits for the owner's committed position").isFalse();

        acksReachOwner.set(true);
        announcementsReachReplica.set(true);
        deliverTheHeldAcknowledgement();

        assertThat(created.await().isSuccess()).as("the write is acknowledged once the replica's acknowledgement arrives").isTrue();
        assertThat(onOwner.await().unwrap()).as("read-your-writes after the acknowledgement").isEqualTo(Option.some(5));
        assertThat(onReplica.await().unwrap()).as("the replica serves it once the owner announced it committed").isEqualTo(Option.some(5));
    }

    @Test
    void anUntouchedKey_isServedWhileAnotherKeyIsPending() {
        acksReachOwner.set(true);
        announcementsReachReplica.set(true);
        var first = ownerEntity.create("a", 1).await();

        assertThat(first.isSuccess()).as("first create: %s", first).isTrue();

        acksReachOwner.set(false);
        var pending = ownerEntity.create("b", 2);

        awaitHeld(replicaManager);

        assertThat(ownerEntity.get("a").await().unwrap()).as("committed key").isEqualTo(Option.some(1));
        assertThat(ownerEntity.get("untouched").await().unwrap()).as("absent key").isEqualTo(Option.none());
        assertThat(pending.isResolved()).isFalse();
    }

    private void deliverTheHeldAcknowledgement() {
        // The replica's earlier acknowledgement was dropped; the owner's tick re-offers the tail and the replica acknowledges it.
        for (var i = 0; i < 7; i++) {
            ownerManager.repeatVisible();
        }
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
