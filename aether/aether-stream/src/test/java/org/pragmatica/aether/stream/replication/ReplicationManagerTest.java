// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
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

            manager.replicateEvent(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO);

            assertThat(sentMessages).hasSize(2);
            assertThat(sentMessages).extracting(SentMessage::target)
                                    .containsExactlyInAnyOrder(REPLICA_A, REPLICA_B);
        }

        @Test
        void replicateEvent_noReplicas_noTransportCall() {
            manager.replicateEvent(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO);

            assertThat(sentMessages).isEmpty();
        }

        @Test
        void replicateEvent_sendsCorrectMessage() {
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);

            manager.replicateEvent(STREAM, PARTITION, 5L, PAYLOAD, TIMESTAMP, Epoch.ZERO);

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
    class AckAccountingTests {

        @Test
        void duplicateAcksFromOneReplica_doNotSatisfyMinAcks() {
            // #262.1: minAcks=2 must require TWO DISTINCT replicas. Two acks from REPLICA_A alone must
            // NOT resolve the await — only the timeout would, never a single-replica double-count.
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.registerReplica(STREAM, PARTITION, REPLICA_B);

            var pending = manager.awaitReplication(STREAM, PARTITION, 5L, 2);

            manager.handleAck(replicateAck(REPLICA_A, STREAM, PARTITION, 5L));
            manager.handleAck(replicateAck(REPLICA_A, STREAM, PARTITION, 6L)); // same replica again

            assertThat(pending.isResolved()).isFalse();

            // A distinct second replica resolves it.
            manager.handleAck(replicateAck(REPLICA_B, STREAM, PARTITION, 5L));
            assertThat(pending.await().isSuccess()).isTrue();
        }

        @Test
        void distinctReplicaAcks_satisfyMinAcks() {
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.registerReplica(STREAM, PARTITION, REPLICA_B);

            var pending = manager.awaitReplication(STREAM, PARTITION, 5L, 2);

            manager.handleAck(replicateAck(REPLICA_A, STREAM, PARTITION, 5L));
            assertThat(pending.isResolved()).isFalse();
            manager.handleAck(replicateAck(REPLICA_B, STREAM, PARTITION, 5L));

            assertThat(pending.await().isSuccess()).isTrue();
        }

        @Test
        void minSyncTwo_resolvesAfterOneDistinctPeerAck() {
            // Barrier arithmetic: min-sync-replicas=2 (owner + one in-sync peer) maps to
            // awaitReplication(minAcks = min-sync - 1 = 1). At replicas=2 OR replicas=3, a SINGLE
            // distinct non-self replica ack satisfies the sync barrier — the owner is one of the
            // in-sync set and is never awaited.
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.registerReplica(STREAM, PARTITION, REPLICA_B);

            var pending = manager.awaitReplication(STREAM, PARTITION, 5L, 1);

            manager.handleAck(replicateAck(REPLICA_A, STREAM, PARTITION, 5L));

            assertThat(pending.await().isSuccess()).isTrue();
        }

        @Test
        void selfInReplicaSet_isNotCounted_norReplicatedTo() {
            // #262.2/.5 + #378: the HRW set is owner-first so it contains GOVERNOR (self). Under the
            // corrected provisioning (RF = owner + minSyncReplicas peers) a minSyncReplicas=2 stream
            // registers self + TWO real peers, so minAcks=2 is satisfiable by the peer acks alone —
            // self is neither a replication target nor a counted ack.
            registry.registerReplica(STREAM, PARTITION, GOVERNOR); // self, owner-first
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.registerReplica(STREAM, PARTITION, REPLICA_B);

            var pending = manager.awaitReplication(STREAM, PARTITION, 5L, 2);
            assertThat(pending.isResolved()).isFalse(); // NOT a NOT_ENOUGH failure — two real peers exist

            manager.handleAck(replicateAck(GOVERNOR, STREAM, PARTITION, 5L)); // self-ack: ignored
            assertThat(pending.isResolved()).isFalse();
            manager.handleAck(replicateAck(REPLICA_A, STREAM, PARTITION, 5L));
            manager.handleAck(replicateAck(REPLICA_B, STREAM, PARTITION, 5L));
            assertThat(pending.await().isSuccess()).isTrue(); // resolved by the two DISTINCT peers

            // Replication targets exclude self: only the two real peers receive the event.
            manager.replicateEvent(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO);
            assertThat(sentMessages).extracting(SentMessage::target)
                                    .containsExactlyInAnyOrder(REPLICA_A, REPLICA_B);
        }

        @Test
        void awaitReplication_fewerPeersThanMinAcks_failsNotEnoughReplicas() {
            // #378: when the cluster is too small to provision `minSyncReplicas` peers (owner + one peer
            // but minAcks=2), the await fails CLEARLY with NOT_ENOUGH_REPLICAS rather than silently
            // under-provisioning. This is the manager-side signal the RF clamp relies on when
            // minSyncReplicas + 1 > N.
            registry.registerReplica(STREAM, PARTITION, GOVERNOR); // self, owner-first
            registry.registerReplica(STREAM, PARTITION, REPLICA_A); // only one real peer

            var result = manager.awaitReplication(STREAM, PARTITION, 0L, 2).await();
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void selfAck_doesNotCountTowardMinAcks() {
            registry.registerReplica(STREAM, PARTITION, GOVERNOR); // self
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);

            var pending = manager.awaitReplication(STREAM, PARTITION, 5L, 1);

            manager.handleAck(replicateAck(GOVERNOR, STREAM, PARTITION, 5L)); // self-ack: ignored
            assertThat(pending.isResolved()).isFalse();

            manager.handleAck(replicateAck(REPLICA_A, STREAM, PARTITION, 5L));
            assertThat(pending.await().isSuccess()).isTrue();
        }

        @Test
        void ackBeforeRegister_race_isHonoredViaRegistrySeed() {
            // #262.3: replication fires (and a peer acks) BEFORE the caller awaits. The ack already
            // advanced the registry watermark; awaitReplication seeds the pending set from the registry,
            // so the already-won ack resolves the await immediately instead of timing out.
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.registerReplica(STREAM, PARTITION, REPLICA_B);

            // Acks land first (race won).
            manager.handleAck(replicateAck(REPLICA_A, STREAM, PARTITION, 5L));
            manager.handleAck(replicateAck(REPLICA_B, STREAM, PARTITION, 5L));

            // Await registered afterwards — must resolve from the already-recorded watermarks.
            var pending = manager.awaitReplication(STREAM, PARTITION, 5L, 2);

            assertThat(pending.isResolved()).isTrue();
            assertThat(pending.await().isSuccess()).isTrue();
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
            ReplicationManager.NONE.replicateEvent(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO);

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
