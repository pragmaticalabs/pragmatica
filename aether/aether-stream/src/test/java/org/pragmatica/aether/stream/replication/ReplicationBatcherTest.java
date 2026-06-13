// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationBatcher.replicationBatcher;


class ReplicationBatcherTest {

    private static final NodeId GOVERNOR = NodeId.randomNodeId();
    private static final NodeId REPLICA_A = NodeId.randomNodeId();
    private static final String STREAM = "events";
    private static final int PARTITION = 0;
    private static final byte[] PAYLOAD = "test-event".getBytes();
    private static final long TIMESTAMP = 1000L;

    private ReplicaRegistry registry;
    private List<SentMessage> sentMessages;
    private ReplicationBatcher batcher;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        sentMessages = new ArrayList<>();
        registry.registerReplica(STREAM, PARTITION, REPLICA_A);
    }

    @Nested
    class SizeBasedFlush {

        @Test
        void add_flushesWhenMaxEventsReached() {
            batcher = replicationBatcher(capturingTransport(), registry, GOVERNOR, 3, TimeSpan.timeSpan(10).seconds());

            batcher.add(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP);
            batcher.add(STREAM, PARTITION, 1L, "second".getBytes(), TIMESTAMP + 1);
            assertThat(sentMessages).isEmpty();

            batcher.add(STREAM, PARTITION, 2L, "third".getBytes(), TIMESTAMP + 2);
            assertThat(sentMessages).hasSize(1);

            var message = (ReplicationMessage.ReplicateEvents) sentMessages.getFirst().message();
            assertThat(message.fromOffset()).isEqualTo(0L);
            assertThat(message.payloads()).hasSize(3);
            assertThat(message.timestamps()).containsExactly(TIMESTAMP, TIMESTAMP + 1, TIMESTAMP + 2);

            batcher.close();
        }

        @Test
        void add_preservesPayloadOrder() {
            batcher = replicationBatcher(capturingTransport(), registry, GOVERNOR, 2, TimeSpan.timeSpan(10).seconds());

            batcher.add(STREAM, PARTITION, 5L, "first".getBytes(), 100L);
            batcher.add(STREAM, PARTITION, 6L, "second".getBytes(), 200L);

            var message = (ReplicationMessage.ReplicateEvents) sentMessages.getFirst().message();
            assertThat(message.fromOffset()).isEqualTo(5L);
            assertThat(new String(message.payloads().getFirst())).isEqualTo("first");
            assertThat(new String(message.payloads().getLast())).isEqualTo("second");

            batcher.close();
        }
    }

    @Nested
    class ManualFlush {

        @Test
        void flushAll_sendsAccumulatedEvents() {
            batcher = replicationBatcher(capturingTransport(), registry, GOVERNOR, 100, TimeSpan.timeSpan(10).seconds());

            batcher.add(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP);
            batcher.add(STREAM, PARTITION, 1L, "second".getBytes(), TIMESTAMP + 1);
            assertThat(sentMessages).isEmpty();

            batcher.flushAll();
            assertThat(sentMessages).hasSize(1);

            var message = (ReplicationMessage.ReplicateEvents) sentMessages.getFirst().message();
            assertThat(message.payloads()).hasSize(2);

            batcher.close();
        }

        @Test
        void flushAll_noEventsAccumulated_sendsNothing() {
            batcher = replicationBatcher(capturingTransport(), registry, GOVERNOR, 100, TimeSpan.timeSpan(10).seconds());

            batcher.flushAll();
            assertThat(sentMessages).isEmpty();

            batcher.close();
        }
    }

    @Nested
    class CloseTests {

        @Test
        void close_flushesRemainingEvents() {
            batcher = replicationBatcher(capturingTransport(), registry, GOVERNOR, 100, TimeSpan.timeSpan(10).seconds());

            batcher.add(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP);
            assertThat(sentMessages).isEmpty();

            batcher.close();
            assertThat(sentMessages).hasSize(1);
        }
    }

    @Nested
    class MultiPartition {

        @Test
        void add_batchesPerPartitionIndependently() {
            batcher = replicationBatcher(capturingTransport(), registry, GOVERNOR, 2, TimeSpan.timeSpan(10).seconds());

            registry.registerReplica(STREAM, 1, REPLICA_A);

            batcher.add(STREAM, 0, 0L, "p0-first".getBytes(), TIMESTAMP);
            batcher.add(STREAM, 1, 0L, "p1-first".getBytes(), TIMESTAMP);

            // Neither partition has reached threshold
            assertThat(sentMessages).isEmpty();

            // Partition 0 reaches threshold
            batcher.add(STREAM, 0, 1L, "p0-second".getBytes(), TIMESTAMP + 1);
            assertThat(sentMessages).hasSize(1);

            var message = (ReplicationMessage.ReplicateEvents) sentMessages.getFirst().message();
            assertThat(message.partition()).isEqualTo(0);
            assertThat(message.payloads()).hasSize(2);

            // Partition 1 still has one event pending
            batcher.close();
            assertThat(sentMessages).hasSize(2);

            var p1Message = (ReplicationMessage.ReplicateEvents) sentMessages.getLast().message();
            assertThat(p1Message.partition()).isEqualTo(1);
            assertThat(p1Message.payloads()).hasSize(1);
        }
    }

    @Nested
    class NoReplicas {

        @Test
        void add_noReplicas_dropsOnFlush() {
            var emptyRegistry = replicaRegistry();
            batcher = replicationBatcher(capturingTransport(), emptyRegistry, GOVERNOR, 2, TimeSpan.timeSpan(10).seconds());

            batcher.add(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP);
            batcher.add(STREAM, PARTITION, 1L, PAYLOAD, TIMESTAMP + 1);

            // Size threshold hit, but no replicas — nothing sent
            assertThat(sentMessages).isEmpty();

            batcher.close();
        }
    }

    @Nested
    class TimeBasedFlush {

        @Test
        void scheduledFlush_sendsAccumulatedEvents() throws InterruptedException {
            var latch = new CountDownLatch(1);
            ReplicationTransport latchingTransport = (target, message) -> {
                sentMessages.add(new SentMessage(target, message));
                latch.countDown();
            };

            batcher = replicationBatcher(latchingTransport, registry, GOVERNOR, 1000, TimeSpan.timeSpan(50).millis());

            batcher.add(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP);

            // Wait for time-based flush (50ms interval + margin)
            assertThat(latch.await(500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(sentMessages).hasSize(1);

            batcher.close();
        }
    }

    @Nested
    class BatchingReplicationManagerTests {

        @Test
        void batchingManager_delegatesToBatcher() {
            var manager = ReplicationManager.batchingReplicationManager(GOVERNOR, registry, capturingTransport(),
                                                                       2, TimeSpan.timeSpan(10).seconds());

            manager.replicateEvent(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP);
            assertThat(sentMessages).isEmpty();

            manager.replicateEvent(STREAM, PARTITION, 1L, "second".getBytes(), TIMESTAMP + 1);
            assertThat(sentMessages).hasSize(1);

            var message = (ReplicationMessage.ReplicateEvents) sentMessages.getFirst().message();
            assertThat(message.payloads()).hasSize(2);

            manager.close();
        }

        @Test
        void batchingManager_close_flushesPending() {
            var manager = ReplicationManager.batchingReplicationManager(GOVERNOR, registry, capturingTransport(),
                                                                       100, TimeSpan.timeSpan(10).seconds());

            manager.replicateEvent(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP);
            assertThat(sentMessages).isEmpty();

            manager.close();
            assertThat(sentMessages).hasSize(1);
        }
    }

    private ReplicationTransport capturingTransport() {
        return (target, message) -> sentMessages.add(new SentMessage(target, message));
    }

    /// Captured transport message for test assertions.
    record SentMessage(NodeId target, ReplicationMessage message) {}
}
