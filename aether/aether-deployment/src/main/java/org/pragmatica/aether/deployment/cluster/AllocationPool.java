package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.config.PlacementPolicy;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;


/// Pool of allocatable nodes for slice placement.
/// Combines core consensus nodes with worker pool nodes.
///
/// @param coreNodes consensus participants with ON_DUTY lifecycle
/// @param mainWorkers always-on worker pool members
/// @param workersByCommunity community-specific worker groups for multi-group topology
public record AllocationPool(List<NodeId> coreNodes,
                             List<NodeId> mainWorkers,
                             Map<String, List<NodeId>> workersByCommunity) {
    public AllocationPool {
        if (workersByCommunity == null) {workersByCommunity = Map.of();}
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
        return switch (policy){
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
