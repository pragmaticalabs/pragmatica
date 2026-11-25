// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node.assembly;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.cluster.NodeReplacementIndex;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.rabia.ClusterConfig;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// #1055: the voter-excluded core retirement is gated by the drain-safety grace at the ONE line that builds the gate from the
/// reconciler holder inside [TopologyAssembly#retireExcludedCores]. These tests drive that method itself (leader, voter electorate,
/// excluded-core derivation, deployments guard, drain request), so an always-true grace in place of that line drains a core
/// younger than the grace and goes red. [CoreRetirementGraceTest] pins the pieces; this pins that the production path uses them.
class CoreRetirementWiringTest {
    private static final NodeId VOTER_A = new NodeId("voter-a");
    private static final NodeId VOTER_B = new NodeId("voter-b");
    private static final NodeId YOUNG = new NodeId("young-excluded-core");
    private static final NodeId MATURE = new NodeId("mature-excluded-core");
    private static final NodeId HOSTING = new NodeId("mature-core-still-hosting-a-slice");
    private static final Artifact SLICE = Artifact.artifact("org.example:slice:1.0.0").unwrap();

    private static Promise<Unit> retire(ClusterTopologyManager topology, Supplier<LeaderReconciler> reconciler) {
        return retire(topology, reconciler, true, DeploymentMap.deploymentMap());
    }

    @SuppressWarnings("unchecked")
    private static Promise<Unit> retire(ClusterTopologyManager topology,
                                        Supplier<LeaderReconciler> reconciler,
                                        boolean leaderNode,
                                        DeploymentMap deployments) {
        return retire(topology, reconciler, leaderNode, deployments, SurplusRefusals.surplusRefusals((_, _) -> {}));
    }

    @SuppressWarnings("unchecked")
    private static Promise<Unit> retire(ClusterTopologyManager topology,
                                        Supplier<LeaderReconciler> reconciler,
                                        boolean leaderNode,
                                        DeploymentMap deployments,
                                        SurplusRefusals refusals) {
        var cluster = (RabiaNode<KVCommand<AetherKey>>) mock(RabiaNode.class);
        var leader = mock(LeaderManager.class);

        when(leader.isLeader()).thenReturn(leaderNode);
        when(cluster.leaderManager()).thenReturn(leader);
        when(cluster.retirementSafeVoters())
            .thenReturn(Option.some(new VoterConfiguration(1L, new ClusterConfig(List.of(VOTER_A, VOTER_B)))));
        when(cluster.verifiedVoterHistoryIds()).thenReturn(Set.of(VOTER_A, VOTER_B));

        var config = mock(AetherValue.ClusterConfigValue.class);

        when(config.coreCount()).thenReturn(2);

        var store = (KVStore<AetherKey, AetherValue>) mock(KVStore.class);

        doReturn(Option.some(config)).when(store).getTyped(any(), any());

        var fsm = mock(MembershipFsm.class);

        when(fsm.coreCountedMembers()).thenReturn(Set.of(VOTER_A, VOTER_B, YOUNG, MATURE, HOSTING));

        var replacements = mock(NodeReplacementIndex.class);

        when(replacements.retirementProtected()).thenReturn(Set.of());

        return TopologyAssembly.retireExcludedCores(cluster,
                                                    () -> fsm,
                                                    store,
                                                    replacements,
                                                    deployments,
                                                    topology,
                                                    reconciler,
                                                    Set.of(VOTER_A, VOTER_B),
                                                    refusals);
    }

    private static ClusterTopologyManager topology() {
        var topology = mock(ClusterTopologyManager.class);

        when(topology.scalingHeld(anyString(), any(ClusterTopologyManager.ScalingAsker.class), anyBoolean())).thenReturn(false);
        when(topology.drainNode(any(), any())).thenReturn(Promise.unitPromise());
        return topology;
    }

    private static LeaderReconciler reconcilerWhereYoungIsInsideTheGrace() {
        var reconciler = mock(LeaderReconciler.class);

        when(reconciler.pastDrainSafetyGrace(YOUNG)).thenReturn(false);
        when(reconciler.pastDrainSafetyGrace(MATURE)).thenReturn(true);
        return reconciler;
    }

    @Test
    void retireExcludedCores_youngExcludedCore_isNotDrainedThroughTheProductionPath() {
        var topology = topology();
        var reconciler = reconcilerWhereYoungIsInsideTheGrace();

        retire(topology, () -> reconciler).await();

        verify(topology, never()).drainNode(YOUNG, DrainReason.OVERPROVISION_SCALE_DOWN);
    }

    @Test
    void retireExcludedCores_matureExcludedCore_isStillDrainedAsSurplusTrim() {
        var topology = topology();
        var reconciler = reconcilerWhereYoungIsInsideTheGrace();

        retire(topology, () -> reconciler).await();

        verify(topology).drainNode(MATURE, DrainReason.OVERPROVISION_SCALE_DOWN);
    }

    /// #3358: the production path hands the excluded cores it derived to the hold. With auto-heal disabled (the hold answers held) the
    /// hold is asked for the subject `core` because there are victims, and nothing is drained. The retirement asks on every pass it evaluates, with `pending` = "there is a victim" (an empty victim list asks with false and closes its own answer).
    @Test
    void retireExcludedCores_autoHealDisabled_asksTheCoreHoldForItsVictims_andDrainsNothing() {
        var topology = mock(ClusterTopologyManager.class);
        var reconciler = reconcilerWhereEveryCoreIsPastTheGrace();

        when(topology.scalingHeld(anyString(), any(ClusterTopologyManager.ScalingAsker.class), anyBoolean())).thenReturn(true);
        when(topology.drainNode(any(), any())).thenReturn(Promise.unitPromise());

        retire(topology, () -> reconciler).await();

        verify(topology).scalingHeld(ClusterTopologyManager.coreSubject(), ClusterTopologyManager.ScalingAsker.RETIREMENT, true);
        verify(topology, never()).drainNode(any(), any());
    }

    /// #3358: every excluded core still hosting a slice leaves no victim, and that is a pass the retirement evaluated: it asks with
    /// nothing pending (its answer goes false) instead of staying silent, and drains nothing.
    @Test
    void retireExcludedCores_everyCoreStillHosting_asksTheCoreHoldWithNothingPending() {
        var topology = topology();
        var reconciler = reconcilerWhereEveryCoreIsPastTheGrace();
        var deployments = DeploymentMap.deploymentMap();

        for (var node : List.of(YOUNG, MATURE, HOSTING)) {
            hostASlice(deployments, node);
        }

        retire(topology, () -> reconciler, true, deployments).await();

        verify(topology).scalingHeld(ClusterTopologyManager.coreSubject(), ClusterTopologyManager.ScalingAsker.RETIREMENT, false);
        verify(topology, never()).drainNode(any(), any());
    }

    /// #3358: a non-leader pass is not an evaluated pass: it asks nothing (its CTM is deactivated and holds no answers).
    @Test
    void retireExcludedCores_nonLeaderPass_neverAsksTheCoreHold() {
        var topology = topology();
        var reconciler = reconcilerWhereEveryCoreIsPastTheGrace();

        retire(topology, () -> reconciler, false, DeploymentMap.deploymentMap()).await();

        verify(topology, never()).scalingHeld(anyString(), any(ClusterTopologyManager.ScalingAsker.class), anyBoolean());
    }

    @Test
    void retireExcludedCores_reconcilerNotPublished_drainsNothing() {
        var topology = topology();

        retire(topology, () -> null).await();

        verify(topology, never()).drainNode(any(), any());
    }

    private static LeaderReconciler reconcilerWhereEveryCoreIsPastTheGrace() {
        var reconciler = mock(LeaderReconciler.class);

        when(reconciler.pastDrainSafetyGrace(any())).thenReturn(true);
        return reconciler;
    }

    private static DeploymentMap deploymentsHostedOn(NodeId node) {
        var map = DeploymentMap.deploymentMap();

        hostASlice(map, node);
        return map;
    }

    private static void hostASlice(DeploymentMap map, NodeId node) {
        map.onNodeArtifactPut(new ValuePut<>(new KVCommand.Put<>(new AetherKey.NodeArtifactKey(node, SLICE),
                                                                 NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE)),
                                             Option.none()));
    }

    @Test
    void retireExcludedCores_matureCoreStillHostingASlice_isNeverRetiredAndItsEmptyPeerIs() {
        var topology = topology();

        var reconciler = reconcilerWhereEveryCoreIsPastTheGrace();

        retire(topology, () -> reconciler, true, deploymentsHostedOn(HOSTING)).await();

        verify(topology).drainNode(MATURE, DrainReason.OVERPROVISION_SCALE_DOWN);
        verify(topology, never()).drainNode(HOSTING, DrainReason.OVERPROVISION_SCALE_DOWN);
    }

    @Test
    void retireExcludedCores_nonLeader_retiresNothingEvenWhenEveryCoreIsMatureAndEmpty() {
        var topology = topology();

        var reconciler = reconcilerWhereEveryCoreIsPastTheGrace();

        retire(topology, () -> reconciler, false, DeploymentMap.deploymentMap()).await();

        verify(topology, never()).drainNode(any(), any());
    }

    /// #3626: a pass that tries nothing forgets every refusal told so far (SurplusRefusals). A node that is not the leader tries
    /// nothing, so a refusal that returns once it leads again is a new transition and is warned again; while it keeps leading, the
    /// same refusal stays silent.
    @Test
    void retireExcludedCores_nonLeaderPass_forgetsTheToldRefusal_soItIsWarnedAgainOnceLeadingAgain() {
        var warned = new CopyOnWriteArrayList<String>();
        var refusals = SurplusRefusals.surplusRefusals((node, cause) -> warned.add(node.id() + ": " + cause.message()));
        var topology = mock(ClusterTopologyManager.class);
        var reconciler = reconcilerWhereYoungIsInsideTheGrace();
        var empty = DeploymentMap.deploymentMap();

        when(topology.scalingHeld(anyString(), any(ClusterTopologyManager.ScalingAsker.class), anyBoolean())).thenReturn(false);
        when(topology.drainNode(any(), any())).thenReturn(Causes.cause("refused")
                                                                .<Unit>promise());

        retire(topology, () -> reconciler, true, empty, refusals).await();
        retire(topology, () -> reconciler, true, empty, refusals).await();
        assertThat(warned).as("two leading passes, one cause: told once").containsExactly("mature-excluded-core: refused");

        retire(topology, () -> reconciler, false, empty, refusals).await();
        retire(topology, () -> reconciler, true, empty, refusals).await();
        assertThat(warned).as("leadership lost for one pass: the refusal is told again").hasSize(2);
    }
}
