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
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;


/// A3 regression — `onGovernorAnnouncementPut` must request a re-projection after
/// emitting its `GovernorAnnounced`/`CommunityDissolved` signal, so spokesman
/// assignments that follow see a fresh snapshot (not one that lags by up to one
/// ping interval).
class HealthReconcilerActivatorReprojectionTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();

    private RecordingClusterNode cluster;
    private HlcClock hlcClock;
    private AtomicBoolean isLeader;
    private AtomicLong rabiaTerm;
    private CountingReconciler reconciler;
    private HealthReconcilerActivator activator;
    private final AtomicReference<Map<AetherKey, AetherValue>> kvSnapshotRef = new AtomicReference<>(Map.of());

    @BeforeEach
    void setUp() {
        cluster = new RecordingClusterNode();
        hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        isLeader = new AtomicBoolean(true);
        rabiaTerm = new AtomicLong(1L);
        kvSnapshotRef.set(Map.of());
        var delegate = HealthReconciler.healthReconciler(SELF,
                                                          cluster,
                                                          ClusterGenerationProjector.clusterGenerationProjector(),
                                                          hlcClock,
                                                          rabiaTerm::get,
                                                          isLeader::get,
                                                          AutoHealConfig.DEFAULT);
        reconciler = new CountingReconciler(delegate);
        activator = HealthReconcilerActivator.healthReconcilerActivator(reconciler,
                                                                         isLeader::get,
                                                                         ClusterGenerationProjector.clusterGenerationProjector(),
                                                                         kvSnapshotRef::get,
                                                                         rabiaTerm::get,
                                                                         hlcClock,
                                                                         cluster,
                                                                         () -> SELF);
    }

    @Test
    void onGovernorAnnouncementPut_asLeader_requestsReprojection() {
        activator.onLeaderChange(new LeaderChange(Option.some(SELF), true));
        seedWithOneCoreNode();
        var baseline = reconciler.reprojectionCount();

        var value = GovernorAnnouncementValue.governorAnnouncementValue(NODE_A, 3);
        activator.onGovernorAnnouncementPut(new ValuePut<>(new KVCommand.Put<>(GovernorAnnouncementKey.forCommunity("pool-a"), value),
                                                            Option.none()));

        assertThat(reconciler.reprojectionCount())
                .as("governor-announcement must request re-projection")
                .isGreaterThan(baseline);
        assertThat(reconciler.lastReprojectionReason()).isEqualTo("governor-announcement");
    }

    @Test
    void onGovernorAnnouncementPut_dissolvedFlag_alsoRequestsReprojection() {
        activator.onLeaderChange(new LeaderChange(Option.some(SELF), true));
        seedWithOneCoreNode();
        var baseline = reconciler.reprojectionCount();

        var value = GovernorAnnouncementValue.governorAnnouncementValue(NODE_A, 3).withDissolved();
        activator.onGovernorAnnouncementPut(new ValuePut<>(new KVCommand.Put<>(GovernorAnnouncementKey.forCommunity("pool-a"), value),
                                                            Option.none()));

        assertThat(reconciler.reprojectionCount())
                .as("governor-announcement (dissolved branch) must also request re-projection")
                .isGreaterThan(baseline);
    }

    @Test
    void onGovernorAnnouncementPut_notLeader_doesNotRequestReprojection() {
        seedWithOneCoreNode();
        isLeader.set(false);
        var baseline = reconciler.reprojectionCount();

        var value = GovernorAnnouncementValue.governorAnnouncementValue(NODE_A, 3);
        activator.onGovernorAnnouncementPut(new ValuePut<>(new KVCommand.Put<>(GovernorAnnouncementKey.forCommunity("pool-a"), value),
                                                            Option.none()));

        assertThat(reconciler.reprojectionCount())
                .as("non-leader must not request re-projection")
                .isEqualTo(baseline);
    }

    private void seedWithOneCoreNode() {
        var base = ClusterGenerationSnapshot.empty(1L).withDesiredCoreSize(1);
        var members = new LinkedHashMap<NodeId, CoreMember>();
        members.put(NODE_A,
                     CoreMember.coreMember(NODE_A, "h-a", 9001, NodeLifecycleState.ON_DUTY, HealthHint.HEALTHY, Epoch.ZERO, Epoch.ZERO));
        reconciler.seedSnapshot(base.withCoreMembers(members));
    }

    /// Delegating reconciler that counts `requestReprojection` calls.
    private static final class CountingReconciler implements HealthReconciler {
        private final HealthReconciler delegate;
        private final AtomicInteger reprojectionCount = new AtomicInteger();
        private final AtomicReference<String> lastReason = new AtomicReference<>("");

        CountingReconciler(HealthReconciler delegate) {this.delegate = delegate;}

        int reprojectionCount() {return reprojectionCount.get();}
        String lastReprojectionReason() {return lastReason.get();}

        @Override public void start(Epoch leaderEpoch) {delegate.start(leaderEpoch);}
        @Override public void stop(StopReason reason) {delegate.stop(reason);}
        @Override public boolean isActive() {return delegate.isActive();}
        @Override public void onSignal(HealthSignal signal) {delegate.onSignal(signal);}
        @Override public ClusterGenerationSnapshot currentSnapshot() {return delegate.currentSnapshot();}
        @Override public Epoch currentEpoch() {return delegate.currentEpoch();}
        @Override public NodeId self() {return delegate.self();}
        @Override public void seedSnapshot(ClusterGenerationSnapshot snapshot) {delegate.seedSnapshot(snapshot);}
        @Override public void reseedMembership(ClusterGenerationSnapshot freshProjection) {delegate.reseedMembership(freshProjection);}

        @Override public void requestReprojection(Supplier<ClusterGenerationSnapshot> reprojectionSupplier, String reason) {
            reprojectionCount.incrementAndGet();
            lastReason.set(reason);
            delegate.requestReprojection(reprojectionSupplier, reason);
        }

        @Override public void requestReprojection(String reason) {
            reprojectionCount.incrementAndGet();
            lastReason.set(reason);
            delegate.requestReprojection(reason);
        }

        @Override public long consensusApplyFailedCount() {return delegate.consensusApplyFailedCount();}

        @Override public void emit(HealthSignal signal) {delegate.emit(signal);}
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final List<List<KVCommand<AetherKey>>> batches = new ArrayList<>();

        @Override public NodeId self() {return SELF;}
        @Override public TopologyManager topologyManager() {throw new UnsupportedOperationException("not used");}
        @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            batches.add(List.copyOf(commands));
            return (Promise) Promise.success(List.of());
        }
    }
}
