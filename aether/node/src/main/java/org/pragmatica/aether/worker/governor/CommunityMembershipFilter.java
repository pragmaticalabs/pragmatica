// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import java.util.List;

import org.pragmatica.aether.worker.health.CommunityMemberDirectory;
import org.pragmatica.swim.SwimMember;


/// Narrows a worker's raw SWIM alive set down to the members of its OWN community before
/// feeding [`GovernorAnnouncer.onMembershipChange`]. The community is identified by the
/// committed [`AetherValue.ActivationDirectiveValue`] of each member, as indexed by community in the
/// [`CommunityMemberDirectory`] (maintained from committed directive changes) — NOT by SWIM `SwimMember` source-labels, which are
/// absent for gossip-learned members and would silently drop community peers.
///
/// Source-scoping (one community per source) is the single-community-per-source form;
/// communityId-scoping (this filter) is the refinement once the growth comparator splits a
/// source into multiple communities.
public sealed interface CommunityMembershipFilter {
    /// Alive SWIM members that are governor candidates of `communityId`: their committed
    /// [`AetherValue.ActivationDirectiveValue`] has role WORKER and names the community (H13). Members
    /// without such a directive are excluded: cross-community members, not-yet-activated nodes, and cores,
    /// which are excluded by role rather than by the accident of an empty community.
    ///
    /// Cost per call is O(alive members + community candidates), independent of the size of the KV
    /// (#1840): the candidate set comes from the directory's per-community index, not from a KV scan.
    static List<SwimMember> communityAliveMembers(List<SwimMember> aliveMembers,
                                                  CommunityMemberDirectory directory,
                                                  String communityId) {
        var candidates = directory.governorCandidates(communityId);

        return aliveMembers.stream()
                           .filter(member -> candidates.contains(member.nodeId()))
                           .toList();
    }

    record unused() implements CommunityMembershipFilter {}
}
