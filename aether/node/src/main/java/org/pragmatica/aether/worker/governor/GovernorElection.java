package org.pragmatica.aether.worker.governor;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMember.MemberState;

import java.util.List;

/// Pure deterministic governor election: every worker evaluates the same function
/// over the same SWIM membership list and arrives at the same result.
///
/// Election rule: lowest ALIVE NodeId wins, with sticky incumbent preference.
/// If the current governor is still ALIVE, it remains governor regardless of ordering.
public interface GovernorElection {
    /// Evaluate governor election from the current SWIM membership.
    ///
    /// @param selfId           this node's ID
    /// @param members          current SWIM membership snapshot
    /// @param currentGovernor  the current governor, if any (sticky incumbent)
    /// @return the resulting governor state for this node
    static GovernorState evaluateElection(NodeId selfId,
                                          List<SwimMember> members,
                                          Option<NodeId> currentGovernor) {
        var incumbentAlive = currentGovernor.filter(gov -> isAlive(gov, members));
        return incumbentAlive.map(gov -> stateForNode(selfId, gov)).or(() -> electLowest(selfId, members));
    }

    private static GovernorState electLowest(NodeId selfId, List<SwimMember> members) {
        return findLowestAlive(members).map(gov -> stateForNode(selfId, gov))
                              .or(() -> GovernorState.Governor.governor(selfId));
    }

    private static GovernorState stateForNode(NodeId selfId, NodeId governorId) {
        return selfId.equals(governorId)
               ? GovernorState.Governor.governor(selfId)
               : GovernorState.Follower.follower(governorId);
    }

    private static boolean isAlive(NodeId nodeId, List<SwimMember> members) {
        return members.stream().anyMatch(m -> m.nodeId().equals(nodeId) && m.state() == MemberState.ALIVE);
    }

    private static Option<NodeId> findLowestAlive(List<SwimMember> members) {
        return Option.from(members.stream().filter(m -> m.state() == MemberState.ALIVE)
                                         .map(SwimMember::nodeId)
                                         .sorted()
                                         .findFirst());
    }
}
