// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager.UnconfirmedClearRefusal;
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


/// #2062 (owner ruling 28992d91b): the operator clears ONE unconfirmed-termination mark, by node id, after verifying at the provider and terminating
/// the instance by hand. The leader owns the marks ([ManagementRoute#NODE_UNCONFIRMED_CLEAR] is LEADER-bound); the clear is audited with the operator
/// the server authenticated, and refused (nothing cleared) for a node that shows life.
public final class UnconfirmedMarkRoutes implements RouteSource {
    /// `instance` is the provider instance id the operator verified and terminated, or `unknown`. It is required: the audit event names it.
    /// `force` overrides the provider check (an instance still listed, a provider that cannot answer) and is recorded in the audit event.
    public record ClearRequest(String instance, Boolean force) {}

    public record ClearResponse(String node, String instance, String operator, boolean forced) {}

    private final Supplier<ManageableNode> nodeSupplier;

    private UnconfirmedMarkRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static UnconfirmedMarkRoutes unconfirmedMarkRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new UnconfirmedMarkRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<ClearResponse> route(ManagementRoute.NODE_UNCONFIRMED_CLEAR)
                                         .withPath(aString())
                                         .withBody(ClearRequest.class)
                                         .toJson(this::clear));
    }

    /// The operator is read here, on the request's own thread, where the server bound the authenticated principal.
    Promise<ClearResponse> clear(String nodeId, ClearRequest request) {
        var operator = operator();
        var instance = Option.option(request.instance()).map(String::trim).filter(value -> !value.isEmpty());

        if (instance.isEmpty()) {
            return new ManagementServerError.InvalidRequest("instance is required: the provider instance id you terminated, or 'unknown'").promise();
        }

        return RequestParse.asRequest(NodeId.nodeId(nodeId))
                           .async()
                           .flatMap(node -> clearOn(node,
                                                    operator,
                                                    instance.unwrap(),
                                                    Boolean.TRUE.equals(request.force())))
                           .mapError(UnconfirmedMarkRoutes::asManagementError);
    }

    private Promise<ClearResponse> clearOn(NodeId node, String operator, String instance, boolean force) {
        return nodeSupplier.get()
                           .clusterTopologyManager()
                           .fold(() -> new UnconfirmedClearRefusal.NotActive().<ClearResponse> promise(),
                                 manager -> manager.clearUnconfirmed(node, operator, instance, force)
                                                   .map(_ -> new ClearResponse(node.id(),
                                                                               instance,
                                                                               operator,
                                                                               force)));
    }

    /// The authenticated management principal bound by the server for this request, or `unknown` when none is bound (a test, or security off).
    private static String operator() {
        return SecurityContextHolder.currentContext()
                                    .map(context -> context.principal()
                                                           .value())
                                    .or("unknown");
    }

    /// A refusal is a statement about the request or the cluster's state, not a server fault: 404 for no mark, 409 for the rest.
    static Cause asManagementError(Cause cause) {
        return switch (cause) {
            case UnconfirmedClearRefusal.NotMarked refusal -> new ManagementServerError.NotFound(refusal.message());
            case UnconfirmedClearRefusal refusal -> new ManagementServerError.Conflict(refusal.message());
            default -> cause;
        };
    }
}
