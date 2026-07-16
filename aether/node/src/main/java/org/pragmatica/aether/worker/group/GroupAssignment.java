// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.group;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.pragmatica.consensus.NodeId;

import static org.pragmatica.aether.worker.group.WorkerGroupId.workerGroupId;


@SuppressWarnings("JBCT-UTIL-02")
public sealed interface GroupAssignment {
    record unused() implements GroupAssignment {}

    static Map<WorkerGroupId, List<NodeId>> computeGroups(List<NodeId> allMembers, String groupName, int maxGroupSize) {
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
            subgroups.get(i % subgroupCount).add(members.get(i));
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
