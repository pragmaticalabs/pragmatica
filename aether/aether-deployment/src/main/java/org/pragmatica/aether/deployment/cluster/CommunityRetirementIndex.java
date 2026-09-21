// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityPlacementOperationValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Unit;


/// A bounded per-community index, avoiding a full KV snapshot for every allocation predicate.
public final class CommunityRetirementIndex {
    private volatile Map<String, NodeId> retiring = Map.of();

    private CommunityRetirementIndex() {}

    public static CommunityRetirementIndex communityRetirementIndex() {
        return new CommunityRetirementIndex();
    }

    public synchronized Unit put(CommunityPlacementOperationValue operation) {
        var updated = new HashMap<>(retiring);

        update(updated, operation);
        retiring = Map.copyOf(updated);

        return Unit.unit();
    }

    private static Unit update(Map<String, NodeId> target, CommunityPlacementOperationValue operation) {
        var prepared = switch (operation.phase()) {
            case AWAITING_READY, READINESS_DELAYED, DRAIN_REQUESTED, DRAINED, TERMINATING, DRAIN_UNCERTAIN -> true;
            case RESERVED, CREATE_REQUESTED, CREATE_UNCERTAIN, COMPLETE, BLOCKED, UNKNOWN -> false;
        };

        if (prepared && operation.previousNode().isPresent()) {
            operation.previousNode().onPresent(node -> target.put(operation.communityId(), node));
        } else {
            target.remove(operation.communityId());
        }

        return Unit.unit();
    }

    public synchronized Unit restore(java.util.Collection<CommunityPlacementOperationValue> operations) {
        var updated = new HashMap<String, NodeId>();

        operations.forEach(operation -> update(updated, operation));
        retiring = Map.copyOf(updated);

        return Unit.unit();
    }

    public synchronized Unit remove(String community) {
        var updated = new HashMap<>(retiring);

        updated.remove(community);
        retiring = Map.copyOf(updated);

        return Unit.unit();
    }

    public Set<NodeId> including(Set<NodeId> reported) {
        var result = new HashSet<>(reported);

        result.addAll(retiring.values());

        return Set.copyOf(result);
    }
}
