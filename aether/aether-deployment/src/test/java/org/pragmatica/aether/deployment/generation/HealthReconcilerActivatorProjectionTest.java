// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies that, on leader gain, the activator projects from committed atoms before
/// starting the reconciler (spec §8 — Commit 5c, "leader-gain projection").
class HealthReconcilerActivatorProjectionTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();

    private RecordingClusterNode cluster;
    private HlcClock hlcClock;
    private AtomicBoolean isLeader;
    private AtomicLong rabiaTerm;
    private HealthReconciler reconciler;
    private Map<AetherKey, AetherValue> kv;

    @BeforeEach
    void setUp() {
        cluster = new RecordingClusterNode();
        hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        isLeader = new AtomicBoolean(false);
        rabiaTerm = new AtomicLong(7L);
        reconciler = HealthReconciler.healthReconciler(SELF,
                                                        cluster,
                                                        ClusterGenerationProjector.clusterGenerationProjector(),
                                                        hlcClock,
                                                        rabiaTerm::get,
                                                        isLeader::get,
                                                        AutoHealConfig.DEFAULT);
        kv = new LinkedHashMap<>();
    }

    @Test
    void onLeaderChange_withCommittedLifecycleAtoms_seedsReconcilerSnapshot() {
        var addrA = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                          System.currentTimeMillis(),
                                                          "host-a",
                                                          9001,
                                                          Epoch.ZERO,
                                                          HlcTimestamp.ZERO);
        var addrB = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                          System.currentTimeMillis(),
                                                          "host-b",
                                                          9002,
                                                          Epoch.ZERO,
                                                          HlcTimestamp.ZERO);
        kv.put(NodeLifecycleKey.nodeLifecycleKey(NODE_A), addrA);
        kv.put(NodeLifecycleKey.nodeLifecycleKey(NODE_B), addrB);
        var activator = buildActivator();

        activator.onLeaderChange(new LeaderChange(Option.some(SELF), true));

        var snapshot = reconciler.currentSnapshot();
        assertThat(snapshot.coreMembers()).containsKeys(NODE_A, NODE_B);
        assertThat(snapshot.coreMembers().get(NODE_A).host()).isEqualTo("host-a");
    }

    @Test
    void onLeaderChange_withGovernorAndSpokesmanAtoms_seedsCommunityView() {
        kv.put(NodeLifecycleKey.nodeLifecycleKey(NODE_A),
                NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY));
        kv.put(GovernorAnnouncementKey.forCommunity("pool-x"),
                GovernorAnnouncementValue.governorAnnouncementValue(NODE_B, 3));
        kv.put(SpokesmanKey.spokesmanKey(NODE_A),
                SpokesmanValue.spokesmanValue(List.of("pool-x"), Epoch.epoch(7L, 0L), HlcTimestamp.ZERO, 1L));
        var activator = buildActivator();

        activator.onLeaderChange(new LeaderChange(Option.some(SELF), true));

        var snapshot = reconciler.currentSnapshot();
        assertThat(snapshot.communities()).containsKey("pool-x");
        assertThat(snapshot.communities().get("pool-x").governorNodeId()).isEqualTo(NODE_B);
    }

    @Test
    void onLeaderChange_emptyKv_seedsEmptySnapshotAtCurrentTerm() {
        var activator = buildActivator();

        activator.onLeaderChange(new LeaderChange(Option.some(SELF), true));

        var snapshot = reconciler.currentSnapshot();
        assertThat(snapshot.coreMembers()).isEmpty();
        assertThat(snapshot.epoch().rabiaTerm()).isEqualTo(7L);
    }

    private HealthReconcilerActivator buildActivator() {
        return HealthReconcilerActivator.healthReconcilerActivator(reconciler,
                                                                    isLeader::get,
                                                                    ClusterGenerationProjector.clusterGenerationProjector(),
                                                                    () -> Map.copyOf(kv),
                                                                    rabiaTerm::get,
                                                                    hlcClock);
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
    }
}
