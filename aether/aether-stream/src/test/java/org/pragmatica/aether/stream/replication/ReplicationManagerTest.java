// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;
import static org.pragmatica.aether.stream.replication.ReplicationMetrics.replicationMetrics;

class ReplicationManagerTest {

    private static final NodeId GOVERNOR = NodeId.randomNodeId();
    private static final NodeId REPLICA_A = NodeId.randomNodeId();
    private static final NodeId REPLICA_B = NodeId.randomNodeId();
    private static final String STREAM = "events";
    private static final int PARTITION = 0;
    private static final byte[] PAYLOAD = "test-event".getBytes();
    private static final long TIMESTAMP = 1000L;

    private ReplicaRegistry registry;
    private List<SentMessage> sentMessages;
    private ReplicationManager manager;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        sentMessages = new ArrayList<>();
        ReplicationTransport capturingTransport = (target, message) -> sentMessages.add(new SentMessage(target, message));
        manager = replicationManager(GOVERNOR, registry, capturingTransport);
    }

    @Nested
    class ReplicateEventTests {

        @Test
        void replicateEvent_callsTransportForEachReplica() {
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.registerReplica(STREAM, PARTITION, REPLICA_B);

            manager.replicateEvent(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP);

            assertThat(sentMessages).hasSize(2);
            assertThat(sentMessages).extracting(SentMessage::target)
                                    .containsExactlyInAnyOrder(REPLICA_A, REPLICA_B);
        }

        @Test
        void replicateEvent_noReplicas_noTransportCall() {
            manager.replicateEvent(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP);

            assertThat(sentMessages).isEmpty();
        }

        @Test
        void replicateEvent_sendsCorrectMessage() {
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);

            manager.replicateEvent(STREAM, PARTITION, 5L, PAYLOAD, TIMESTAMP);

            assertThat(sentMessages).hasSize(1);
            var message = (ReplicationMessage.ReplicateEvents) sentMessages.getFirst().message();
            assertThat(message.governorId()).isEqualTo(GOVERNOR);
            assertThat(message.streamName()).isEqualTo(STREAM);
            assertThat(message.partition()).isEqualTo(PARTITION);
            assertThat(message.fromOffset()).isEqualTo(5L);
            assertThat(message.payloads()).hasSize(1);
            assertThat(message.timestamps()).containsExactly(TIMESTAMP);
        }
    }

    @Nested
    class HandleAckTests {

        @Test
        void handleAck_updatesWatermark() {
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);

            manager.handleAck(replicateAck(REPLICA_A, STREAM, PARTITION, 10L));

            var replicas = registry.replicasFor(STREAM, PARTITION);
            assertThat(replicas.getFirst().confirmedOffset()).isEqualTo(10L);
            assertThat(replicas.getFirst().state()).isEqualTo(ReplicationState.CAUGHT_UP);
        }

        @Test
        void awaitReplication_resolvedByHigherOffsetAck() {
            // m1: a replica that has caught up PAST the awaited offset acks a HIGHER watermark.
            // An exact (stream, partition, offset) match would miss it and only the timeout would
            // resolve. The await for offset 5 must be satisfied by an ack at offset 10.
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);

            var pending = manager.awaitReplication(STREAM, PARTITION, 5L, 1);
            assertThat(pending.isResolved()).isFalse(); // not yet resolved

            manager.handleAck(replicateAck(REPLICA_A, STREAM, PARTITION, 10L));

            var result = pending.await();
            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    class PromotionGateTests {

        @Test
        void handleAck_belowEarliestRetained_advancesWatermark_butStaysSyncing() {
            // #261: the owner's earliest retained offset is 20 (history starts at 20). A replica that
            // acks offset 5 holds only a sub-range below the retained floor — it has NOT covered the
            // partition's history, so it must stay SYNCING (excluded from reads / backfill source).
            ReplicationManager.EarliestRetainedOffset floor20 = (_, _) -> 20L;
            var gatedManager = replicationManager(GOVERNOR, registry, capturing(), floor20);
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);

            gatedManager.handleAck(replicateAck(REPLICA_A, STREAM, PARTITION, 5L));

            var replica = registry.replicasFor(STREAM, PARTITION).getFirst();
            assertThat(replica.confirmedOffset()).isEqualTo(5L); // watermark always advances
            assertThat(replica.state()).isEqualTo(ReplicationState.SYNCING); // but no premature promotion
        }

        @Test
        void handleAck_atOrAboveEarliestRetained_promotesToCaughtUp() {
            ReplicationManager.EarliestRetainedOffset floor20 = (_, _) -> 20L;
            var gatedManager = replicationManager(GOVERNOR, registry, capturing(), floor20);
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);

            gatedManager.handleAck(replicateAck(REPLICA_A, STREAM, PARTITION, 20L));

            var replica = registry.replicasFor(STREAM, PARTITION).getFirst();
            assertThat(replica.confirmedOffset()).isEqualTo(20L);
            assertThat(replica.state()).isEqualTo(ReplicationState.CAUGHT_UP);
        }

        @Test
        void handleAck_defaultManager_promotesOnAnyAck() {
            // No earliest-retained seam wired (the default factory) => floor -1 => any ack promotes,
            // preserving pre-#261 behavior for minimal runtimes/tests.
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);

            manager.handleAck(replicateAck(REPLICA_A, STREAM, PARTITION, 0L));

            assertThat(registry.replicasFor(STREAM, PARTITION).getFirst().state()).isEqualTo(ReplicationState.CAUGHT_UP);
        }

        private ReplicationTransport capturing() {
            return (target, message) -> sentMessages.add(new SentMessage(target, message));
        }
    }

    @Nested
    class MetricsTests {

        @Test
        void replicationMetrics_computesLag() {
            var metrics = replicationMetrics(STREAM, PARTITION, 100L, 75L, 2);

            assertThat(metrics.maxLag()).isEqualTo(25L);
            assertThat(metrics.replicaCount()).isEqualTo(2);
        }

        @Test
        void replicationMetrics_zeroLag_whenCaughtUp() {
            var metrics = replicationMetrics(STREAM, PARTITION, 50L, 50L, 1);

            assertThat(metrics.maxLag()).isZero();
        }
    }

    @Nested
    class NoOpTests {

        @Test
        void noneManager_replicateEvent_doesNothing() {
            ReplicationManager.NONE.replicateEvent(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP);

            assertThat(ReplicationManager.NONE.registry().replicasFor(STREAM, PARTITION)).isEmpty();
        }

        @Test
        void noneManager_handleAck_doesNothing() {
            ReplicationManager.NONE.handleAck(replicateAck(REPLICA_A, STREAM, PARTITION, 10L));

            assertThat(ReplicationManager.NONE.registry().replicasFor(STREAM, PARTITION)).isEmpty();
        }
    }

    /// Captured transport message for test assertions.
    record SentMessage(NodeId target, ReplicationMessage message) {}
}
