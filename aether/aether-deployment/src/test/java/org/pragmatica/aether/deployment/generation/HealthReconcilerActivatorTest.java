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
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;


class HealthReconcilerActivatorTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();

    private RecordingClusterNode cluster;
    private HlcClock hlcClock;
    private AtomicBoolean isLeader;
    private AtomicLong rabiaTerm;
    private HealthReconciler reconciler;
    private HealthReconcilerActivator activator;

    @BeforeEach
    void setUp() {
        cluster = new RecordingClusterNode();
        hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        isLeader = new AtomicBoolean(false);
        rabiaTerm = new AtomicLong(1L);
        reconciler = HealthReconciler.healthReconciler(SELF,
                                                        cluster,
                                                        ClusterGenerationProjector.clusterGenerationProjector(),
                                                        hlcClock,
                                                        rabiaTerm::get,
                                                        isLeader,
                                                        AutoHealConfig.DEFAULT);
        activator = HealthReconcilerActivator.healthReconcilerActivator(reconciler, isLeader);
    }

    private ClusterGenerationSnapshot seedWithTwoCoreNodes() {
        var base = ClusterGenerationSnapshot.empty(1L).withDesiredCoreSize(2);
        var members = new LinkedHashMap<NodeId, CoreMember>();
        members.put(NODE_A, CoreMember.coreMember(NODE_A, "h-a", 9001, NodeLifecycleState.ON_DUTY, HealthHint.HEALTHY, Epoch.ZERO, Epoch.ZERO));
        members.put(NODE_B, CoreMember.coreMember(NODE_B, "h-b", 9002, NodeLifecycleState.ON_DUTY, HealthHint.HEALTHY, Epoch.ZERO, Epoch.ZERO));
        var seeded = base.withCoreMembers(members);
        reconciler.seedSnapshot(seeded);
        return seeded;
    }

    @Test
    void onLeaderChange_becomingLeader_startsReconciler() {
        activator.onLeaderChange(new LeaderChange(Option.some(SELF), true));

        assertThat(isLeader.get()).isTrue();
    }

    @Test
    void onLeaderChange_steppingDown_stopsReconcilerAndResetsSnapshot() {
        activator.onLeaderChange(new LeaderChange(Option.some(SELF), true));
        seedWithTwoCoreNodes();

        activator.onLeaderChange(new LeaderChange(Option.some(NODE_A), false));

        assertThat(isLeader.get()).isFalse();
        assertThat(reconciler.currentSnapshot().coreMembers()).isEmpty();
    }

    @Test
    void onGovernorAnnouncementPut_notLeader_isNoop() {
        seedWithTwoCoreNodes();
        isLeader.set(false);

        var value = GovernorAnnouncementValue.governorAnnouncementValue(NODE_A, 3);
        activator.onGovernorAnnouncementPut(new ValuePut<>(new KVCommand.Put<>(GovernorAnnouncementKey.forCommunity("pool-a"), value),
                                                            Option.none()));

        assertThat(cluster.appliedBatches()).isEmpty();
    }

    @Test
    void onGovernorAnnouncementPut_asLeader_triggersSpokesmanAssignment() {
        activator.onLeaderChange(new LeaderChange(Option.some(SELF), true));
        seedWithTwoCoreNodes();

        var value = GovernorAnnouncementValue.governorAnnouncementValue(NODE_A, 3);
        activator.onGovernorAnnouncementPut(new ValuePut<>(new KVCommand.Put<>(GovernorAnnouncementKey.forCommunity("pool-a"), value),
                                                            Option.none()));

        var spokesmanWrites = cluster.appliedBatches().stream()
                                     .flatMap(List::stream)
                                     .filter(c -> c instanceof KVCommand.Put<?, ?> put && put.key() instanceof SpokesmanKey)
                                     .toList();
        assertThat(spokesmanWrites).isNotEmpty();
    }

    @Test
    void onGovernorAnnouncementPut_dissolvedFlag_triggersCommunityDissolved() {
        activator.onLeaderChange(new LeaderChange(Option.some(SELF), true));
        seedWithTwoCoreNodes();

        var value = GovernorAnnouncementValue.governorAnnouncementValue(NODE_A, 3).withDissolved();
        activator.onGovernorAnnouncementPut(new ValuePut<>(new KVCommand.Put<>(GovernorAnnouncementKey.forCommunity("pool-a"), value),
                                                            Option.none()));

        // No partitions assigned to this community in the seed — signal accepted, no writes
        // (the CommunityDissolved handler short-circuits when community is not in current snapshot)
        assertThat(reconciler.currentEpoch().rabiaTerm()).isEqualTo(1L);
    }

    @Test
    void onSpokesmanPut_failedStatus_triggersReassignment() {
        activator.onLeaderChange(new LeaderChange(Option.some(SELF), true));
        seedWithTwoCoreNodes();

        var value = SpokesmanValue.spokesmanValue(List.of("pool-a"), Epoch.epoch(1L, 0L), HlcTimestamp.ZERO, 1L)
                                   .withFailure("timeout");
        activator.onSpokesmanPut(new ValuePut<>(new KVCommand.Put<>(SpokesmanKey.spokesmanKey(NODE_A), value), Option.none()));

        var spokesmanWrites = cluster.appliedBatches().stream()
                                     .flatMap(List::stream)
                                     .filter(c -> c instanceof KVCommand.Put<?, ?> put && put.key() instanceof SpokesmanKey)
                                     .toList();
        assertThat(spokesmanWrites).isNotEmpty();
    }

    @Test
    void onSpokesmanPut_activeStatus_doesNotWriteAtoms() {
        activator.onLeaderChange(new LeaderChange(Option.some(SELF), true));
        seedWithTwoCoreNodes();

        var value = SpokesmanValue.spokesmanValue(List.of("pool-a"), Epoch.epoch(1L, 0L), HlcTimestamp.ZERO, 1L)
                                   .withStatus(SpokesmanStatus.ACTIVE);
        activator.onSpokesmanPut(new ValuePut<>(new KVCommand.Put<>(SpokesmanKey.spokesmanKey(NODE_A), value), Option.none()));

        assertThat(cluster.appliedBatches()).isEmpty();
    }

    @Test
    void onNodeLifecyclePut_decommissionedAndNotLeader_isNoop() {
        seedWithTwoCoreNodes();
        isLeader.set(false);

        var value = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DECOMMISSIONED);
        activator.onNodeLifecyclePut(new ValuePut<>(new KVCommand.Put<>(NodeLifecycleKey.nodeLifecycleKey(NODE_A), value),
                                                     Option.none()));

        assertThat(cluster.appliedBatches()).isEmpty();
    }

    @Test
    void onNodeLifecyclePut_onDuty_logsButDoesNotReact() {
        activator.onLeaderChange(new LeaderChange(Option.some(SELF), true));
        seedWithTwoCoreNodes();

        var value = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY);
        activator.onNodeLifecyclePut(new ValuePut<>(new KVCommand.Put<>(NodeLifecycleKey.nodeLifecycleKey(NODE_A), value),
                                                     Option.none()));

        assertThat(cluster.appliedBatches()).isEmpty();
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

        List<List<KVCommand<AetherKey>>> appliedBatches() {
            return List.copyOf(batches);
        }
    }
}
