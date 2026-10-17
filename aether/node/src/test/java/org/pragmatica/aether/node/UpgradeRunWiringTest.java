// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.UpgradeRunPlanner.Member;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 part F — the order a run replaces nodes in: cores first with the LEADER last (its replacement hands leadership over once, at the
/// end, instead of once per node), then workers; a node already on the target is left out. Names sort within a group, so the order does not
/// depend on which node computes it.
class UpgradeRunWiringTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId C = new NodeId("c");
    private static final NodeId W1 = new NodeId("w1");
    private static final NodeId W2 = new NodeId("w2");

    private static final Map<NodeId, Member> MEMBERS = Map.of(A, new Member("core", "1.0.0"),
                                                              B, new Member("core", "1.0.0"),
                                                              C, new Member("core", "1.0.0"),
                                                              W1, new Member("worker", "1.0.0"),
                                                              W2, new Member("worker", "2.0.0"));

    @Test
    void coresFirst_leaderLast_thenWorkers_andNodesOnTheTargetAreLeftOut() {
        assertThat(UpgradeRunWiring.orderOf(MEMBERS, "2.0.0", A)).containsExactly(B, C, A, W1);
    }

    @Test
    void whenTheLeaderSortsLast_itStaysLast_andWhenItIsAWorkerNothingMoves() {
        assertThat(UpgradeRunWiring.orderOf(MEMBERS, "2.0.0", C)).containsExactly(A, B, C, W1);
        assertThat(UpgradeRunWiring.orderOf(MEMBERS, "2.0.0", W1)).containsExactly(A, B, C, W1);
    }

    @Test
    void aSpotNodeIsNotReplaceable_andANodeWithNoVersionLabelCountsAsNotOnTheTarget() {
        var members = Map.of(A, new Member("core", ""), B, new Member("spot", "1.0.0"));

        assertThat(UpgradeRunWiring.orderOf(members, "2.0.0", A)).containsExactly(A);
    }

    @Test
    void everyNodeAlreadyOnTheTarget_leavesNothingToReplace() {
        assertThat(UpgradeRunWiring.orderOf(Map.of(A, new Member("core", "2.0.0")), "2.0.0", A)).isEmpty();
    }
}
