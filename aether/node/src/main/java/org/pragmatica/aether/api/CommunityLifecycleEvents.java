// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ClusterEvent.CommunityMemberJoined;
import org.pragmatica.aether.api.ClusterEvent.CommunityMemberLeft;
import org.pragmatica.aether.api.ClusterEvent.CommunityMinted;
import org.pragmatica.aether.api.ClusterEvent.CommunityStateChanged;
import org.pragmatica.aether.api.ClusterEvent.Severity;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;


/// Community lifecycle events (#1652) as a pure projection of one committed write: the value before
/// (`ValuePut.oldValue`) and after. Every node applies the same committed write, so every node derives
/// the same events; the aggregator's owner gate decides which one publishes them.
///
/// Two committed records, two projections:
///   - `CommunityValue` — its first appearance is a mint; a changed `state` is one state-change event
///     carrying the edge. A write that changes neither (a target-size re-alignment) emits nothing.
///   - `GovernorAnnouncementValue.members` — the committed ROSTER. Nodes present after and not before
///     joined; present before and not after left. The roster is assignment, not liveness.
public sealed interface CommunityLifecycleEvents {
    static List<ClusterEvent> fromCommunityPut(Supplier<HlcTimestamp> clock,
                                               String communityId,
                                               Option<CommunityValue> before,
                                               CommunityValue after) {
        return before.fold(() -> List.of(minted(clock.get(), communityId, after)),
                           previous -> stateEdge(clock, communityId, previous, after));
    }

    static List<ClusterEvent> fromRosterPut(Supplier<HlcTimestamp> clock,
                                            String communityId,
                                            Option<GovernorAnnouncementValue> before,
                                            GovernorAnnouncementValue after) {
        var previous = before.map(GovernorAnnouncementValue::members).map(Set::copyOf).or(Set.of());
        var current = Set.copyOf(after.members());

        return Stream.concat(sorted(current, previous).map(node -> joined(clock.get(),
                                                                          communityId,
                                                                          node,
                                                                          after)),
                             sorted(previous, current).map(node -> left(clock.get(),
                                                                        communityId,
                                                                        node,
                                                                        after)))
                     .toList();
    }

    private static List<ClusterEvent> stateEdge(Supplier<HlcTimestamp> clock,
                                                String communityId,
                                                CommunityValue before,
                                                CommunityValue after) {
        return before.state() == after.state()
               ? List.of()
               : List.of(stateChanged(clock.get(), communityId, before.state(), after));
    }

    private static Stream<NodeId> sorted(Set<NodeId> nodes, Set<NodeId> excluded) {
        return nodes.stream()
                    .filter(node -> !excluded.contains(node))
                    .sorted(Comparator.comparing(NodeId::id));
    }

    private static ClusterEvent minted(HlcTimestamp at, String communityId, CommunityValue value) {
        return new CommunityMinted(at,
                                   Severity.INFO,
                                   "Community '" + communityId + "' minted (target size " + value.targetSize() + ")",
                                   Map.of("communityId",
                                          communityId,
                                          "state",
                                          value.state().name(),
                                          "targetSize",
                                          String.valueOf(value.targetSize()),
                                          "role",
                                          value.role()));
    }

    private static ClusterEvent stateChanged(HlcTimestamp at,
                                             String communityId,
                                             CommunityState from,
                                             CommunityValue value) {
        return new CommunityStateChanged(at,
                                         severityOf(value.state()),
                                         "Community '" + communityId
                                        + "' " + from.name()
                                        + " -> " + value.state()
                                                        .name(),
                                         Map.of("communityId",
                                                communityId,
                                                "from",
                                                from.name(),
                                                "to",
                                                value.state().name(),
                                                "targetSize",
                                                String.valueOf(value.targetSize())));
    }

    private static Severity severityOf(CommunityState to) {
        return to == CommunityState.DEGRADED
               ? Severity.WARNING
               : Severity.INFO;
    }

    private static ClusterEvent joined(HlcTimestamp at,
                                       String communityId,
                                       NodeId node,
                                       GovernorAnnouncementValue roster) {
        return new CommunityMemberJoined(at,
                                         Severity.INFO,
                                         "Node " + node.id() + " joined community '" + communityId + "'",
                                         rosterDetails(communityId, node, roster));
    }

    private static ClusterEvent left(HlcTimestamp at,
                                     String communityId,
                                     NodeId node,
                                     GovernorAnnouncementValue roster) {
        return new CommunityMemberLeft(at,
                                       Severity.INFO,
                                       "Node " + node.id() + " left community '" + communityId + "'",
                                       rosterDetails(communityId, node, roster));
    }

    private static Map<String, String> rosterDetails(String communityId,
                                                     NodeId node,
                                                     GovernorAnnouncementValue roster) {
        return Map.of("communityId",
                      communityId,
                      "nodeId",
                      node.id(),
                      "governorId",
                      roster.governorId().id(),
                      "memberCount",
                      String.valueOf(Set.copyOf(roster.members()).size()));
    }

    record unused() implements CommunityLifecycleEvents {}
}
