// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.config.PlacementPolicy;
import org.pragmatica.consensus.NodeId;


public record AllocationPool(List<NodeId> coreNodes,
                             List<NodeId> mainWorkers,
                             Map<String, List<NodeId>> workersByCommunity) {
    public AllocationPool {
        if (workersByCommunity == null) {
            workersByCommunity = Map.of();
        }
    }

    public static AllocationPool allocationPool(List<NodeId> coreNodes, List<NodeId> mainWorkers) {
        return new AllocationPool(List.copyOf(coreNodes), List.copyOf(mainWorkers), Map.of());
    }

    public static AllocationPool allocationPool(List<NodeId> coreNodes,
                                                List<NodeId> mainWorkers,
                                                Map<String, List<NodeId>> workersByCommunity) {
        return new AllocationPool(List.copyOf(coreNodes), List.copyOf(mainWorkers), Map.copyOf(workersByCommunity));
    }

    public static AllocationPool coreOnly(List<NodeId> coreNodes) {
        return new AllocationPool(List.copyOf(coreNodes), List.of(), Map.of());
    }

    public List<NodeId> allNodes() {
        var all = new ArrayList<>(coreNodes);

        all.addAll(mainWorkers);

        return List.copyOf(all);
    }

    public List<NodeId> nodesForPolicy(PlacementPolicy policy) {
        return switch (policy) {
            case CORE_ONLY -> coreNodes;
            case WORKERS_PREFERRED -> mainWorkers.isEmpty()
                                      ? coreNodes
                                      : mainWorkers;
            case WORKERS_ONLY -> mainWorkers;
            case ALL -> allNodes();
        };
    }

    public boolean hasWorkers() {
        return ! mainWorkers.isEmpty();
    }

    public List<NodeId> nodesForCommunity(String communityId) {
        return workersByCommunity.getOrDefault(communityId, List.of());
    }

    public boolean hasCommunities() {
        return ! workersByCommunity.isEmpty();
    }

    public Set<String> communities() {
        return workersByCommunity.keySet();
    }
}
