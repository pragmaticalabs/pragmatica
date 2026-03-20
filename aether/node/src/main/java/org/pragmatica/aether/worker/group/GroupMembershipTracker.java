package org.pragmatica.aether.worker.group;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMember.MemberState;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/// Tracks SWIM membership and computes zone-aware groups using [GroupAssignment].
///
/// Thread-safe: membership is stored in a [CopyOnWriteArrayList] and computed groups
/// are published via volatile fields.
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

    /// Factory method creating a new tracker.
    public static GroupMembershipTracker groupMembershipTracker(NodeId self, String groupName, int maxGroupSize) {
        return new GroupMembershipTracker(self, groupName, maxGroupSize);
    }

    /// Update membership when a member joins or changes state.
    public void updateMember(SwimMember member) {
        membershipSnapshot.removeIf(m -> m.nodeId()
                                          .equals(member.nodeId()));
        if (member.state() != MemberState.FAULTY) {
            membershipSnapshot.add(member);
        }
        recomputeGroups();
    }

    /// Remove a member that left.
    public void removeMember(NodeId leftNodeId) {
        membershipSnapshot.removeIf(m -> m.nodeId()
                                          .equals(leftNodeId));
        recomputeGroups();
    }

    /// Current group for this node.
    public WorkerGroupId myGroup() {
        return myGroup;
    }

    /// Members in this node's group (alive only).
    public List<NodeId> myGroupMembers() {
        return currentGroups.getOrDefault(myGroup, List.of());
    }

    /// All computed groups.
    public Map<WorkerGroupId, List<NodeId>> allGroups() {
        return currentGroups;
    }

    /// All alive member NodeIds (regardless of group).
    public List<NodeId> allAliveMembers() {
        return membershipSnapshot.stream()
                                 .filter(GroupMembershipTracker::isAlive)
                                 .map(SwimMember::nodeId)
                                 .toList();
    }

    /// Full membership snapshot.
    public List<SwimMember> membershipSnapshot() {
        return List.copyOf(membershipSnapshot);
    }

    private void recomputeGroups() {
        var aliveIds = allAliveMembers();
        currentGroups = GroupAssignment.computeGroups(aliveIds, groupName, maxGroupSize);
        myGroup = currentGroups.entrySet()
                               .stream()
                               .filter(e -> e.getValue()
                                             .contains(self))
                               .map(Map.Entry::getKey)
                               .findFirst()
                               .orElse(WorkerGroupId.workerGroupId(groupName, "local"));
    }

    private static boolean isAlive(SwimMember member) {
        return member.state() == MemberState.ALIVE;
    }
}
