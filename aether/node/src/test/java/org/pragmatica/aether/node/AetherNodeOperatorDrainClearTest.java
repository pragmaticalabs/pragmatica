// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipTransitionRecord;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #2063 — an operator drain entry ends with the departure it commanded. Before, only the drains the topology manager
/// started itself were ever cleared, so a drained node that rejoined under the same config-derived id was drained again.
class AetherNodeOperatorDrainClearTest {
    private static final NodeId DRAINED = NodeId.nodeId("drained").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("other").unwrap();

    private static MembershipTransitionRecord transition(NodeId id, String from, String to) {
        return new MembershipTransitionRecord(id, from, to, "test", 1L, "core", 0L);
    }

    @Test
    void clearOperatorDrainOnDeath_clearsTheEntry_whenTheDrainedNodeReachesDead() {
        var registry = DrainCommandRegistry.drainCommandRegistry();

        registry.requestDrain(DRAINED);
        AetherNode.clearOperatorDrainOnDeath(registry, transition(DRAINED, "Departing", "Dead"));

        assertThat(registry.isDrainRequested(DRAINED)).isFalse();
        assertThat(registry.drainTargets()).isEmpty();
    }

    @Test
    void clearOperatorDrainOnDeath_keepsTheEntry_whileTheNodeIsOnlyDeparting() {
        var registry = DrainCommandRegistry.drainCommandRegistry();

        registry.requestDrain(DRAINED);
        AetherNode.clearOperatorDrainOnDeath(registry, transition(DRAINED, "Member", "Departing"));

        assertThat(registry.isDrainRequested(DRAINED)).isTrue();
    }

    @Test
    void clearOperatorDrainOnDeath_clearsOnlyTheDeadNode() {
        var registry = DrainCommandRegistry.drainCommandRegistry();

        registry.requestDrain(DRAINED);
        registry.requestDrain(OTHER);
        AetherNode.clearOperatorDrainOnDeath(registry, transition(DRAINED, "Departing", "Dead"));

        assertThat(registry.drainTargets()).containsExactly(OTHER);
    }

    /// The transition listener is assembled inside `AetherNode.assemble`, which no unit test constructs; pin that the hook is
    /// called there with the node's own registry (same stripped-source approach as the death-edge wiring pins).
    @Test
    void transitionListener_callsTheClear_withTheNodesRegistry() {
        assertThat(NodeDepartureWiringTest.assemblyCode()).contains("clearOperatorDrainOnDeath(drainCommandRegistry,record);");
    }
}
