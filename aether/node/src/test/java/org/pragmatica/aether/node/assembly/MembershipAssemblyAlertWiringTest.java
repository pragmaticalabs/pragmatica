// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.api.ManagementServer;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.ntt.PresenceSampler;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossDetector;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.ClusterSyncScheduler;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.CoreAdmission;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2061 wiring pin for the node-health alert clear in `MembershipAssembly.membershipJournal`: the alert the membership
/// FSM's DEAD edge raised is resolved when THAT FSM re-admits the node (the edge into `Member`). The unit tests call
/// `resolveNodeHealthAlertOnRejoin` directly, so a deleted call in the assembled `onTransition` listener left every one
/// of them green. This drives a REAL [`MembershipFsm`] through the REAL `membershipJournal` assembly (the rest of the
/// node mocked) and reads the REAL [`AlertManager`]. It is also the end-to-end cover for the Member-edge clear: the Ember
/// replay (`EmberPlannedDepartureAlertsTest`) injects the transport observation and cannot reach it.
class MembershipAssemblyAlertWiringTest {
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId VICTIM = new NodeId("victim");

    @Test
    void deadThenMember_throughTheAssembledListener_clearsTheNodeHealthAlert() {
        var fsm = MembershipFsm.membershipFsm();
        var alerts = newAlertManager();

        MembershipAssembly.membershipJournal(inputs(fsm, alerts));
        assertThat(fsm.memberStates()).as("control: the boot seed made the victim a member").containsEntry(VICTIM, "Member");

        fsm.onSwimDeparted(VICTIM, 2L);
        alerts.onNodeFailed(VICTIM, SELF);
        assertThat(fsm.memberStates()).as("control: the FSM holds the victim dead").containsEntry(VICTIM, "Dead");
        assertThat(alerts.getActiveNodeHealthAlerts()).as("control: the DEAD verdict raised the alert, and the Dead edge did not clear it").hasSize(1);

        fsm.onSwimHealthy(VICTIM, 9L);

        assertThat(fsm.memberStates()).as("the FSM re-admitted the victim").containsEntry(VICTIM, "Member");
        assertThat(alerts.getActiveNodeHealthAlerts()).as("the edge into Member resolved the alert, through the assembled listener").isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static AlertManager newAlertManager() {
        return AlertManager.readOnly((KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class));
    }

    @SuppressWarnings("unchecked")
    private static MembershipAssembly.MembershipJournalInputs inputs(MembershipFsm fsm, AlertManager alerts) {
        var config = Mockito.mock(AetherNodeConfig.class);
        var coreNodes = List.of(NodeInfo.nodeInfo(SELF, new NodeAddress("127.0.0.1", 9001)),
                                NodeInfo.nodeInfo(VICTIM, new NodeAddress("127.0.0.1", 9002)));

        when(config.self()).thenReturn(SELF);
        when(config.workerConfig()).thenReturn(Option.none());
        when(config.topology()).thenReturn(new TopologyConfig(SELF, 5, timeSpan(1).seconds(), timeSpan(10).seconds(), coreNodes));
        var clusterNode = Mockito.mock(RabiaNode.class);
        var topologyObserver = Mockito.mock(TopologyObserver.class);

        when(clusterNode.topologyManager()).thenReturn(topologyObserver);
        when(clusterNode.network()).thenReturn(Mockito.mock(ClusterNetwork.class));
        when(topologyObserver.inQuorum()).thenReturn(() -> true);
        return new MembershipAssembly.MembershipJournalInputs(config,
                                                              Mockito.mock(MessageRouter.DelegateRouter.class),
                                                              (RabiaNode<KVCommand<AetherKey>>) clusterNode,
                                                              Mockito.mock(HlcClock.class),
                                                              Mockito.mock(GenerationSnapshotSource.class),
                                                              Mockito.mock(ClusterSyncCollector.class),
                                                              DrainCommandRegistry.drainCommandRegistry(),
                                                              Mockito.mock(ClusterSyncScheduler.class),
                                                              Mockito.mock(CoreAdmission.class),
                                                              alerts,
                                                              new AtomicReference<ReplicaSetController>(),
                                                              new AtomicReference<Option<ManagementServer>>(Option.none()),
                                                              new AtomicReference<QuorumLossDetector>(),
                                                              new ArrayList<>(),
                                                              Mockito.mock(PresenceSampler.class),
                                                              fsm);
    }
}
