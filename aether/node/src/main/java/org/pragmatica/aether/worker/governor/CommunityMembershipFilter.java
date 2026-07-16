// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.swim.SwimMember;

import static java.util.stream.Collectors.toSet;


/// Narrows a worker's raw SWIM alive set down to the members of its OWN community before
/// feeding [`GovernorAnnouncer.onMembershipChange`]. The community is identified by the
/// committed [`AetherValue.ActivationDirectiveValue.communityId`] of each member, read from the
/// consensus-backed [`KVStore`] snapshot — NOT by SWIM `SwimMember` source-labels, which are
/// absent for gossip-learned members and would silently drop community peers.
///
/// Source-scoping (one community per source) is the single-community-per-source form;
/// communityId-scoping (this filter) is the refinement once the growth comparator splits a
/// source into multiple communities.
public sealed interface CommunityMembershipFilter {
    /// Alive SWIM members whose committed [`AetherValue.ActivationDirectiveValue`] names
    /// `communityId`. Members without a committed worker directive for this community are
    /// excluded (cross-community members, not-yet-activated nodes).
    static List<SwimMember> communityAliveMembers(List<SwimMember> aliveMembers,
                                                  KVStore<AetherKey, AetherValue> kvStore,
                                                  String communityId) {
        var communityNodeIds = committedCommunityNodeIds(kvStore, communityId);

        return aliveMembers.stream()
                           .filter(member -> communityNodeIds.contains(member.nodeId()))
                           .toList();
    }

    static Set<NodeId> committedCommunityNodeIds(KVStore<AetherKey, AetherValue> kvStore, String communityId) {
        return committedCommunityNodeIds(kvStore.snapshot(), communityId);
    }

    private static Set<NodeId> committedCommunityNodeIds(Map<AetherKey, AetherValue> snapshot, String communityId) {
        return snapshot.entrySet()
                       .stream()
                       .flatMap(entry -> communityNodeId(entry, communityId))
                       .collect(toSet());
    }

    private static Stream<NodeId> communityNodeId(Map.Entry<AetherKey, AetherValue> entry, String communityId) {
        return entry.getKey() instanceof AetherKey.ActivationDirectiveKey directiveKey && entry.getValue() instanceof AetherValue.ActivationDirectiveValue directive && communityId.equals(directive.communityId())
               ? Stream.of(directiveKey.nodeId())
               : Stream.empty();
    }

    record unused() implements CommunityMembershipFilter {}
}
