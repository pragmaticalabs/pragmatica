// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;


/// Regression test for the A6 cast-path bug in `projectFromCommittedAtoms`.
///
/// The KV store holds mixed key types — `AetherKey` entries written by Aether plus
/// `LeaderKey` entries written by the consensus layer. The snapshot supplier is typed
/// as `Supplier<Map<AetherKey, AetherValue>>`, which is a convenient erased view but
/// does not actually guarantee element types at runtime.
///
/// Prior to the fix, `collectNodesWithArtifacts` iterated `kv.keySet().stream()` which
/// caused `javac` to insert `checkcast AetherKey` on each stream element. The first
/// non-AetherKey (e.g., `LeaderKey`) triggered a `ClassCastException`, killing
/// `onLeaderChange` before `seedClusterConfigIfMissing`. The cluster never bootstrapped.
///
/// This test fails on the pre-fix implementation (`kv.keySet().stream()`) and passes on
/// the post-fix (`kv.entrySet().stream()`).
class HealthReconcilerActivatorMixedKeyProjectionTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();

    @Test
    void onLeaderChange_mixedKeyStoreWithLeaderKeyEntries_projectsWithoutClassCastException() {
        var rawMixed = new LinkedHashMap<Object, Object>();
        // Real consensus-layer entries — these are the trigger for the CCE in the pre-fix code.
        rawMixed.put(LeaderKey.INSTANCE, LeaderValue.leaderValue(SELF));
        // Genuine AetherKey entries that the projection should still process correctly.
        rawMixed.put(NodeLifecycleKey.nodeLifecycleKey(NODE_A),
                     NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY));
        rawMixed.put(NodeLifecycleKey.nodeLifecycleKey(NODE_B),
                     NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY));
        // NODE_A has an artifact deployed; NODE_B does not — so NODE_B must appear in nodesWithoutSlices.
        rawMixed.put(NodeArtifactKey.nodeArtifactKey(NODE_A, ARTIFACT),
                     NodeArtifactValue.activeNodeArtifactValue(0, List.of()));

        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<AetherKey, AetherValue> kv = (Map) rawMixed;

        var cluster = new RecordingClusterNode();
        var hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        var isLeader = new AtomicBoolean(false);
        var rabiaTerm = new AtomicLong(11L);
        var reconciler = HealthReconciler.healthReconciler(SELF,
                                                           cluster,
                                                           ClusterGenerationProjector.clusterGenerationProjector(),
                                                           hlcClock,
                                                           rabiaTerm::get,
                                                           isLeader,
                                                           AutoHealConfig.DEFAULT);
        var activator = HealthReconcilerActivator.healthReconcilerActivator(reconciler,
                                                                            isLeader,
                                                                            ClusterGenerationProjector.clusterGenerationProjector(),
                                                                            () -> kv,
                                                                            rabiaTerm::get,
                                                                            hlcClock);

        assertThatCode(() -> activator.onLeaderChange(new LeaderChange(Option.some(SELF), true)))
              .as("projection across mixed KV keys must not throw ClassCastException on LeaderKey")
              .doesNotThrowAnyException();

        var snapshot = reconciler.currentSnapshot();
        assertThat(snapshot.coreMembers()).as("both ON_DUTY lifecycles should project into coreMembers")
                                          .containsOnlyKeys(NODE_A, NODE_B);
        assertThat(snapshot.nodesWithoutSlices()).as("NODE_B has no artifact entry; NODE_A has one")
                                                 .containsExactly(NODE_B);
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
