// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementApiResponses.WorkerInfo;
import org.pragmatica.aether.api.ManagementApiResponses.WorkersResponse;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;


/// #525: the worker roster, served from committed consensus state.
///
/// Worker mode is live — `AetherNode.activateWorkerMode` switches a node to observation-only
/// forwarding, and its `GovernorAnnouncer` writes the community roster into the replicated
/// KV-Store under [GovernorAnnouncementKey]. That announcement is the ONLY authoritative,
/// cluster-visible statement of which nodes are workers, which is why this route reads it rather
/// than any per-node in-memory tracker: a LEADER-targeted route must answer from replicated state.
///
/// The projection is per-WORKER (one row per member), whereas `/api/cluster/governors` projects the
/// same announcements per-COMMUNITY (one row per governor). Both views are wanted: an operator
/// asking "which workers do I have" should not have to flatten communities by hand.
///
/// Dissolved communities are excluded — `dissolved` marks a community whose governor has stood the
/// community down, so its members are no longer serving as its workers. Listing them would report
/// workers for a community that is gone.
public final class WorkerRoutes implements RouteSource {
    private final Supplier<ManageableNode> nodeSupplier;

    private WorkerRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static WorkerRoutes workerRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new WorkerRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<WorkersResponse> route(ManagementRoute.WORKERS_LIST).toJson(this::buildWorkersResponse));
    }

    private WorkersResponse buildWorkersResponse() {
        var workers = new ArrayList<WorkerInfo>();

        nodeSupplier.get()
                    .kvStore()
                    .forEach(GovernorAnnouncementKey.class,
                             GovernorAnnouncementValue.class,
                             (key, value) -> workers.addAll(communityWorkers(key, value)));

        return new WorkersResponse(sortedByCommunityThenNode(workers));
    }

    /// Stable ordering so repeated calls are diffable — the KV iteration order is not specified.
    private static List<WorkerInfo> sortedByCommunityThenNode(List<WorkerInfo> workers) {
        return workers.stream()
                      .sorted(Comparator.comparing(WorkerInfo::community).thenComparing(WorkerInfo::nodeId))
                      .toList();
    }

    private static List<WorkerInfo> communityWorkers(GovernorAnnouncementKey key, GovernorAnnouncementValue value) {
        return value.dissolved()
               ? List.of()
               : projectMembers(key, value);
    }

    private static List<WorkerInfo> projectMembers(GovernorAnnouncementKey key, GovernorAnnouncementValue value) {
        return value.members()
                    .stream()
                    .map(member -> toWorkerInfo(key, value, member))
                    .toList();
    }

    private static WorkerInfo toWorkerInfo(GovernorAnnouncementKey key,
                                           GovernorAnnouncementValue value,
                                           NodeId member) {
        return new WorkerInfo(member.id(),
                              key.communityId(),
                              value.governorId().id(),
                              member.equals(value.governorId()),
                              value.communityTerm(),
                              value.announcedAt());
    }
}
