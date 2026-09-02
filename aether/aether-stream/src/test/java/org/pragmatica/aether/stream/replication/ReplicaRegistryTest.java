// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.replication;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;

class ReplicaRegistryTest {

    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId NODE_A = NodeId.randomNodeId();
    private static final NodeId NODE_B = NodeId.randomNodeId();

    private ReplicaRegistry registry;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
    }

    @Nested
    class RegistrationTests {

        @Test
        void registerReplica_thenLookup_findsIt() {
            registry.registerReplica(STREAM, PARTITION, NODE_A);

            var replicas = registry.replicasFor(STREAM, PARTITION);

            assertThat(replicas).hasSize(1);
            assertThat(replicas.getFirst().nodeId()).isEqualTo(NODE_A);
            assertThat(replicas.getFirst().state()).isEqualTo(ReplicationState.SYNCING);
            assertThat(replicas.getFirst().confirmedOffset()).isEqualTo(-1L);
        }

        @Test
        void registerMultipleReplicas_allFound() {
            registry.registerReplica(STREAM, PARTITION, NODE_A);
            registry.registerReplica(STREAM, PARTITION, NODE_B);

            var replicas = registry.replicasFor(STREAM, PARTITION);

            assertThat(replicas).hasSize(2);
            assertThat(replicas).extracting(ReplicaDescriptor::nodeId)
                                .containsExactlyInAnyOrder(NODE_A, NODE_B);
        }

        @Test
        void unregisterReplica_removesFromLookup() {
            registry.registerReplica(STREAM, PARTITION, NODE_A);
            registry.registerReplica(STREAM, PARTITION, NODE_B);

            registry.unregisterReplica(STREAM, PARTITION, NODE_A);

            var replicas = registry.replicasFor(STREAM, PARTITION);

            assertThat(replicas).hasSize(1);
            assertThat(replicas.getFirst().nodeId()).isEqualTo(NODE_B);
        }

        @Test
        void unregisterReplica_unknownNode_noEffect() {
            registry.registerReplica(STREAM, PARTITION, NODE_A);

            registry.unregisterReplica(STREAM, PARTITION, NODE_B);

            assertThat(registry.replicasFor(STREAM, PARTITION)).hasSize(1);
        }

        @Test
        void replicasFor_unknownPartition_returnsEmpty() {
            var replicas = registry.replicasFor("nonexistent", 99);

            assertThat(replicas).isEmpty();
        }
    }

    @Nested
    class WatermarkTests {

        @Test
        void updateWatermark_updatesDescriptor() {
            registry.registerReplica(STREAM, PARTITION, NODE_A);

            registry.updateWatermark(STREAM, PARTITION, NODE_A, 42L);

            var replicas = registry.replicasFor(STREAM, PARTITION);

            assertThat(replicas.getFirst().confirmedOffset()).isEqualTo(42L);
            assertThat(replicas.getFirst().state()).isEqualTo(ReplicationState.CAUGHT_UP);
        }

        @Test
        void updateWatermark_unknownNode_noEffect() {
            registry.registerReplica(STREAM, PARTITION, NODE_A);

            registry.updateWatermark(STREAM, PARTITION, NODE_B, 42L);

            assertThat(registry.replicasFor(STREAM, PARTITION).getFirst().confirmedOffset()).isEqualTo(-1L);
        }

        @Test
        void minConfirmedOffset_multipleReplicas_returnsMinimum() {
            registry.registerReplica(STREAM, PARTITION, NODE_A);
            registry.registerReplica(STREAM, PARTITION, NODE_B);
            registry.updateWatermark(STREAM, PARTITION, NODE_A, 100L);
            registry.updateWatermark(STREAM, PARTITION, NODE_B, 50L);

            var result = registry.minConfirmedOffset(STREAM, PARTITION);

            assertThat(result.isPresent()).isTrue();
            result.onPresent(offset -> assertThat(offset).isEqualTo(50L));
        }

        @Test
        void minConfirmedOffset_noReplicas_returnsNone() {
            var result = registry.minConfirmedOffset(STREAM, PARTITION);

            assertThat(result.isEmpty()).isTrue();
        }
    }

    @Nested
    class WatermarkStoreTests {

        @Test
        void updateWatermark_callsWatermarkStore() {
            var callCount = new AtomicInteger(0);
            var lastOffset = new AtomicLong(-1);
            var lastNodeId = new AtomicReference<NodeId>();

            WatermarkStore store = (stream, partition, nodeId, offset) -> {
                callCount.incrementAndGet();
                lastOffset.set(offset);
                lastNodeId.set(nodeId);
            };

            var reg = replicaRegistry(store);
            reg.registerReplica(STREAM, PARTITION, NODE_A);

            reg.updateWatermark(STREAM, PARTITION, NODE_A, 77L);

            assertThat(callCount.get()).isEqualTo(1);
            assertThat(lastOffset.get()).isEqualTo(77L);
            assertThat(lastNodeId.get()).isEqualTo(NODE_A);
        }

        @Test
        void updateWatermark_multipleUpdates_callsStoreEachTime() {
            var callCount = new AtomicInteger(0);
            WatermarkStore store = (_, _, _, _) -> callCount.incrementAndGet();

            var reg = replicaRegistry(store);
            reg.registerReplica(STREAM, PARTITION, NODE_A);

            reg.updateWatermark(STREAM, PARTITION, NODE_A, 10L);
            reg.updateWatermark(STREAM, PARTITION, NODE_A, 20L);
            reg.updateWatermark(STREAM, PARTITION, NODE_A, 30L);

            assertThat(callCount.get()).isEqualTo(3);
        }

        @Test
        void noopFactory_doesNotCallStore() {
            var reg = replicaRegistry();
            reg.registerReplica(STREAM, PARTITION, NODE_A);

            reg.updateWatermark(STREAM, PARTITION, NODE_A, 42L);

            // Just verify the update worked without store — no exception.
            assertThat(reg.replicasFor(STREAM, PARTITION).getFirst().confirmedOffset()).isEqualTo(42L);
        }
    }

    /// #12 — `CAUGHT_UP` never downgrades, so a peer that stops acking FREEZES at its last good
    /// watermark and goes on reading as healthy forever. `freshPeersFor` additionally requires a peer to
    /// be within `caughtUpMaxLagOffsets` of the freshest peer watermark, measured relative to the peer
    /// set because the read router runs on nodes that hold no local partition.
    @Nested
    class FreshPeersTests {
        private static final NodeId NODE_C = NodeId.randomNodeId();

        /// Bound of 10 keeps the arithmetic legible; production default is 1024.
        private ReplicaRegistry bounded() {
            return replicaRegistry(10L);
        }

        private static void caughtUpAt(ReplicaRegistry reg, NodeId node, long offset) {
            reg.registerReplica(STREAM, PARTITION, node);
            reg.updateWatermark(STREAM, PARTITION, node, offset);
        }

        @Test
        void freshPeersFor_peerWithinBound_isKept() {
            var reg = bounded();

            caughtUpAt(reg, NODE_B, 100L);
            caughtUpAt(reg, NODE_C, 95L);

            assertThat(reg.freshPeersFor(STREAM, PARTITION, NODE_A)).extracting(ReplicaDescriptor::nodeId)
                                                                    .containsExactlyInAnyOrder(NODE_B, NODE_C);
        }

        @Test
        void freshPeersFor_peerLaggingBeyondBound_isDropped() {
            // The defect: NODE_C stopped acking at 50 while writes continued to 100. Its state is still
            // CAUGHT_UP and always will be, so only the lag distinguishes it.
            var reg = bounded();

            caughtUpAt(reg, NODE_B, 100L);
            caughtUpAt(reg, NODE_C, 50L);

            assertThat(reg.freshPeersFor(STREAM, PARTITION, NODE_A)).extracting(ReplicaDescriptor::nodeId)
                                                                    .containsExactly(NODE_B);
            assertThat(reg.replicasFor(STREAM, PARTITION)).as("the frozen peer is still CAUGHT_UP — only freshness rejects it")
                                                          .filteredOn(descriptor -> descriptor.nodeId().equals(NODE_C))
                                                          .allMatch(descriptor -> descriptor.state() == ReplicationState.CAUGHT_UP);
        }

        @Test
        void freshPeersFor_writeIdlePartition_keepsEveryPeer() {
            // The reason the bound is in OFFSETS and not a TTL: nothing refreshes a watermark on a quiet
            // partition, so a time-based rule would age out the healthiest streams in the cluster.
            var reg = bounded();

            caughtUpAt(reg, NODE_B, 7L);
            caughtUpAt(reg, NODE_C, 7L);

            assertThat(reg.freshPeersFor(STREAM, PARTITION, NODE_A)).hasSize(2);
        }

        @Test
        void freshPeersFor_syncingPeer_isExcludedRegardlessOfLag() {
            var reg = bounded();

            caughtUpAt(reg, NODE_B, 100L);
            reg.registerReplica(STREAM, PARTITION, NODE_C);

            assertThat(reg.freshPeersFor(STREAM, PARTITION, NODE_A)).extracting(ReplicaDescriptor::nodeId)
                                                                    .containsExactly(NODE_B);
        }

        @Test
        void freshPeersFor_syncingPeerWithinLagBound_isStillExcluded() {
            // The test above does NOT actually pin the state filter: a freshly registered peer seeds at
            // offset -1, so the lag check alone (100 - -1 = 101 > 10) would exclude it even with the
            // CAUGHT_UP filter deleted. Mutation testing caught that — removing the state filter left the
            // whole suite green. This case isolates it: NODE_C sits at the SAME watermark as the freshest
            // peer, so its lag is 0 and ONLY its SYNCING state can reject it.
            var reg = bounded();

            caughtUpAt(reg, NODE_B, 100L);
            reg.registerReplica(STREAM, PARTITION, NODE_C);
            reg.updateWatermark(STREAM, PARTITION, NODE_C, 100L, ReplicationState.SYNCING);

            assertThat(reg.replicasFor(STREAM, PARTITION)).filteredOn(descriptor -> descriptor.nodeId().equals(NODE_C))
                                                          .allMatch(descriptor -> descriptor.confirmedOffset() == 100L
                                                                               && descriptor.state() == ReplicationState.SYNCING);
            assertThat(reg.freshPeersFor(STREAM, PARTITION, NODE_A)).as("a SYNCING peer is not a read source however fresh its watermark")
                                                                    .extracting(ReplicaDescriptor::nodeId)
                                                                    .containsExactly(NODE_B);
        }

        @Test
        void freshPeersFor_self_isExcludedEvenWhenFreshest() {
            // Self is never lag-checked and never counted: a node does not ack itself, so its descriptor
            // keeps the SYNCING/-1 seed (#593) and its CAUGHT_UP comes from backfill completion.
            var reg = bounded();

            caughtUpAt(reg, NODE_A, 100L);
            caughtUpAt(reg, NODE_B, 100L);

            assertThat(reg.freshPeersFor(STREAM, PARTITION, NODE_A)).extracting(ReplicaDescriptor::nodeId)
                                                                    .containsExactly(NODE_B);
        }

        @Test
        void freshPeersFor_singlePeer_isAlwaysFresh() {
            // Documented limitation, pinned so it is a decision rather than a surprise: one sample admits
            // no relative judgement, so an only peer is compared against itself and can never be stale.
            var reg = bounded();

            caughtUpAt(reg, NODE_B, 1L);

            assertThat(reg.freshPeersFor(STREAM, PARTITION, NODE_A)).hasSize(1);
        }

        @Test
        void freshPeersFor_unboundedRegistry_admitsArbitraryLag() {
            var reg = replicaRegistry(ReplicaRegistry.CAUGHT_UP_LAG_UNBOUNDED);

            caughtUpAt(reg, NODE_B, 1_000_000L);
            caughtUpAt(reg, NODE_C, 0L);

            assertThat(reg.freshPeersFor(STREAM, PARTITION, NODE_A)).hasSize(2);
        }

        @Test
        void replicaRegistry_defaultFactory_isBoundedNotInert() {
            // An unwired path must come up GUARDED. A fence whose provenance is never exercised is a
            // fence that does nothing — this pins that the no-arg factory is not the unbounded one.
            assertThat(replicaRegistry().caughtUpMaxLagOffsets()).isEqualTo(ReplicaRegistry.DEFAULT_CAUGHT_UP_MAX_LAG_OFFSETS)
                                                                 .isNotEqualTo(ReplicaRegistry.CAUGHT_UP_LAG_UNBOUNDED);
        }
    }
}
