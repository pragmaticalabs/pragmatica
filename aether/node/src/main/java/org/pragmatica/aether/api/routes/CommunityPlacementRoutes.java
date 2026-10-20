// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.CommunityPlacementReconciler;
import org.pragmatica.aether.deployment.cluster.CommunityPlacementReconciler.ParkedClear;
import org.pragmatica.aether.deployment.cluster.CommunityPlacementReconciler.ParkedClearRefusal;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.pragmatica.http.routing.PathParameter.aString;


/// #241: the operator clears ONE parked community placement operation (a drain whose acknowledgement never came, or a conflict the reconciler
/// cannot resolve), by community id, after looking at the nodes the `community-placement-parked` warning names. The leader owns the operation
/// ([ManagementRoute#COMMUNITY_PLACEMENT_CLEAR] is LEADER-bound); the clear is audited with the operator the server authenticated and the reason
/// they gave, and removes, drains and terminates nothing.
public final class CommunityPlacementRoutes implements RouteSource {
    /// `reason` is what the operator checked; required, because the audit event names it.
    public record ClearRequest(String reason) {}

    public record ClearResponse(String community,
                                String operation,
                                String phase,
                                String targetNode,
                                String previousNode,
                                String operator) {}

    private final Supplier<ManageableNode> nodeSupplier;

    private CommunityPlacementRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static CommunityPlacementRoutes communityPlacementRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new CommunityPlacementRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<ClearResponse> route(ManagementRoute.COMMUNITY_PLACEMENT_CLEAR)
                                         .withPath(aString())
                                         .withBody(ClearRequest.class)
                                         .toJson(this::clear));
    }

    /// The operator is read here, on the request's own thread, where the server bound the authenticated principal.
    Promise<ClearResponse> clear(String community, ClearRequest request) {
        var operator = operator();
        var reason = Option.option(request.reason()).map(String::trim).filter(value -> !value.isEmpty());

        return reason.isEmpty()
               ? new ManagementServerError.InvalidRequest("reason is required: say what you checked on the nodes before clearing").promise()
               : clearOn(community, operator, reason.unwrap());
    }

    private Promise<ClearResponse> clearOn(String community, String operator, String reason) {
        return nodeSupplier.get()
                           .clusterTopologyManager()
                           .flatMap(ClusterTopologyManager::installedCommunityPlacement)
                           .fold(() -> new ParkedClearRefusal.NotActive().<ParkedClear> promise(),
                                 reconciler -> reconciler.clearParked(community, operator, reason))
                           .map(cleared -> response(cleared, operator))
                           .mapError(CommunityPlacementRoutes::asManagementError);
    }

    private static ClearResponse response(ParkedClear cleared, String operator) {
        return new ClearResponse(cleared.communityId(),
                                 cleared.operationId(),
                                 cleared.phase().name(),
                                 cleared.targetNode().id(),
                                 cleared.previousNode().map(NodeId::id).or(""),
                                 operator);
    }

    /// The authenticated management principal bound by the server for this request, or `unknown` when none is bound (a test, or security off).
    private static String operator() {
        return SecurityContextHolder.currentContext()
                                    .map(context -> context.principal()
                                                           .value())
                                    .or("unknown");
    }

    /// A refusal is a statement about the request or the cluster's state, not a server fault: 404 for no operation, 409 for the rest.
    static Cause asManagementError(Cause cause) {
        return switch (cause) {
            case ParkedClearRefusal.NoOperation refusal -> new ManagementServerError.NotFound(refusal.message());
            case ParkedClearRefusal refusal -> new ManagementServerError.Conflict(refusal.message());
            default -> cause;
        };
    }
}
