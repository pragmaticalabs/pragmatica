// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.consensus.NodeId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #2937 (deployment-fsm-spec C17r): `deadMembers()` is local membership's current DEAD set, the input both deployment engines
/// synthesise removals from on becoming leader. A member re-admitted by a fenced rejoin (DEAD to OBSERVED on a higher
/// incarnation) leaves it, so no removal is synthesised for it.
class MembershipFsmDeadMembersTest {
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");

    // Red when: a DEAD member is missing from the DEAD set, or a live member is in it.
    @Test
    void deadMembers_holdsTheDeadMemberOnly() {
        var manager = MembershipFsm.membershipFsm();

        manager.onSwimHealthy(A, 1L);
        manager.onSwimHealthy(B, 1L);
        manager.onSwimDeparted(A, 1L);

        assertThat(manager.memberStates()).as("precondition").containsEntry(A, "Dead").containsEntry(B, "Member");
        assertThat(manager.deadMembers()).containsOnlyKeys(A);
    }

    // Red when: a member re-admitted after DEAD (a higher-incarnation rejoin) is still in the DEAD set, so a later Rebuild
    // would depart a live node.
    @Test
    void deadMembers_rejoinedMember_leavesTheDeadSet() {
        var manager = MembershipFsm.membershipFsm();

        manager.onSwimHealthy(A, 1L);
        manager.onSwimDeparted(A, 1L);
        assertThat(manager.deadMembers()).as("precondition").containsOnlyKeys(A);
        manager.onSwimHealthy(A, 2L);

        assertThat(manager.memberStates()).as("precondition: re-armed").doesNotContainEntry(A, "Dead");
        assertThat(manager.deadMembers()).isEmpty();
    }
}
