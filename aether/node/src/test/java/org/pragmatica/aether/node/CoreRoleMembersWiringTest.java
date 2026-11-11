// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #2887: the FSM2 `coreRoleMembers` seam is the role=core membership, not the installed voter set (voters != cores). The assembly is not
/// constructed by any unit test, so the supplier is pinned by its presence in the assembly source ([AssemblySource]); the mapping is tested directly.
class CoreRoleMembersWiringTest {
    private static final NodeId VOTER = new NodeId("voter");
    private static final NodeId SURPLUS = new NodeId("surplus-core");
    private static final NodeId SWAPPED_OUT = new NodeId("swapped-out-core");
    private static final NodeId JOINING = new NodeId("joining-core");
    private static final NodeId WORKER = new NodeId("worker");

    private static MemberDescriptor descriptor(String role) {
        return new MemberDescriptor(Option.none(), role, "");
    }

    private static Map<NodeId, MemberDescriptor> membership() {
        return Map.of(VOTER, descriptor("core"),
                      SURPLUS, descriptor("core"),
                      SWAPPED_OUT, descriptor("Core"),
                      JOINING, descriptor("core"),
                      WORKER, descriptor("worker"));
    }

    @Test
    void assembly_feedsTheSeamFromTheRoleCoreMembership_notTheInstalledVoters() {
        var code = AssemblySource.stripped();

        assertThat(code).containsOnlyOnce("Set::of,()->deploymentMemberSources(membershipFsm),Map::of,()->coreRoleMembers(membershipFsm.memberDescriptors()),()->membershipFsm.memberDescriptors().keySet(),");
        assertThat(code).doesNotContain("Map::of,()->installedVoterIds(clusterNode),()->membershipFsm.memberDescriptors()");
    }

    @Test
    void surplusCore_neverAVoter_isACoreRoleMember() {
        assertThat(AetherNode.coreRoleMembers(membership())).contains(SURPLUS);
    }

    @Test
    void swappedOutCore_noLongerAVoter_isACoreRoleMember() {
        assertThat(AetherNode.coreRoleMembers(membership())).contains(SWAPPED_OUT);
    }

    @Test
    void notYetVoterJoiningCore_isACoreRoleMember() {
        assertThat(AetherNode.coreRoleMembers(membership())).contains(JOINING);
    }

    @Test
    void roleWorkerMember_isNotACoreRoleMember() {
        assertThat(AetherNode.coreRoleMembers(membership())).isEqualTo(Set.of(VOTER, SURPLUS, SWAPPED_OUT, JOINING));
    }
}
