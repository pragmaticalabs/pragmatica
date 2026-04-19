// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.CommunityQuiescence;
import org.pragmatica.aether.slice.generation.CommunitySummary;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.OperatorIntent;
import org.pragmatica.aether.slice.generation.PartitionOwner;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;


class HealthReconcilerTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final NodeId NODE_C = NodeId.nodeId("node-c").unwrap();

    private RecordingClusterNode cluster;
    private HlcClock hlcClock;
    private AtomicBoolean isLeader;
    private AtomicLong rabiaTerm;
    private HealthReconciler reconciler;

    @BeforeEach
    void setUp() {
        cluster = new RecordingClusterNode(SELF);
        hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        isLeader = new AtomicBoolean(true);
        rabiaTerm = new AtomicLong(1L);
        reconciler = HealthReconciler.healthReconciler(SELF,
                                                        cluster,
                                                        ClusterGenerationProjector.clusterGenerationProjector(),
                                                        hlcClock,
                                                        rabiaTerm::get,
                                                        isLeader,
                                                        AutoHealConfig.DEFAULT);
        reconciler.start();
    }

    private ClusterGenerationSnapshot seedSnapshotWithThreeCoreNodes() {
        var snapshot = ClusterGenerationSnapshot.empty(1L).withDesiredCoreSize(3);
        var coreMembers = new LinkedHashMap<NodeId, CoreMember>();
        coreMembers.put(NODE_A, CoreMember.coreMember(NODE_A,
                                                       "host-a",
                                                       9001,
                                                       NodeLifecycleState.ON_DUTY,
                                                       HealthHint.HEALTHY,
                                                       Epoch.epoch(1L, 0L),
                                                       Epoch.epoch(1L, 0L)));
        coreMembers.put(NODE_B, CoreMember.coreMember(NODE_B,
                                                       "host-b",
                                                       9002,
                                                       NodeLifecycleState.ON_DUTY,
                                                       HealthHint.HEALTHY,
                                                       Epoch.epoch(1L, 0L),
                                                       Epoch.epoch(1L, 0L)));
        coreMembers.put(NODE_C, CoreMember.coreMember(NODE_C,
                                                       "host-c",
                                                       9003,
                                                       NodeLifecycleState.ON_DUTY,
                                                       HealthHint.HEALTHY,
                                                       Epoch.epoch(1L, 0L),
                                                       Epoch.epoch(1L, 0L)));
        var seeded = snapshot.withCoreMembers(coreMembers);
        reconciler.seedSnapshot(seeded);
        return seeded;
    }

    @Nested
    class DecisionTable {
        @Test
        void onSignal_pingTimeoutBelowSuspectThreshold_noAtomWriteAndNoHintChange() {
            seedSnapshotWithThreeCoreNodes();
            var epochBefore = reconciler.currentEpoch();

            reconciler.onSignal(new HealthSignal.PingTimeout(NODE_A, 1, epochBefore));

            assertThat(cluster.applied()).isEmpty();
            assertThat(reconciler.currentSnapshot().coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.HEALTHY);
        }

        @Test
        void onSignal_threeConsecutivePingTimeouts_marksSuspectedInMemory() {
            seedSnapshotWithThreeCoreNodes();

            reconciler.onSignal(new HealthSignal.PingTimeout(NODE_A, 1, Epoch.epoch(1L, 0L)));
            reconciler.onSignal(new HealthSignal.PingTimeout(NODE_A, 2, Epoch.epoch(1L, 0L)));
            reconciler.onSignal(new HealthSignal.PingTimeout(NODE_A, 3, Epoch.epoch(1L, 0L)));

            assertThat(cluster.applied()).isEmpty();
            assertThat(reconciler.currentSnapshot().coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.SUSPECTED);
        }

        @Test
        void onSignal_tenPingTimeoutsPlusSwimFaulty_writesLeftAndBumpsOwnershipTerm() {
            var snapshot = seedSnapshotWithThreeCoreNodes();
            var partitioned = snapshot.withCoreMembers(snapshot.coreMembers());
            reconciler.seedSnapshot(withPartitionOwner(partitioned, "core", NODE_A, "core", 1L));

            reconciler.onSignal(new HealthSignal.SwimHint(NODE_A, HealthHint.FAULTY, Epoch.epoch(1L, 0L)));
            for (int i = 1; i <= 10; i++) {
                reconciler.onSignal(new HealthSignal.PingTimeout(NODE_A, i, Epoch.epoch(1L, 0L)));
            }

            assertThat(cluster.applied()).isNotEmpty();
            var lifecycleWrites = cluster.writesTargetingLifecycle(NODE_A);
            assertThat(lifecycleWrites).hasSize(1);
            assertThat(((AetherValue.NodeLifecycleValue) lifecycleWrites.getFirst().value()).state()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
            assertThat(cluster.writesTargetingPartition("core")).hasSize(1);
        }

        @Test
        void onSignal_swimFaultyAlone_marksSuspectedDoesNotRemove() {
            seedSnapshotWithThreeCoreNodes();

            reconciler.onSignal(new HealthSignal.SwimHint(NODE_A, HealthHint.FAULTY, Epoch.epoch(1L, 0L)));

            assertThat(cluster.applied()).isEmpty();
            assertThat(reconciler.currentSnapshot().coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.SUSPECTED);
        }

        @Test
        void onSignal_swimHealthyClearsSuspicion_clearsSuspected() {
            seedSnapshotWithThreeCoreNodes();
            reconciler.onSignal(new HealthSignal.SwimHint(NODE_A, HealthHint.SUSPECTED, Epoch.epoch(1L, 0L)));

            reconciler.onSignal(new HealthSignal.SwimHint(NODE_A, HealthHint.HEALTHY, Epoch.epoch(1L, 0L)));

            assertThat(reconciler.currentSnapshot().coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.HEALTHY);
        }

        @Test
        void onSignal_quicDisconnect_alone_doesNotRemove() {
            seedSnapshotWithThreeCoreNodes();

            reconciler.onSignal(new HealthSignal.QuicDisconnect(NODE_A, Epoch.epoch(1L, 0L)));

            assertThat(cluster.applied()).isEmpty();
        }

        @Test
        void onSignal_drainCompleted_writesDecommissionedViaSingleWriter() {
            // Spec §8: CDM delegates the lifecycle transition to HealthReconciler via DrainCompleted.
            // HealthReconciler must write NodeLifecycleKey = DECOMMISSIONED authoritatively.
            seedSnapshotWithThreeCoreNodes();

            reconciler.onSignal(new HealthSignal.DrainCompleted(NODE_A, Epoch.epoch(1L, 0L)));

            var lifecycleWrites = cluster.writesTargetingLifecycle(NODE_A);
            assertThat(lifecycleWrites).hasSize(1);
            assertThat(((AetherValue.NodeLifecycleValue) lifecycleWrites.getFirst().value()).state())
                .isEqualTo(NodeLifecycleState.DECOMMISSIONED);
        }

        @Test
        void onSignal_drainCompletedForUnknownNode_isNoOp() {
            seedSnapshotWithThreeCoreNodes();
            var unknown = NodeId.nodeId("never-seen").unwrap();

            reconciler.onSignal(new HealthSignal.DrainCompleted(unknown, Epoch.epoch(1L, 0L)));

            assertThat(cluster.applied()).isEmpty();
        }

        @Test
        void onSignal_governorAnnouncedForNewCommunity_assignsSpokesman() {
            seedSnapshotWithThreeCoreNodes();

            reconciler.onSignal(new HealthSignal.GovernorAnnounced("worker-pool-a", NODE_A, 1L));

            var spokesmanWrites = cluster.writesTargetingSpokesman();
            assertThat(spokesmanWrites).hasSize(1);
            var value = (SpokesmanValue) spokesmanWrites.getFirst().value();
            assertThat(value.communities()).containsExactly("worker-pool-a");
        }

        @Test
        void onSignal_communityDissolved_reassignsPartitionsInOneBatch() {
            var seeded = seedSnapshotWithThreeCoreNodes();
            var communities = new LinkedHashMap<String, CommunitySummary>();
            communities.put("worker-pool-a",
                             CommunitySummary.communitySummary("worker-pool-a",
                                                                NODE_A,
                                                                1L,
                                                                Epoch.epoch(1L, 1L),
                                                                3,
                                                                3,
                                                                0,
                                                                0,
                                                                Set.of("worker-pool-a", "worker-pool-a-2"),
                                                                Option.some(NODE_B),
                                                                Epoch.epoch(1L, 1L),
                                                                CommunityQuiescence.DISSOLVING,
                                                                ""));
            var withCommunity = new ClusterGenerationSnapshot(seeded.epoch(),
                                                               seeded.rabiaTerm(),
                                                               seeded.committedAt(),
                                                               seeded.reason(),
                                                               seeded.desiredCoreSize(),
                                                               seeded.coreMembers(),
                                                               communities,
                                                               seeded.partitions(),
                                                               seeded.derivedMode(),
                                                               seeded.quiescence(),
                                                               seeded.quiescenceDetail());
            reconciler.seedSnapshot(withPartitionOwner(withCommunity, "worker-pool-a", NODE_A, "worker-pool-a", 3L));

            reconciler.onSignal(new HealthSignal.CommunityDissolved("worker-pool-a"));

            var batches = cluster.batches();
            assertThat(batches).hasSize(1);
            assertThat(cluster.writesTargetingPartition("worker-pool-a")).hasSize(1);
            var partitionValue = (DhtPartitionOwnershipValue) cluster.writesTargetingPartition("worker-pool-a").getFirst().value();
            assertThat(partitionValue.ownerCommunityId()).isEqualTo("core");
            assertThat(partitionValue.ownershipTerm()).isEqualTo(4L);
        }

        @Test
        void onSignal_spokesmanAssignmentFailed_reassignsAffectedCommunities() {
            seedSnapshotWithThreeCoreNodes();

            reconciler.onSignal(new HealthSignal.SpokesmanAssignmentFailed(NODE_A,
                                                                            List.of("worker-pool-a", "worker-pool-b"),
                                                                            "boot failure"));

            var spokesmanWrites = cluster.writesTargetingSpokesman();
            assertThat(spokesmanWrites).isNotEmpty();
            var failedNodeReset = spokesmanWrites.stream()
                                                  .filter(put -> ((SpokesmanKey) put.key()).coreNodeId().equals(NODE_A))
                                                  .findFirst();
            assertThat(failedNodeReset).isPresent();
            assertThat(((SpokesmanValue) failedNodeReset.get().value()).communities()).isEmpty();
        }

        @Test
        void onSignal_operatorRemoveMember_writesDrainingFirstNotDecommissioned() {
            // Spec §8.2: OperatorAction(remove(n)) transitions via DRAINING. The final
            // move to DECOMMISSIONED is driven by CDM's DrainCompleted signal.
            seedSnapshotWithThreeCoreNodes();

            reconciler.onSignal(new HealthSignal.OperatorAction(new OperatorIntent.RemoveMember(NODE_A)));

            var lifecycleWrites = cluster.writesTargetingLifecycle(NODE_A);
            assertThat(lifecycleWrites).hasSize(1);
            assertThat(((AetherValue.NodeLifecycleValue) lifecycleWrites.getFirst().value()).state())
                .isEqualTo(NodeLifecycleState.DRAINING);
        }

        @Test
        void onSignal_operatorRemoveMember_whenAlreadyDraining_isNoOp() {
            // Idempotent: duplicate RemoveMember during an in-flight drain must not re-write the atom.
            var base = seedSnapshotWithThreeCoreNodes();
            var members = new LinkedHashMap<>(base.coreMembers());
            members.put(NODE_A,
                         CoreMember.coreMember(NODE_A,
                                               "host-a",
                                               9001,
                                               NodeLifecycleState.DRAINING,
                                               HealthHint.SUSPECTED,
                                               Epoch.epoch(1L, 0L),
                                               Epoch.epoch(1L, 0L)));
            reconciler.seedSnapshot(base.withCoreMembers(members));

            reconciler.onSignal(new HealthSignal.OperatorAction(new OperatorIntent.RemoveMember(NODE_A)));

            assertThat(cluster.writesTargetingLifecycle(NODE_A)).isEmpty();
        }

        @Test
        void onSignal_operatorRemoveThenDrainCompleted_writesDrainingThenDecommissioned() {
            // Full path: operator -> DRAINING, then CDM completes drain -> DECOMMISSIONED.
            var base = seedSnapshotWithThreeCoreNodes();

            reconciler.onSignal(new HealthSignal.OperatorAction(new OperatorIntent.RemoveMember(NODE_A)));
            // Simulate the atom landing: flip NODE_A's lifecycle in the snapshot to DRAINING.
            var members = new LinkedHashMap<>(base.coreMembers());
            members.put(NODE_A,
                         CoreMember.coreMember(NODE_A,
                                               "host-a",
                                               9001,
                                               NodeLifecycleState.DRAINING,
                                               HealthHint.SUSPECTED,
                                               Epoch.epoch(1L, 0L),
                                               Epoch.epoch(1L, 0L)));
            reconciler.seedSnapshot(base.withCoreMembers(members));
            reconciler.onSignal(new HealthSignal.DrainCompleted(NODE_A, Epoch.epoch(1L, 0L)));

            var lifecycleWrites = cluster.writesTargetingLifecycle(NODE_A);
            assertThat(lifecycleWrites).hasSize(2);
            assertThat(((AetherValue.NodeLifecycleValue) lifecycleWrites.get(0).value()).state())
                .isEqualTo(NodeLifecycleState.DRAINING);
            assertThat(((AetherValue.NodeLifecycleValue) lifecycleWrites.get(1).value()).state())
                .isEqualTo(NodeLifecycleState.DECOMMISSIONED);
        }

        @Test
        void onSignal_operatorDrainMember_writesLifecycleDraining() {
            seedSnapshotWithThreeCoreNodes();

            reconciler.onSignal(new HealthSignal.OperatorAction(new OperatorIntent.DrainMember(NODE_A)));

            var lifecycleWrites = cluster.writesTargetingLifecycle(NODE_A);
            assertThat(lifecycleWrites).hasSize(1);
            assertThat(((AetherValue.NodeLifecycleValue) lifecycleWrites.getFirst().value()).state()).isEqualTo(NodeLifecycleState.DRAINING);
        }

        @Test
        void onSignal_operatorSetDesiredSize_updatesSnapshotWithoutAtomWrite() {
            seedSnapshotWithThreeCoreNodes();

            reconciler.onSignal(new HealthSignal.OperatorAction(new OperatorIntent.SetDesiredSize(5)));

            assertThat(cluster.applied()).isEmpty();
            assertThat(reconciler.currentSnapshot().desiredCoreSize()).isEqualTo(5);
        }
    }

    @Nested
    class EpochMonotonicity {
        @Test
        void onSignal_multipleSignals_epochStrictlyAdvances() {
            seedSnapshotWithThreeCoreNodes();
            var start = reconciler.currentEpoch();

            reconciler.onSignal(new HealthSignal.SwimHint(NODE_A, HealthHint.SUSPECTED, Epoch.epoch(1L, 0L)));
            var afterFirst = reconciler.currentEpoch();
            reconciler.onSignal(new HealthSignal.SwimHint(NODE_B, HealthHint.SUSPECTED, Epoch.epoch(1L, 0L)));
            var afterSecond = reconciler.currentEpoch();

            assertThat(afterFirst.isStrictlyAfter(start)).isTrue();
            assertThat(afterSecond.isStrictlyAfter(afterFirst)).isTrue();
        }

        @Test
        void onSignal_noChangeToHealthHint_doesNotBumpCounter() {
            seedSnapshotWithThreeCoreNodes();
            reconciler.onSignal(new HealthSignal.SwimHint(NODE_A, HealthHint.SUSPECTED, Epoch.epoch(1L, 0L)));
            var afterFirst = reconciler.currentEpoch();

            reconciler.onSignal(new HealthSignal.SwimHint(NODE_A, HealthHint.SUSPECTED, Epoch.epoch(1L, 0L)));

            assertThat(reconciler.currentEpoch()).isEqualTo(afterFirst);
        }
    }

    @Nested
    class LeaderGating {
        @Test
        void onSignal_whenNotLeader_isNoOp() {
            seedSnapshotWithThreeCoreNodes();
            isLeader.set(false);

            reconciler.onSignal(new HealthSignal.OperatorAction(new OperatorIntent.RemoveMember(NODE_A)));

            assertThat(cluster.applied()).isEmpty();
        }

        @Test
        void onSignal_afterStop_isNoOp() {
            seedSnapshotWithThreeCoreNodes();
            reconciler.stop();

            reconciler.onSignal(new HealthSignal.OperatorAction(new OperatorIntent.RemoveMember(NODE_A)));

            assertThat(cluster.applied()).isEmpty();
        }

        @Test
        void onSignal_afterRabiaTermBump_resetsEpochToNewTermZero() {
            seedSnapshotWithThreeCoreNodes();
            rabiaTerm.set(5L);

            reconciler.onSignal(new HealthSignal.SwimHint(NODE_A, HealthHint.SUSPECTED, Epoch.epoch(5L, 0L)));

            assertThat(reconciler.currentEpoch().rabiaTerm()).isEqualTo(5L);
        }
    }

    @Nested
    class PartitionTransferAtomicity {
        @Test
        void onSignal_communityDissolvedWithTwoPartitions_allWritesInOneBatch() {
            var seeded = seedSnapshotWithThreeCoreNodes();
            var communities = new LinkedHashMap<String, CommunitySummary>();
            communities.put("worker-pool-a",
                             CommunitySummary.communitySummary("worker-pool-a",
                                                                NODE_A,
                                                                1L,
                                                                Epoch.epoch(1L, 1L),
                                                                3,
                                                                3,
                                                                0,
                                                                0,
                                                                Set.of("p-1", "p-2"),
                                                                Option.some(NODE_B),
                                                                Epoch.epoch(1L, 1L),
                                                                CommunityQuiescence.DISSOLVING,
                                                                ""));
            var partitions = new LinkedHashMap<String, PartitionOwner>();
            partitions.put("p-1", PartitionOwner.partitionOwner("p-1", NODE_A, "worker-pool-a", Epoch.epoch(1L, 0L), 1L));
            partitions.put("p-2", PartitionOwner.partitionOwner("p-2", NODE_A, "worker-pool-a", Epoch.epoch(1L, 0L), 1L));
            var withCommunityAndPartitions = new ClusterGenerationSnapshot(seeded.epoch(),
                                                                             seeded.rabiaTerm(),
                                                                             seeded.committedAt(),
                                                                             seeded.reason(),
                                                                             seeded.desiredCoreSize(),
                                                                             seeded.coreMembers(),
                                                                             communities,
                                                                             partitions,
                                                                             seeded.derivedMode(),
                                                                             seeded.quiescence(),
                                                                             seeded.quiescenceDetail());
            reconciler.seedSnapshot(withCommunityAndPartitions);

            reconciler.onSignal(new HealthSignal.CommunityDissolved("worker-pool-a"));

            assertThat(cluster.batches()).hasSize(1);
            var batch = cluster.batches().getFirst();
            var partitionWrites = batch.stream()
                                        .filter(c -> c instanceof KVCommand.Put<?, ?> put && put.key() instanceof DhtPartitionOwnershipKey)
                                        .count();
            assertThat(partitionWrites).isEqualTo(2);
        }
    }

    private static ClusterGenerationSnapshot withPartitionOwner(ClusterGenerationSnapshot base,
                                                                String partitionId,
                                                                NodeId ownerNodeId,
                                                                String ownerCommunityId,
                                                                long ownershipTerm) {
        var updated = new LinkedHashMap<>(base.partitions());
        updated.put(partitionId,
                     PartitionOwner.partitionOwner(partitionId, ownerNodeId, ownerCommunityId, Epoch.epoch(1L, 0L), ownershipTerm));
        return new ClusterGenerationSnapshot(base.epoch(),
                                              base.rabiaTerm(),
                                              base.committedAt(),
                                              base.reason(),
                                              base.desiredCoreSize(),
                                              base.coreMembers(),
                                              base.communities(),
                                              updated,
                                              base.derivedMode(),
                                              base.quiescence(),
                                              base.quiescenceDetail());
    }

    /// In-memory `ClusterNode` that records every applied batch and lets tests introspect them.
    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final List<List<KVCommand<AetherKey>>> batches = new ArrayList<>();

        RecordingClusterNode(NodeId self) {
            this.self = self;
        }

        @Override public NodeId self() {
            return self;
        }

        @Override public TopologyManager topologyManager() {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override public Promise<Unit> start() {
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            batches.add(List.copyOf(commands));
            return (Promise) Promise.success(List.of());
        }

        List<List<KVCommand<AetherKey>>> batches() {
            return List.copyOf(batches);
        }

        List<KVCommand.Put<AetherKey, AetherValue>> applied() {
            return flatten().stream()
                                .filter(c -> c instanceof KVCommand.Put<?, ?>)
                                .map(c -> castPut(c))
                                .toList();
        }

        List<KVCommand.Put<AetherKey, AetherValue>> writesTargetingLifecycle(NodeId nodeId) {
            return applied().stream()
                                .filter(put -> put.key() instanceof NodeLifecycleKey lifecycle && lifecycle.nodeId().equals(nodeId))
                                .toList();
        }

        List<KVCommand.Put<AetherKey, AetherValue>> writesTargetingPartition(String partitionId) {
            return applied().stream()
                                .filter(put -> put.key() instanceof DhtPartitionOwnershipKey partition && partition.partitionId().equals(partitionId))
                                .toList();
        }

        List<KVCommand.Put<AetherKey, AetherValue>> writesTargetingSpokesman() {
            return applied().stream()
                                .filter(put -> put.key() instanceof SpokesmanKey)
                                .toList();
        }

        private List<KVCommand<AetherKey>> flatten() {
            var all = new ArrayList<KVCommand<AetherKey>>();
            batches.forEach(all::addAll);
            return all;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static KVCommand.Put<AetherKey, AetherValue> castPut(KVCommand<AetherKey> c) {
            return (KVCommand.Put<AetherKey, AetherValue>) (KVCommand.Put) c;
        }
    }
}
