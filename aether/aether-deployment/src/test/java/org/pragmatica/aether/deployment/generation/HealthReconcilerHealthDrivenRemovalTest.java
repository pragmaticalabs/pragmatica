// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.PartitionOwner;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies that accumulated `PingTimeout` + `SwimHint(FAULTY)` + `QuicDisconnect`
/// signals on the leader cause `HealthReconciler` to write
/// `NodeLifecycleKey = DECOMMISSIONED` and transfer DHT partition ownership,
/// per spec §8.2 row "PingTimeout (10×) + SwimHint FAULTY".
class HealthReconcilerHealthDrivenRemovalTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final NodeId NODE_C = NodeId.nodeId("node-c").unwrap();

    private RecordingClusterNode cluster;
    private AtomicBoolean isLeader;
    private AtomicLong rabiaTerm;
    private HealthReconciler reconciler;

    @BeforeEach
    void setUp() {
        cluster = new RecordingClusterNode();
        var hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        isLeader = new AtomicBoolean(true);
        rabiaTerm = new AtomicLong(1L);
        reconciler = HealthReconciler.healthReconciler(SELF,
                                                        cluster,
                                                        ClusterGenerationProjector.clusterGenerationProjector(),
                                                        hlcClock,
                                                        rabiaTerm::get,
                                                        isLeader,
                                                        AutoHealConfig.DEFAULT);
        reconciler.start(Epoch.epoch(1L, 0L));
        seedThreeCoreNodesWithPartitionOwnedByA();
    }

    @Test
    void accumulatedSignals_exceedingThreshold_writesLifecycleDecommissionedStampedWithCurrentEpoch() {
        reconciler.onSignal(new HealthSignal.QuicDisconnect(NODE_A, Epoch.epoch(1L, 0L)));
        reconciler.onSignal(new HealthSignal.SwimHint(NODE_A, HealthHint.FAULTY, Epoch.epoch(1L, 0L)));
        for (int i = 1; i <= 10; i++) {
            reconciler.onSignal(new HealthSignal.PingTimeout(NODE_A, i, Epoch.epoch(1L, 0L)));
        }

        var lifecycleWrites = cluster.writesTargetingLifecycle(NODE_A);
        assertThat(lifecycleWrites).hasSize(1);
        var value = (AetherValue.NodeLifecycleValue) lifecycleWrites.getFirst().value();
        assertThat(value.state()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
        assertThat(value.observedCoreEpoch().rabiaTerm()).isEqualTo(1L);
    }

    @Test
    void accumulatedSignals_onNodeOwningPartition_transferPartitionOwnershipWithBumpedTerm() {
        reconciler.onSignal(new HealthSignal.SwimHint(NODE_A, HealthHint.FAULTY, Epoch.epoch(1L, 0L)));
        for (int i = 1; i <= 10; i++) {
            reconciler.onSignal(new HealthSignal.PingTimeout(NODE_A, i, Epoch.epoch(1L, 0L)));
        }

        var partitionWrites = cluster.writesTargetingPartition("core");
        assertThat(partitionWrites).hasSize(1);
        var transfer = (AetherValue.DhtPartitionOwnershipValue) partitionWrites.getFirst().value();
        assertThat(transfer.ownerNodeId()).isIn(NODE_B, NODE_C);
        assertThat(transfer.ownershipTerm()).isEqualTo(2L);
    }

    @Test
    void pingTimeoutAndQuicDisconnect_withoutSwimHintFaulty_doesNotRemove() {
        reconciler.onSignal(new HealthSignal.QuicDisconnect(NODE_A, Epoch.epoch(1L, 0L)));
        for (int i = 1; i <= 20; i++) {
            reconciler.onSignal(new HealthSignal.PingTimeout(NODE_A, i, Epoch.epoch(1L, 0L)));
        }

        assertThat(cluster.writesTargetingLifecycle(NODE_A)).isEmpty();
    }

    @Test
    void swimFaulty_withoutPingTimeout_doesNotRemove() {
        reconciler.onSignal(new HealthSignal.SwimHint(NODE_A, HealthHint.FAULTY, Epoch.epoch(1L, 0L)));

        assertThat(cluster.writesTargetingLifecycle(NODE_A)).isEmpty();
    }

    private void seedThreeCoreNodesWithPartitionOwnedByA() {
        var members = new LinkedHashMap<NodeId, CoreMember>();
        members.put(NODE_A,
                     CoreMember.coreMember(NODE_A,
                                           "host-a",
                                           9001,
                                           NodeLifecycleState.ON_DUTY,
                                           HealthHint.HEALTHY,
                                           Epoch.epoch(1L, 0L),
                                           Epoch.epoch(1L, 0L)));
        members.put(NODE_B,
                     CoreMember.coreMember(NODE_B,
                                           "host-b",
                                           9002,
                                           NodeLifecycleState.ON_DUTY,
                                           HealthHint.HEALTHY,
                                           Epoch.epoch(1L, 0L),
                                           Epoch.epoch(1L, 0L)));
        members.put(NODE_C,
                     CoreMember.coreMember(NODE_C,
                                           "host-c",
                                           9003,
                                           NodeLifecycleState.ON_DUTY,
                                           HealthHint.HEALTHY,
                                           Epoch.epoch(1L, 0L),
                                           Epoch.epoch(1L, 0L)));
        var partitions = new LinkedHashMap<String, PartitionOwner>();
        partitions.put("core", PartitionOwner.partitionOwner("core", NODE_A, "core", Epoch.epoch(1L, 0L), 1L));
        var base = ClusterGenerationSnapshot.empty(1L).withDesiredCoreSize(3);
        var withMembers = base.withCoreMembers(members);
        var seeded = new ClusterGenerationSnapshot(withMembers.epoch(),
                                                    withMembers.committedAt(),
                                                    withMembers.reason(),
                                                    withMembers.desiredCoreSize(),
                                                    withMembers.coreMembers(),
                                                    withMembers.nodesWithoutSlices(),
                                                    withMembers.communities(),
                                                    partitions,
                                                    withMembers.derivedMode(),
                                                    withMembers.quiescence(),
                                                    withMembers.quiescenceDetail());
        reconciler.seedSnapshot(seeded);
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final List<List<KVCommand<AetherKey>>> batches = new ArrayList<>();

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {
            throw new UnsupportedOperationException("not used");
        }

        @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            batches.add(List.copyOf(commands));
            return (Promise) Promise.success(List.of());
        }

        List<KVCommand.Put<AetherKey, AetherValue>> applied() {
            var all = new ArrayList<KVCommand<AetherKey>>();
            batches.forEach(all::addAll);
            return all.stream()
                       .filter(c -> c instanceof KVCommand.Put<?, ?>)
                       .map(this::castPut)
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

        @SuppressWarnings({"unchecked", "rawtypes"})
        private KVCommand.Put<AetherKey, AetherValue> castPut(KVCommand<AetherKey> c) {
            return (KVCommand.Put<AetherKey, AetherValue>) (KVCommand.Put) c;
        }
    }
}
