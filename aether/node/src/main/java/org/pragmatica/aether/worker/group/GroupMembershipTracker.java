// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.group;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMember.MemberState;

import static org.pragmatica.aether.config.WorkerConfig.DEFAULT_ZONE;


@SuppressWarnings({"JBCT-RET-01", "JBCT-STY-05"})
public final class GroupMembershipTracker {
    private final NodeId self;
    private final String groupName;
    private final int maxGroupSize;
    private final CopyOnWriteArrayList<SwimMember> membershipSnapshot = new CopyOnWriteArrayList<>();
    private volatile Map<WorkerGroupId, List<NodeId>> currentGroups = Map.of();
    private volatile WorkerGroupId myGroup = WorkerGroupId.DEFAULT;

    private GroupMembershipTracker(NodeId self, String groupName, int maxGroupSize) {
        this.self = self;
        this.groupName = groupName;
        this.maxGroupSize = maxGroupSize;
    }

    public static GroupMembershipTracker groupMembershipTracker(NodeId self, String groupName, int maxGroupSize) {
        return new GroupMembershipTracker(self, groupName, maxGroupSize);
    }

    public void updateMember(SwimMember member) {
        membershipSnapshot.removeIf(m -> m.nodeId()
                                          .equals(member.nodeId()));
        if (member.state() != MemberState.FAULTY) {
            membershipSnapshot.add(member);
        }

        recomputeGroups();
    }

    public void removeMember(NodeId leftNodeId) {
        membershipSnapshot.removeIf(m -> m.nodeId()
                                          .equals(leftNodeId));
        recomputeGroups();
    }

    public WorkerGroupId myGroup() {
        return myGroup;
    }

    public List<NodeId> myGroupMembers() {
        return currentGroups.getOrDefault(myGroup, List.of());
    }

    public Map<WorkerGroupId, List<NodeId>> allGroups() {
        return currentGroups;
    }

    public List<NodeId> allAliveMembers() {
        return membershipSnapshot.stream()
                                 .filter(GroupMembershipTracker::isAlive)
                                 .map(SwimMember::nodeId)
                                 .toList();
    }

    public List<SwimMember> membershipSnapshot() {
        return List.copyOf(membershipSnapshot);
    }

    private void recomputeGroups() {
        var aliveIds = allAliveMembers();

        currentGroups = GroupAssignment.computeGroups(aliveIds, groupName, maxGroupSize, this::zoneOf);
        myGroup = currentGroups.entrySet()
                               .stream()
                               .filter(e -> e.getValue()
                                             .contains(self))
                               .map(Map.Entry::getKey)
                               .findFirst()
                               .orElse(WorkerGroupId.workerGroupId(groupName, DEFAULT_ZONE));
    }

    /// The zone a node ADVERTISES, read from its SWIM labels — the same `zone` label the Hello handshake
    /// propagates (`AETHER_ZONE` → `NodeInfo.LABEL_ZONE` → announce → `SwimMember.labels`). #592 replaced
    /// a string-split of the NodeId here: that produced zones like `"node"` from `node-1` and grouped by
    /// naming convention rather than by topology.
    ///
    /// A node that advertises no zone falls back to [#DEFAULT_ZONE] rather than to a parsed fragment of
    /// its name — one honest bucket for "zone unknown" beats several confident-looking wrong ones. Note
    /// that until a node is actually given `AETHER_ZONE` this is the case for every node, which collapses
    /// to exactly the previous single-zone behaviour rather than to a new grouping.
    private String zoneOf(NodeId nodeId) {
        return membershipSnapshot.stream()
                                 .filter(member -> member.nodeId()
                                                         .equals(nodeId))
                                 .map(member -> member.labels()
                                                      .get(NodeInfo.LABEL_ZONE))
                                 .filter(zone -> zone != null && !zone.isBlank())
                                 .findFirst()
                                 .orElse(DEFAULT_ZONE);
    }

    private static boolean isAlive(SwimMember member) {
        return member.state() == MemberState.ALIVE;
    }
}
