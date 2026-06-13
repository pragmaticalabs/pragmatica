// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Wave 2 / W1 (cluster-topology-overhaul spec): the quorum membership view is fed the
/// CORE-SCOPED projection at the aether-side supplier seam (`AetherNode` wires
/// `MembershipFsm::coreCountedMembers` into `PresenceGenerationSnapshotSource` →
/// [`PresenceMembershipView`] → `TopologyObserver.haveQuorum`). integrations/consensus cannot
/// name aether roles, so the view stays a role-agnostic consumer of an already-scoped set —
/// these tests prove the seam end-to-end: 1 core + 2 workers yields `healthyOnDutyCount() == 1`,
/// which is BELOW the quorum of 2 a 3-core config requires (Rabia has no voter majority).
class PresenceMembershipViewCoreScopeTest {
    private static final NodeId CORE_A = new NodeId("node-core-a");
    private static final NodeId WORKER_B = new NodeId("node-worker-b");
    private static final NodeId WORKER_C = new NodeId("node-worker-c");

    private static final int CONFIGURED_CORE_COUNT = 3;
    private static final int QUORUM_OF_THREE = CONFIGURED_CORE_COUNT / 2 + 1;

    private static MembershipFsm fsmWithOneCoreTwoWorkers() {
        var fsm = MembershipFsm.membershipFsm();

        fsm.onSwimHealthy(CORE_A, 1L);
        fsm.onMemberDescriptor(labeledInfo(CORE_A, "core"));
        fsm.onSwimHealthy(WORKER_B, 1L);
        fsm.onMemberDescriptor(labeledInfo(WORKER_B, "worker"));
        fsm.onSwimHealthy(WORKER_C, 1L);
        fsm.onMemberDescriptor(labeledInfo(WORKER_C, "worker"));

        return fsm;
    }

    private static NodeInfo labeledInfo(NodeId id, String role) {
        return NodeInfo.nodeInfo(id,
                                 NodeAddress.nodeAddress("host-x", 6000).unwrap(),
                                 Map.of(NodeInfo.LABEL_ROLE, role));
    }

    @Test
    void healthyOnDutyCount_fedCoreScopedSupplier_excludesWorkersFromQuorumNumerator() {
        var fsm = fsmWithOneCoreTwoWorkers();
        var view = PresenceMembershipView.from(fsm::coreCountedMembers, () -> CONFIGURED_CORE_COUNT, Set::of);

        assertThat(fsm.countedMembers()).as("role-blind count would falsely satisfy quorum").hasSize(3);
        assertThat(view.healthyOnDutyCount()).isEqualTo(1);
        assertThat(view.healthyOnDutyCount()).as("1 core + 2 workers is NOT a quorum of a 3-core config").isLessThan(QUORUM_OF_THREE);
    }

    @Test
    void coreMemberIds_fedCoreScopedSupplier_neverContainWorkers() {
        var fsm = fsmWithOneCoreTwoWorkers();
        var view = PresenceMembershipView.from(fsm::coreCountedMembers, () -> CONFIGURED_CORE_COUNT, Set::of);

        assertThat(view.coreMemberIds()).containsExactly(CORE_A);
        assertThat(view.onDutyMemberIds()).containsExactly(CORE_A);
    }
}
