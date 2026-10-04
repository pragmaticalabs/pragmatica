// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardTransport;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1873, end to end over the forward transport: a consumer on a node that holds NO ring reads through the real client
/// and the real handler of the owner. The owner began epoch E2 at offset 3 (its record says so); a consumer whose cursor
/// is past that, under E1, is refused with the typed divergence, and one below it is served and told E2. Nothing is
/// softened to a local read: a refusal must reach the consumer.
class StreamReadRouterEpochTest {
    private static final NodeId OWNER = NodeId.nodeId("owner").unwrap();
    private static final NodeId ASSIGNEE = NodeId.nodeId("assignee").unwrap();
    private static final String STREAM = "topic:ns:orders:1.0.0";
    private static final int PARTITION = 0;
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 1L, 2L);

    private final java.util.concurrent.atomic.AtomicReference<StreamPartitionOwnershipValue> ownerRecord = new java.util.concurrent.atomic.AtomicReference<>(record(List.of(new EpochStart(E1, 0L), new EpochStart(E2, 3L))));
    private StreamPartitionManager ownerPartitions;
    private StreamPartitionManager assigneePartitions;
    private StreamReadRouter ownerRouter;
    private StreamReadRouter assigneeRouter;

    @BeforeEach
    void setUp() {
        ownerPartitions = StreamPartitionManager.streamPartitionManager();
        ownerPartitions.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000), "earliest"))
                       .onFailure(cause -> fail(cause.message()));
        for (var i = 0; i < 5; i++) {
            ownerPartitions.publishLocal(STREAM, PARTITION, new byte[]{(byte) i}, 1_000L + i).onFailure(cause -> fail(cause.message()));
        }
        ownerPartitions.ownershipRecords((_, _) -> Option.some(ownerRecord.get()));
        // The assignee knows the stream (its config is committed cluster-wide) and holds no ring: not a replica of it.
        assigneePartitions = StreamPartitionManager.streamPartitionManager();
        assigneePartitions.placementRoleSupplier((_, _) -> org.pragmatica.aether.stream.replication.ReplicaSetController.Role.NONE);
        assigneePartitions.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000), "earliest"))
                          .onFailure(cause -> fail(cause.message()));
        var clientRef = new StreamForwardClient[1];
        StreamForwardTransport ownerToAssignee = (_, message) -> clientRef[0].onReadForwardResponse((ReadForwardResponse) message);
        var ownerHandler = StreamForwardHandler.streamForwardHandler(OWNER, ownerPartitions, ownerToAssignee);
        StreamForwardTransport assigneeToOwner = (_, message) -> ownerHandler.onReadForward((ReadForward) message);

        clientRef[0] = StreamForwardClient.streamForwardClient(ASSIGNEE, assigneeToOwner, TimeSpan.timeSpan(5).seconds());
        ownerRouter = StreamReadRouter.localOnly(ownerPartitions);
        assigneeRouter = StreamReadRouter.streamReadRouter(assigneePartitions,
                                                           Option.none(),
                                                           Option.some(clientRef[0]),
                                                           ASSIGNEE,
                                                           (_, _) -> Option.some(OWNER),
                                                           StreamReadForwardMetrics.NOOP);
    }

    @AfterEach
    void tearDown() {
        ownerPartitions.close();
        assigneePartitions.close();
    }

    @Test
    void forwardedConsumer_cursorPastTheNewEpochsStart_getsTheTypedDivergence() {
        var read = assigneeRouter.readValidated(STREAM, PARTITION, 5L, 10, E1).await();

        assertThat(read.isFailure()).isTrue();
        read.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StreamError.EpochDiverged.class, diverged -> {
            assertThat(diverged.ownerEpoch()).isEqualTo(E2);
            assertThat(diverged.resumeAt()).isEqualTo(3L);
            assertThat(diverged.boundaryKnown()).as("the record holds the start that followed E1").isTrue();
        }));
    }

    /// C3: whether the boundary is exact crosses the forward transport. The record keeps no start older than E2 (the
    /// consumer's E1 predates the history), so the owner answers a conservative bound, and the assignee must learn that.
    @Test
    void forwardedConsumer_olderThanTheKeptHistory_getsAnInexactBoundary() {
        ownerRecord.set(record(List.of(new EpochStart(E2, 3L))));

        var read = assigneeRouter.readValidated(STREAM, PARTITION, 5L, 10, E1).await();

        assertThat(read.isFailure()).isTrue();
        read.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StreamError.EpochDiverged.class, diverged -> {
            assertThat(diverged.resumeAt()).isEqualTo(3L);
            assertThat(diverged.boundaryKnown()).as("the owner could not name the boundary and said so").isFalse();
        }));
    }

    @Test
    void forwardedConsumer_cursorBelowTheNewEpochsStart_isServed_andAdoptsTheOwnersEpoch() {
        var read = assigneeRouter.readValidated(STREAM, PARTITION, 2L, 10, E1).await().unwrap();

        assertThat(read.ownerEpoch()).isEqualTo(E2);
        assertThat(read.events()).extracting(OffHeapRingBuffer.RawEvent::offset).containsExactly(2L, 3L, 4L);
    }

    @Test
    void colocatedConsumer_isCheckedTheSameWay() {
        var diverged = ownerRouter.readValidated(STREAM, PARTITION, 5L, 10, E1).await();
        var served = ownerRouter.readValidated(STREAM, PARTITION, 2L, 10, E1).await().unwrap();

        assertThat(diverged.isFailure()).isTrue();
        diverged.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.EpochDiverged.class));
        assertThat(served.ownerEpoch()).isEqualTo(E2);
    }

    /// With no owner to ask and no ring here, the answer is the honest NOT_LOCAL, never a made-up empty read.
    @Test
    void noRingAndNoOwner_isPartitionNotLocal() {
        var lonely = StreamReadRouter.streamReadRouter(assigneePartitions,
                                                       Option.none(),
                                                       Option.none(),
                                                       ASSIGNEE,
                                                       (_, _) -> Option.none(),
                                                       StreamReadForwardMetrics.NOOP);
        var read = lonely.readValidated(STREAM, PARTITION, 0L, 10, E1).await();

        assertThat(read.isFailure()).isTrue();
        read.onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.PARTITION_NOT_LOCAL));
    }

    private static StreamPartitionOwnershipValue record(List<EpochStart> starts) {
        return new StreamPartitionOwnershipValue(OWNER, E2, 2L, HlcTimestamp.ZERO, List.of(OWNER), 3L, false, List.of(), starts);
    }
}
