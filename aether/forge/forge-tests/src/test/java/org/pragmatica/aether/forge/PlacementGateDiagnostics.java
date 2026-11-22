// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.util.Map;
import java.util.stream.Collectors;

import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.consensus.NodeId;


/// Failure-message diagnostics for the placement-operation AWAITING_READY gate
/// (CommunityPlacementReconciler.targetReady): prints, for the operation's target node, the three clauses the gate
/// requires, as the leader sees them: (1) the leader readiness view, (2) the NodePlacement row, (3) the
/// ActivationDirective role and community against the operation's community.
final class PlacementGateDiagnostics {
    private PlacementGateDiagnostics() {}

    static String describe(AetherNode leader, String communityId) {
        return leader.kvStore()
                     .getTyped(new AetherKey.CommunityPlacementOperationKey(communityId),
                               AetherValue.CommunityPlacementOperationValue.class)
                     .fold(() -> "operation=<absent>",
                           operation -> describe(leader, operation));
    }

    private static String describe(AetherNode leader, AetherValue.CommunityPlacementOperationValue operation) {
        var target = operation.targetNode();
        var states = leader.metricsCollector().reportedStates();

        return "operation=" + operation
             + "; gate(target=" + target.id()
             + "): (1) leaderReadiness[target]=" + states.get(target)
             + " readySet=" + readySet(states)
             + "; (2) nodePlacement=" + leader.kvStore()
                                              .getTyped(new AetherKey.NodePlacementKey(target),
                                                        AetherValue.NodePlacementValue.class)
             + "; (3) directive=" + leader.kvStore()
                                          .getTyped(new AetherKey.ActivationDirectiveKey(target),
                                                    AetherValue.ActivationDirectiveValue.class)
                                          .map(value -> "role=" + value.role() + " community=" + value.communityId())
             + " vs operationCommunity=" + operation.communityId();
    }

    private static String readySet(Map<NodeId, NodeReportedState> states) {
        return states.entrySet()
                     .stream()
                     .filter(entry -> entry.getValue() == NodeReportedState.READY)
                     .map(entry -> entry.getKey()
                                        .id())
                     .sorted()
                     .collect(Collectors.joining(",", "[", "]"));
    }
}
