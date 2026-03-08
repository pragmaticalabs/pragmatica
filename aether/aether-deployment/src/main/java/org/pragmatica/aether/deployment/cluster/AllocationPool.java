package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.config.PlacementPolicy;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;

/// Pool of allocatable nodes for slice placement.
/// Combines core consensus nodes with worker pool nodes.
///
/// @param coreNodes consensus participants with ON_DUTY lifecycle
/// @param mainWorkers always-on worker pool members
public record AllocationPool(List<NodeId> coreNodes, List<NodeId> mainWorkers) {
    public static AllocationPool allocationPool(List<NodeId> coreNodes, List<NodeId> mainWorkers) {
        return new AllocationPool(List.copyOf(coreNodes), List.copyOf(mainWorkers));
    }

    /// Core-only pool (backward compatible -- no workers).
    public static AllocationPool coreOnly(List<NodeId> coreNodes) {
        return new AllocationPool(List.copyOf(coreNodes), List.of());
    }

    /// All schedulable nodes in priority order (core first, then workers).
    public List<NodeId> allNodes() {
        var all = new ArrayList<>(coreNodes);
        all.addAll(mainWorkers);
        return List.copyOf(all);
    }

    /// Nodes matching the given placement policy.
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

    /// Whether any workers are available.
    public boolean hasWorkers() {
        return ! mainWorkers.isEmpty();
    }
}
