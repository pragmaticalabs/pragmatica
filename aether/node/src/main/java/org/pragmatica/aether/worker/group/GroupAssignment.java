package org.pragmatica.aether.worker.group;

import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.pragmatica.aether.worker.group.WorkerGroupId.workerGroupId;

/// Deterministic group computation: partitions a set of nodes into zone-aware worker groups.
/// Same inputs always produce the same outputs. The caller handles hysteresis for merge decisions.
@SuppressWarnings("JBCT-UTIL-02") // Utility interface -- static methods only
public sealed interface GroupAssignment {
    record unused() implements GroupAssignment {}

    /// Compute zone-aware worker groups from the given members.
    ///
    /// Algorithm:
    /// 1. Extract zone from each NodeId (everything before the last dash, or "local" if no dash).
    /// 2. Group members by zone.
    /// 3. If a zone group fits within maxGroupSize, create a single group.
    /// 4. Otherwise, split round-robin into ceil(size/maxGroupSize) subgroups.
    ///
    /// @param allMembers   all cluster members
    /// @param groupName    base name for the groups
    /// @param maxGroupSize maximum members per group before splitting
    /// @return deterministic mapping of group IDs to their sorted member lists
    static Map<WorkerGroupId, List<NodeId>> computeGroups(List<NodeId> allMembers,
                                                          String groupName,
                                                          int maxGroupSize) {
        var result = new TreeMap<WorkerGroupId, List<NodeId>>(Comparator.comparing(WorkerGroupId::communityId));
        var zoneGroups = groupByZone(allMembers);
        zoneGroups.forEach((zone, members) -> assignZoneGroups(result, members, groupName, zone, maxGroupSize));
        return result;
    }

    private static void assignZoneGroups(Map<WorkerGroupId, List<NodeId>> result,
                                         List<NodeId> members,
                                         String groupName,
                                         String zone,
                                         int maxGroupSize) {
        if (members.size() <= maxGroupSize) {
            result.put(workerGroupId(groupName, zone), members);
            return;
        }
        splitIntoSubgroups(result, members, groupName, zone, maxGroupSize);
    }

    private static void splitIntoSubgroups(Map<WorkerGroupId, List<NodeId>> result,
                                           List<NodeId> members,
                                           String groupName,
                                           String zone,
                                           int maxGroupSize) {
        var subgroupCount = (members.size() + maxGroupSize - 1) / maxGroupSize;
        var subgroups = new ArrayList<List<NodeId>>(subgroupCount);
        for (var i = 0; i < subgroupCount; i++) {
            subgroups.add(new ArrayList<>());
        }
        for (var i = 0; i < members.size(); i++) {
            subgroups.get(i % subgroupCount)
                     .add(members.get(i));
        }
        for (var i = 0; i < subgroupCount; i++) {
            result.put(workerGroupId(groupName + "-" + i, zone), subgroups.get(i));
        }
    }

    private static String extractZone(NodeId nodeId) {
        var id = nodeId.id();
        var lastDash = id.lastIndexOf('-');
        return lastDash < 0
               ? "local"
               : id.substring(0, lastDash);
    }

    private static Map<String, List<NodeId>> groupByZone(List<NodeId> members) {
        return members.stream()
                      .sorted()
                      .collect(Collectors.groupingBy(GroupAssignment::extractZone,
                                                     TreeMap::new,
                                                     Collectors.toList()));
    }
}
