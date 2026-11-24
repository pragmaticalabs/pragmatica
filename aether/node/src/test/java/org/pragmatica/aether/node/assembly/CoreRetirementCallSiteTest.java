// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node.assembly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.NodeReplacementIndex;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ClusterConfig;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.NodeAddress;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1055: the argument [TopologyAssembly#topologyManager] hands to `retireExcludedCores` is the reconciler holder it returns.
/// [CoreRetirementWiringTest] passes the supplier in by hand, so a call site that wired some other supplier (here: one that is
/// always empty, which fails closed and retires nothing, ever) would pass it. This test drives the assembly itself: the periodic
/// retirement task it defers, the real topology manager it builds, the drain registry that manager enqueues into.
class CoreRetirementCallSiteTest {
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId VOTER_B = new NodeId("voter-b");
    private static final NodeId YOUNG = new NodeId("young-excluded-core");
    private static final NodeId MATURE = new NodeId("mature-excluded-core");

    @SuppressWarnings("unchecked")
    @Test
    void retirementTask_readsTheReconcilerHolderTheAssemblyReturns_drainsMatureNotYoung() throws Exception {
        var config = mock(AetherNodeConfig.class);

        when(config.self()).thenReturn(SELF);
        when(config.workerConfig()).thenReturn(Option.none());
        when(config.environment()).thenReturn(Option.none());
        when(config.autoHeal()).thenReturn(org.pragmatica.aether.environment.AutoHealConfig.DEFAULT);
        when(config.topology()).thenReturn(new TopologyConfig(SELF,
                                                              5,
                                                              timeSpan(1).seconds(),
                                                              timeSpan(10).seconds(),
                                                              List.of(NodeInfo.nodeInfo(SELF, new NodeAddress("127.0.0.1", 9001)),
                                                                      NodeInfo.nodeInfo(VOTER_B,
                                                                                        new NodeAddress("127.0.0.1", 9002)))));

        var clusterConfig = mock(AetherValue.ClusterConfigValue.class);

        when(clusterConfig.coreCount()).thenReturn(2);

        var store = (KVStore<AetherKey, AetherValue>) mock(KVStore.class);

        Mockito.doReturn(Option.none()).when(store).getTyped(any(), any());
        Mockito.doReturn(Option.some(clusterConfig)).when(store).getTyped(eq(AetherKey.ClusterConfigKey.CURRENT), any());

        var observer = mock(TopologyObserver.class);
        var leader = mock(LeaderManager.class);
        var cluster = (RabiaNode<KVCommand<AetherKey>>) mock(RabiaNode.class);
        var voters = new VoterConfiguration(1L, new ClusterConfig(List.of(SELF, VOTER_B)));

        when(leader.isLeader()).thenReturn(true);
        when(cluster.leaderManager()).thenReturn(leader);
        when(cluster.topologyManager()).thenReturn(observer);
        when(cluster.network()).thenReturn(mock(ClusterNetwork.class));
        when(cluster.voterConfiguration()).thenReturn(Option.none());
        when(cluster.retirementSafeVoters()).thenReturn(Option.some(voters));
        when(cluster.verifiedVoterHistoryIds()).thenReturn(Set.of(SELF, VOTER_B));
        when(observer.inQuorum()).thenReturn(() -> true);
        when(observer.reportedActiveNodeCount()).thenReturn(2);

        var descriptor = new MemberDescriptor(Option.none(), "core", "static", "1");
        var fsm = mock(MembershipFsm.class);

        when(fsm.coreCountedMembers()).thenReturn(Set.of(SELF, VOTER_B, YOUNG, MATURE));
        when(fsm.isTrackedAndNotDead(any())).thenReturn(true);
        when(fsm.memberDescriptor(any())).thenReturn(Option.some(descriptor));

        var replacements = mock(NodeReplacementIndex.class);

        when(replacements.retirementProtected()).thenReturn(Set.of());
        when(replacements.voterSwaps()).thenReturn(Map.of());

        var registry = mock(DrainCommandRegistry.class);
        var deferred = new ArrayList<Supplier<ScheduledFuture<?>>>();
        var outputs = TopologyAssembly.topologyManager(new TopologyAssembly.TopologyManagerInputs(config,
                                                                                                  store,
                                                                                                  cluster,
                                                                                                  new AtomicReference<>(fsm),
                                                                                                  mock(org.pragmatica.aether.deployment.cluster.CapacityControlledLifecycle.class),
                                                                                                  mock(DeploymentMap.class),
                                                                                                  () -> Set.of(SELF, VOTER_B, YOUNG, MATURE),
                                                                                                  replacements,
                                                                                                  mock(ClusterSyncCollector.class),
                                                                                                  () -> true,
                                                                                                  registry,
                                                                                                  () -> Option.some(clusterConfig),
                                                                                                  _ -> org.pragmatica.lang.Promise.success(List.of()),
                                                                                                  mock(GenerationSnapshotSource.class),
                                                                                                  deferred::add));
        var reconciler = mock(LeaderReconciler.class);

        when(reconciler.pastDrainSafetyGrace(YOUNG)).thenReturn(false);
        when(reconciler.pastDrainSafetyGrace(MATURE)).thenReturn(true);
        outputs.leaderReconcilerRef().set(reconciler);
        outputs.clusterTopologyManager().activate();

        var task = deferred.getFirst().get();

        try {
            verify(registry, timeout(10_000)).requestDrain(MATURE);
            verify(registry, never()).requestDrain(YOUNG);
        } finally {
            task.cancel(false);
        }
    }
}
