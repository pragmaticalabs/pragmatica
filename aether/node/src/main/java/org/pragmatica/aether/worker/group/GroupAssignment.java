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
import java.util.function.Function;
import java.util.stream.Collectors;

import org.pragmatica.consensus.NodeId;

import static org.pragmatica.aether.worker.group.WorkerGroupId.workerGroupId;


@SuppressWarnings("JBCT-UTIL-02")
public sealed interface GroupAssignment {
    record unused() implements GroupAssignment {}

    /// Group members by zone, where the zone of a node is RESOLVED — read from the node's own advertised
    /// labels — rather than inferred from its name.
    ///
    /// #592: this used to derive the zone by string-splitting the `NodeId` at its last dash, so `node-1`
    /// grouped into a zone called `"node"` and a CTM-minted `…-r<clock36>` worker into everything before
    /// the suffix. That is not zone awareness, it is identifier parsing: it happened to look right only
    /// because uniform naming put every node in one zone, which hid the defect behind the
    /// single-community case. The operator-facing `[worker] zone` knob and the `zone` label propagated
    /// over the Hello handshake for exactly this purpose were both unread on this path.
    ///
    /// `zoneOf` is the seam so this stays pure and directly testable; `GroupMembershipTracker` binds it to
    /// the SWIM membership labels, which is where the advertised zone actually arrives
    /// (`AETHER_ZONE` → `NodeInfo.LABEL_ZONE` in `Main` → announce → `SwimMember.labels`).
    static Map<WorkerGroupId, List<NodeId>> computeGroups(List<NodeId> allMembers,
                                                          String groupName,
                                                          int maxGroupSize,
                                                          Function<NodeId, String> zoneOf) {
        var result = new TreeMap<WorkerGroupId, List<NodeId>>(Comparator.comparing(WorkerGroupId::communityId));
        var zoneGroups = groupByZone(allMembers, zoneOf);

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

    private static Map<String, List<NodeId>> groupByZone(List<NodeId> members, Function<NodeId, String> zoneOf) {
        return members.stream()
                      .sorted()
                      .collect(Collectors.groupingBy(zoneOf,
                                                     TreeMap::new,
                                                     Collectors.toList()));
    }
}
