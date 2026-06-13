// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.group;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMember.MemberState;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;


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

        currentGroups = GroupAssignment.computeGroups(aliveIds, groupName, maxGroupSize);
        myGroup = currentGroups.entrySet().stream().filter(e -> e.getValue()
                                                                 .contains(self)).map(Map.Entry::getKey).findFirst().orElse(WorkerGroupId.workerGroupId(groupName,
                                                                                                                                                        "local"));
    }

    private static boolean isAlive(SwimMember member) {
        return member.state() == MemberState.ALIVE;
    }
}
