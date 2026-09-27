// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.FsmObserver;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The #1550 wiring pin, at the REAL seam. `AetherNode.livePlacementMembers` is the member set the stream
/// `ReplicaSetController` places partitions over. Since #1390 the voter set it is bounded by
/// (`TopologyObserver.coreNodes()`) is health-independent, so a dead voter stays in it; placing over the
/// voters alone kept a killed owner as the HRW winner forever. These tests run in the per-PR suite, unlike
/// the Heavy `StreamOwnerFailoverTest` that the regression escaped.
class LivePlacementMembersSeamTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId PEER_B = new NodeId("node-b");
    private static final NodeId PEER_C = new NodeId("node-c");
    private static final Set<NodeId> VOTERS = Set.of(SELF, PEER_B, PEER_C);
    private static final String STREAM = "orders";
    private static final int RF = 2;

    private static final long NO_HINT_DECAY = Long.MAX_VALUE;
    private static final TimeSpan BACKSTOP = TimeSpan.timeSpan(40).millis();

    private static MembershipFsm healthyFsm() {
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                              System::currentTimeMillis,
                                              NO_HINT_DECAY,
                                              BACKSTOP);

        fsm.seed(VOTERS);
        fsm.onSwimHealthy(PEER_B, 1L);
        fsm.onSwimHealthy(PEER_C, 1L);

        return fsm;
    }

    private static NodeId ownerOver(List<NodeId> members, int partition) {
        return ReplicaPlacement.place(STREAM, partition, members, RF)
                               .map(ReplicaPlacement.Placement::owner)
                               .or(SELF);
    }

    /// A partition whose HRW owner over the full voter set is a peer — the node the test then kills.
    private static int partitionOwnedByPeer(List<NodeId> members) {
        var partition = 0;

        while (ownerOver(members, partition).equals(SELF)) {
            partition++;
        }

        return partition;
    }

    @Test
    void livePlacementMembers_allVotersHealthy_placesOverEveryVoter() {
        assertThat(AetherNode.livePlacementMembers(VOTERS, healthyFsm())).containsExactlyInAnyOrder(SELF,
                                                                                                    PEER_B,
                                                                                                    PEER_C);
    }

    /// THE #1550 pin. The departed owner stays in the voter set (arming: if it ever left the voters, the
    /// assertion below would pass for the wrong reason), and it must leave the placement set — so HRW
    /// re-resolves the partition to a survivor instead of the dead node.
    @Test
    void livePlacementMembers_ownerDeparted_excludesItAndReResolvesOwnerToSurvivor() {
        var fsm = healthyFsm();
        var before = AetherNode.livePlacementMembers(VOTERS, fsm);
        var partition = partitionOwnedByPeer(before);
        var deadOwner = ownerOver(before, partition);

        fsm.onSwimDeparted(deadOwner, 2L);

        assertThat(VOTERS).as("arming: a dead voter stays in the installed voter set")
                          .contains(deadOwner);

        var after = AetherNode.livePlacementMembers(VOTERS, fsm);

        assertThat(after).as("a departed voter must leave stream placement")
                         .doesNotContain(deadOwner)
                         .hasSize(2);
        assertThat(ownerOver(after, partition)).as("HRW must re-resolve the dead owner's partition to a survivor")
                                               .isNotEqualTo(deadOwner);
    }

    /// A SUSPECT voter still counts, so a transient SWIM flap does not reshuffle ownership.
    @Test
    void livePlacementMembers_peerSuspect_keepsIt() {
        var fsm = healthyFsm();

        fsm.onSwimSuspect(PEER_B, 2L);

        assertThat(AetherNode.livePlacementMembers(VOTERS, fsm)).contains(PEER_B);
    }

    /// Voters bound the set: a counted core that is not an installed voter never enters placement.
    @Test
    void livePlacementMembers_countedButNotInstalled_excludesIt() {
        assertThat(AetherNode.livePlacementMembers(Set.of(SELF, PEER_B), healthyFsm())).containsExactlyInAnyOrder(SELF,
                                                                                                                   PEER_B);
    }
}
