// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementApiResponses.CommunitiesResponse;
import org.pragmatica.aether.api.ManagementApiResponses.CommunityInfo;
import org.pragmatica.aether.api.routes.CommunityRouteError.CommunityNotFound;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.PathParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// #1652: community state, served from committed consensus state plus the leader's live view.
///
/// A community is described by two committed records keyed by its id: `CommunityValue` (the leader's
/// lifecycle state, target size, role, mint/dissolve instants) and `GovernorAnnouncementValue` (its
/// roster: governor, members, community term). Each row is their union; a community known to only one
/// of them shows the other half as `null` rather than a made-up default.
///
/// `liveMembers` is the one NON-committed field: the leader's instantaneous count of roster members that
/// are still directed to the community and not observed absent — the same count the per-community FSM
/// compares against the viability floor. It is `null` wherever it cannot be observed (the serving node
/// is not the leader, liveness is unwired, or there is no roster). The route is LEADER-targeted so a
/// normal request lands where it can be observed.
///
/// A worker that loses the core fences itself locally and writes nothing, so this route never shows a
/// community DISSOLVED on that account; the core shows it DEGRADED once its members stop answering.
public final class CommunityRoutes implements RouteSource {
    private final Supplier<ManageableNode> nodeSupplier;

    private CommunityRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static CommunityRoutes communityRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new CommunityRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<CommunitiesResponse> route(ManagementRoute.CLUSTER_COMMUNITIES).toJson(this::buildCommunitiesResponse),
                         ManagementRoutes.<CommunityInfo> route(ManagementRoute.CLUSTER_COMMUNITY_GET)
                                         .withPath(PathParameter.aString())
                                         .to(this::communityDetail)
                                         .asJson());
    }

    private CommunitiesResponse buildCommunitiesResponse() {
        var node = nodeSupplier.get();

        return new CommunitiesResponse(communityIds(node).stream().map(id -> communityInfo(node, id)).toList());
    }

    private Promise<CommunityInfo> communityDetail(String communityId) {
        var node = nodeSupplier.get();

        return Option.some(communityId)
                     .filter(id -> isKnown(node, id))
                     .map(id -> communityInfo(node, id))
                     .async(CommunityNotFound.FACTORY.apply(communityId));
    }

    /// Sorted, so repeated calls are diffable — KV iteration order is not specified.
    private static Set<String> communityIds(ManageableNode node) {
        var ids = new TreeSet<String>();

        node.kvStore().forEach(CommunityKey.class,
                               CommunityValue.class,
                               (key, _) -> ids.add(key.communityId()));
        node.kvStore()
            .forEach(GovernorAnnouncementKey.class,
                     GovernorAnnouncementValue.class,
                     (key, _) -> ids.add(key.communityId()));

        return ids;
    }

    private static boolean isKnown(ManageableNode node, String communityId) {
        return community(node, communityId).isPresent() || roster(node, communityId).isPresent();
    }

    private static CommunityInfo communityInfo(ManageableNode node, String communityId) {
        var community = community(node, communityId);
        var roster = roster(node, communityId);
        var members = roster.map(CommunityRoutes::memberIds).or(List.of());

        return new CommunityInfo(communityId,
                                 community.map(value -> value.state()
                                                             .name()),
                                 community.map(CommunityValue::targetSize),
                                 community.map(CommunityValue::role),
                                 community.map(CommunityValue::createdAt),
                                 community.flatMap(CommunityValue::dissolvedAt),
                                 roster.map(value -> value.governorId()
                                                          .id()),
                                 members,
                                 members.size(),
                                 roster.map(GovernorAnnouncementValue::communityTerm),
                                 node.communityLiveMembers(communityId));
    }

    private static List<String> memberIds(GovernorAnnouncementValue roster) {
        return roster.members()
                     .stream()
                     .map(NodeId::id)
                     .distinct()
                     .sorted()
                     .toList();
    }

    private static Option<CommunityValue> community(ManageableNode node, String communityId) {
        return node.kvStore()
                   .getTyped(CommunityKey.communityKey(communityId),
                             CommunityValue.class);
    }

    private static Option<GovernorAnnouncementValue> roster(ManageableNode node, String communityId) {
        return node.kvStore()
                   .getTyped(GovernorAnnouncementKey.forCommunity(communityId),
                             GovernorAnnouncementValue.class);
    }
}
