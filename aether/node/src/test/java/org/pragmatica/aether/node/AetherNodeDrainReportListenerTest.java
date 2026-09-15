// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/// #688: the DRAINING pong is the only production signal that a node is draining, and before this
/// pin it reached only the membership FSM (#1054's acknowledgement). The leader-side eviction loop
/// (`ClusterDeploymentState.startDrainEviction`) had no production entry — its `NodeDraining`
/// decision arm is never emitted. `AetherNode.drainReportListener` is what the pong fan is wired
/// with; this pins that it forwards the report to the CDM (the new half) as well as the FSM.
class AetherNodeDrainReportListenerTest {
    @Test
    void drainReport_reachesTheClusterDeploymentManager_andTheMembershipFsm() {
        var draining = NodeId.nodeId("drainee").unwrap();
        var reportedToCdm = new ArrayList<NodeId>();
        var cdm = Mockito.mock(ClusterDeploymentManager.class);
        var fsm = MembershipFsm.membershipFsm();

        doAnswer(invocation -> reportedToCdm.add(invocation.getArgument(0))).when(cdm).onNodeDraining(any());

        AetherNode.drainReportListener(fsm, cdm).accept(draining);

        assertThat(reportedToCdm).as("#688: the report must start the leader-side eviction through the CDM")
                                 .containsExactly(draining);
        assertThat(fsm.memberDescriptor(draining).isPresent()).as("control: the FSM half is unchanged — a pong for "
                                                                  + "an id it has never observed creates no tracking")
                                                              .isFalse();
    }
}
