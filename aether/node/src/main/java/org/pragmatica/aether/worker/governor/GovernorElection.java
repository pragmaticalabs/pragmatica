// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import java.util.List;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMember.MemberState;


public interface GovernorElection {
    /// Called only after local readiness admission. SWIM contains peers, never local self.
    static GovernorState evaluateReadyNomination(NodeId self, List<SwimMember> peers, Option<NodeId> currentGovernor) {
        var incumbent = currentGovernor.filter(node -> node.equals(self) || isAlive(node, peers));
        var lowest = java.util.stream.Stream.concat(java.util.stream.Stream.of(self),
                                                    peers.stream()
                                                         .filter(member -> member.state() == MemberState.ALIVE)
                                                         .map(SwimMember::nodeId))
                                            .min(NodeId::compareTo)
                                            .orElse(self);

        return stateForNode(self, incumbent.or(lowest));
    }

    static GovernorState evaluateElection(NodeId selfId, List<SwimMember> members, Option<NodeId> currentGovernor) {
        var incumbentAlive = currentGovernor.filter(gov -> isAlive(gov, members));

        return incumbentAlive.map(gov -> stateForNode(selfId, gov))
                             .or(() -> electLowest(selfId, members));
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
        return members.stream()
                      .anyMatch(m -> m.nodeId()
                                      .equals(nodeId) && m.state() == MemberState.ALIVE);
    }

    private static Option<NodeId> findLowestAlive(List<SwimMember> members) {
        return Option.from(members.stream()
                                  .filter(m -> m.state() == MemberState.ALIVE)
                                  .map(SwimMember::nodeId)
                                  .sorted()
                                  .findFirst());
    }
}
