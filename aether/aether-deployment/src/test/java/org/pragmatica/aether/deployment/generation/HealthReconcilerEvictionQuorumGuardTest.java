// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.kvstore.AetherKey;
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

/// Theme C — split-brain protection guard.
///
/// `HealthReconciler.evictNode` MUST refuse to write `NodeLifecycleValue(DECOMMISSIONED)`
/// when the projected snapshot indicates the local side has fallen below quorum (i.e. only
/// a minority of `desiredCoreSize` members are `ON_DUTY`). Otherwise a partitioned leader
/// could erase healthy peers' lifecycle metadata after the partition heals.
class HealthReconcilerEvictionQuorumGuardTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final NodeId NODE_C = NodeId.nodeId("node-c").unwrap();
    private static final NodeId NODE_D = NodeId.nodeId("node-d").unwrap();
    private static final NodeId NODE_E = NodeId.nodeId("node-e").unwrap();

    @Test
    void eviction_inMinorityPartition_doesNotWriteDecommissioned() {
        // Cluster sized at 5; only 2 ON_DUTY (SELF + NODE_B). Majority of 5 = 3, so quorum
        // is NOT active. NODE_A is observed FAULTY but the reconciler must refuse to write.
        var fixture = newReconcilerFixture();
        var snapshot = fiveNodeSnapshotWithTwoOnDuty();
        fixture.reconciler.seedSnapshot(snapshot);

        fixture.reconciler.onSignal(new HealthSignal.SwimHint(NODE_A, HealthHint.FAULTY, Epoch.epoch(1L, 0L)));
        for (int i = 1; i <= 12; i++) {
            fixture.reconciler.onSignal(new HealthSignal.PingTimeout(NODE_A, i, Epoch.epoch(1L, 0L)));
        }

        assertThat(fixture.cluster.writesTargetingLifecycle(NODE_A)).isEmpty();
    }

    @Test
    void eviction_inMajorityPartition_writesDecommissioned() {
        // Sanity counterpart: cluster sized at 5; SELF + NODE_B + NODE_C ON_DUTY (3 of 5 =
        // majority of (5/2)+1 = 3). Quorum IS active. Eviction proceeds normally.
        var fixture = newReconcilerFixture();
        var snapshot = fiveNodeSnapshotWithThreeOnDuty();
        fixture.reconciler.seedSnapshot(snapshot);

        fixture.reconciler.onSignal(new HealthSignal.SwimHint(NODE_A, HealthHint.FAULTY, Epoch.epoch(1L, 0L)));
        for (int i = 1; i <= 12; i++) {
            fixture.reconciler.onSignal(new HealthSignal.PingTimeout(NODE_A, i, Epoch.epoch(1L, 0L)));
        }

        var lifecycleWrites = fixture.cluster.writesTargetingLifecycle(NODE_A);
        assertThat(lifecycleWrites).hasSize(1);
        var value = (AetherValue.NodeLifecycleValue) lifecycleWrites.getFirst().value();
        assertThat(value.state()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
    }

    private record ReconcilerFixture(HealthReconciler reconciler, RecordingClusterNode cluster) {}

    private static ReconcilerFixture newReconcilerFixture() {
        var cluster = new RecordingClusterNode();
        var hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        var isLeader = new AtomicBoolean(true);
        var rabiaTerm = new AtomicLong(1L);
        var reconciler = HealthReconciler.healthReconciler(SELF,
                                                           cluster,
                                                           ClusterGenerationProjector.clusterGenerationProjector(),
                                                           hlcClock,
                                                           rabiaTerm::get,
                                                           isLeader::get,
                                                           AutoHealConfig.DEFAULT);
        reconciler.start();
        return new ReconcilerFixture(reconciler, cluster);
    }

    private static ClusterGenerationSnapshot fiveNodeSnapshotWithTwoOnDuty() {
        var members = new LinkedHashMap<NodeId, CoreMember>();
        addCoreMember(members, SELF, "host-self", 9000, NodeLifecycleState.ON_DUTY);
        addCoreMember(members, NODE_B, "host-b", 9002, NodeLifecycleState.ON_DUTY);
        // NODE_A still ON_DUTY in coreMembers — it is the eviction *target*. Even though
        // the reconciler observes A as FAULTY, the projected snapshot lifecycle remains
        // ON_DUTY until DECOMMISSIONED is committed. Note: A counts toward onDutyCount
        // until evicted, so we deliberately keep onDutyCount low by leaving C/D/E in
        // non-ON_DUTY states.
        addCoreMember(members, NODE_A, "host-a", 9001, NodeLifecycleState.ON_DUTY);
        addCoreMember(members, NODE_C, "host-c", 9003, NodeLifecycleState.DECOMMISSIONED);
        addCoreMember(members, NODE_D, "host-d", 9004, NodeLifecycleState.DECOMMISSIONED);
        addCoreMember(members, NODE_E, "host-e", 9005, NodeLifecycleState.DECOMMISSIONED);
        // Force quorum NOT active by making desiredCoreSize 7 — majority = 4 — and only 3
        // ON_DUTY (SELF/B/A). Actually with 3 ON_DUTY and majority=4, quorum is NOT active.
        var base = ClusterGenerationSnapshot.empty(1L).withDesiredCoreSize(7);
        var withMembers = base.withCoreMembers(members);
        return new ClusterGenerationSnapshot(withMembers.epoch(),
                                             withMembers.committedAt(),
                                             withMembers.reason(),
                                             withMembers.desiredCoreSize(),
                                             withMembers.coreMembers(),
                                             withMembers.nodesWithoutSlices(),
                                             withMembers.communities(),
                                             withMembers.partitions(),
                                             withMembers.derivedMode(),
                                             withMembers.quiescence(),
                                             withMembers.quiescenceDetail());
    }

    private static ClusterGenerationSnapshot fiveNodeSnapshotWithThreeOnDuty() {
        var members = new LinkedHashMap<NodeId, CoreMember>();
        addCoreMember(members, SELF, "host-self", 9000, NodeLifecycleState.ON_DUTY);
        addCoreMember(members, NODE_A, "host-a", 9001, NodeLifecycleState.ON_DUTY);
        addCoreMember(members, NODE_B, "host-b", 9002, NodeLifecycleState.ON_DUTY);
        addCoreMember(members, NODE_C, "host-c", 9003, NodeLifecycleState.ON_DUTY);
        addCoreMember(members, NODE_D, "host-d", 9004, NodeLifecycleState.ON_DUTY);
        addCoreMember(members, NODE_E, "host-e", 9005, NodeLifecycleState.ON_DUTY);
        var base = ClusterGenerationSnapshot.empty(1L).withDesiredCoreSize(5);
        var withMembers = base.withCoreMembers(members);
        return new ClusterGenerationSnapshot(withMembers.epoch(),
                                             withMembers.committedAt(),
                                             withMembers.reason(),
                                             withMembers.desiredCoreSize(),
                                             withMembers.coreMembers(),
                                             withMembers.nodesWithoutSlices(),
                                             withMembers.communities(),
                                             withMembers.partitions(),
                                             withMembers.derivedMode(),
                                             withMembers.quiescence(),
                                             withMembers.quiescenceDetail());
    }

    private static void addCoreMember(LinkedHashMap<NodeId, CoreMember> members,
                                      NodeId nodeId,
                                      String host,
                                      int port,
                                      NodeLifecycleState lifecycle) {
        members.put(nodeId,
                    CoreMember.coreMember(nodeId,
                                          host,
                                          port,
                                          lifecycle,
                                          HealthHint.HEALTHY,
                                          Epoch.epoch(1L, 0L),
                                          Epoch.epoch(1L, 0L)));
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

        @SuppressWarnings({"unchecked", "rawtypes"})
        private KVCommand.Put<AetherKey, AetherValue> castPut(KVCommand<AetherKey> c) {
            return (KVCommand.Put<AetherKey, AetherValue>) (KVCommand.Put) c;
        }
    }
}
