// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.replication.ReplicationReceiveHandler;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1555 items 5/6: after an ownership move with catch-up, the partition stays WRITABLE at `min-sync-replicas` 2
/// with a real replica acking. Two real [StreamPartitionManager] rings, wired by real replication: the promoted
/// owner `X` (ring 0..19) replicates through a [ReplicationManager] whose transport delivers to the replica `R`'s
/// [ReplicationReceiveHandler] (ring 0..24, the previous owner's replica), and `R`'s acks come back to `X`.
///
/// This is the verifier's ms2 wedge in miniature: without the catch-up `X` appends at offset 20, `R` refuses the
/// conflicting offset, no ack arrives and every publish times out "outcome unknown". With the promotion gate `X`
/// first pulls 20..24 from `R`, appends at 25, and `R` acks.
class PromotedOwnerReplicaAckTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId X = new NodeId("x");
    private static final NodeId R = new NodeId("r");
    private static final TimeSpan ACK_BOUND = TimeSpan.timeSpan(3).seconds();

    private final AtomicReference<ReplicationManager> ownerReplication = new AtomicReference<>();
    private StreamPartitionManager owner;
    private StreamPartitionManager replica;

    @BeforeEach
    void setUp() {
        replica = streamPartitionManager(Long.MAX_VALUE);
        var receiver = ReplicationReceiveHandler.replicationReceiveHandler(R,
                                                                           replica::appendRecovered,
                                                                           replica::nextExpectedOffset,
                                                                           this::toOwner,
                                                                           (_, _) -> {});
        var registry = ReplicaRegistry.replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, X);
        registry.registerReplica(STREAM, PARTITION, R);
        ownerReplication.set(ReplicationManager.replicationManager(X, registry, (target, message) -> deliver(receiver, message)));
        owner = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, ownerReplication.get());
        assertThat(owner.createStream(config()).isSuccess()).isTrue();
        assertThat(replica.createStream(config()).isSuccess()).isTrue();
        seed(owner, "pre", 20);
        seed(replica, "pre", 20);
        seed(replica, "post", 5);
        registry.updateWatermark(STREAM, PARTITION, R, 24L, ReplicationState.CAUGHT_UP);
        owner.placementRoleSupplier((_, _) -> ReplicaSetController.Role.OWNER);
        owner.ownerServeGate(gate()::admit);
    }

    @AfterEach
    void tearDown() {
        owner.close();
        replica.close();
    }

    @Test
    void publish_afterPromotionWithCatchUp_isAckedByTheReplica() {
        assertThat(eventually(() -> owner.mayServeAsOwner(STREAM, PARTITION))).as("promotion completes").isTrue();

        var offset = owner.publishLocalAtFloor(STREAM, PARTITION, "heal-0".getBytes(StandardCharsets.UTF_8), 1L, 1)
                          .unwrap();
        var acked = owner.awaitReplication(STREAM, PARTITION, offset, 1)
                         .timeout(ACK_BOUND)
                         .await();

        assertThat(offset).as("appended after the caught-up history, never over it").isEqualTo(25L);
        assertThat(acked.isSuccess()).as("the replica acks: the partition is writable (%s)", acked).isTrue();
    }

    private void deliver(ReplicationReceiveHandler receiver, ReplicationMessage message) {
        if (message instanceof ReplicationMessage.ReplicateEvents events) {
            receiver.onReplicateEvents(events);
        }
    }

    private void toOwner(NodeId target, ReplicationMessage message) {
        if (message instanceof ReplicationMessage.ReplicateAck ack) {
            ownerReplication.get()
                            .handleAck(ack);
        }
    }

    private OwnerActivation gate() {
        var record = Option.some(StreamPartitionOwnershipValue.streamPartitionOwnershipValue(X,
                                                                                             Epoch.epoch(3, 0),
                                                                                             3,
                                                                                             HlcTimestamp.ZERO));

        return OwnerActivation.ownerActivation(X,
                                               (_, _) -> record,
                                               (_, _) -> true,
                                               Option.some((_, _) -> Promise.success(Unit.unit())),
                                               () -> List.of(X, R),
                                               (_, _, _) -> Promise.success(head(replica)),
                                               (_, _) -> head(owner),
                                               (stream, partition, source, tail) -> catchUpFromReplica(tail),
                                               () -> true,
                                               PromotionTestRanges.over(Map.of(X, owner, R, replica)),
                                               PromotionTestRanges.NO_ALARM,
                                               PromotionTestRanges.NEVER_ALARM);
    }

    private Promise<Long> catchUpFromReplica(long tail) {
        var from = head(owner) + 1;

        replica.readLocal(STREAM, PARTITION, from, (int) (tail - from + 1))
               .unwrap()
               .forEach(event -> owner.appendRecovered(STREAM, PARTITION, event.offset(), event.data(), event.timestamp(), Epoch.ZERO)
                                      .unwrap());

        return Promise.success(tail);
    }

    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         org.pragmatica.aether.slice.ReplicationFactors.BUILT_IN.replicationFactor(),
                                         2,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    private static void seed(StreamPartitionManager manager, String tag, int count) {
        for (var i = 0; i < count; i++) {
            assertThat(manager.appendRecovered(STREAM,
                                               PARTITION,
                                               head(manager) + 1,
                                               (tag + "-" + i).getBytes(StandardCharsets.UTF_8),
                                               1L,
                                               Epoch.ZERO)
                              .isSuccess()).isTrue();
        }
    }

    private static long head(StreamPartitionManager manager) {
        return manager.partitionInfo(STREAM, PARTITION)
                      .map(StreamPartitionManager.PartitionInfo::headOffset)
                      .or(-1L);
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) {
        for (var attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }

            LockSupport.parkNanos(20_000_000L);
        }

        return false;
    }
}
