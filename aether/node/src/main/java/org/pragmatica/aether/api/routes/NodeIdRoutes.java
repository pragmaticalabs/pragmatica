// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementApiResponses.ReserveNodeIdsRequest;
import org.pragmatica.aether.api.ManagementApiResponses.ReserveNodeIdsResponse;
import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.api.routes.NodeIdReservation.Reservation;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeIdHighWaterKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeIdentityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeIdHighWaterValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.Verify.Is;


/// #1715: `POST /cluster/node-ids/reserve`, served by the core leader. Reserves `count` node ids `<source>-<role>-<n>` by advancing the
/// committed per-(source, role) high-water ([NodeIdHighWaterKey]) in ONE compare-and-set leader transaction, and answers only after the
/// advance is committed. `cluster apply` calls it BEFORE it creates any VM, so a CLI that dies between reserve and provision leaves a gap in
/// the ordinals and never a reuse. A reservation that loses the compare-and-set to a concurrent one re-reads and tries again.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-PAT-01"})
public final class NodeIdRoutes implements RouteSource {
    static final int MAX_COUNT = 1000;
    private static final int COMMIT_ATTEMPTS = 3;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final Supplier<ManageableNode> nodeSupplier;

    private NodeIdRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static NodeIdRoutes nodeIdRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new NodeIdRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<ReserveNodeIdsResponse> route(ManagementRoute.CLUSTER_NODE_IDS_RESERVE)
                                         .withBody(ReserveNodeIdsRequest.class)
                                         .toJson(this::handleReserve));
    }

    Promise<ReserveNodeIdsResponse> handleReserve(ReserveNodeIdsRequest request) {
        return validated(request).async()
                        .flatMap(valid -> commit(valid, COMMIT_ATTEMPTS));
    }

    private static Result<ReserveNodeIdsRequest> validated(ReserveNodeIdsRequest request) {
        return Verify.ensure(Option.option(request.source()).or(""),
                             Is::matches,
                             NAME,
                             new ManagementServerError.InvalidRequest("source must be a non-empty name of letters, digits, '.', '_' and '-'"))
                     .flatMap(_ -> Verify.ensure(Option.option(request.role()).or(""),
                                                 Is::matches,
                                                 NAME,
                                                 new ManagementServerError.InvalidRequest("role must be a non-empty name of letters, digits, '.', '_' and '-'")))
                     .flatMap(_ -> Verify.ensure(request.count(),
                                                 Is::between,
                                                 1,
                                                 MAX_COUNT,
                                                 new ManagementServerError.InvalidRequest("count must be between 1 and " + MAX_COUNT)))
                     .map(_ -> request);
    }

    private Promise<ReserveNodeIdsResponse> commit(ReserveNodeIdsRequest request, int attemptsLeft) {
        var node = nodeSupplier.get();

        return committedLeaderOf(node).async()
                                .flatMap(leader -> attempt(node, leader, request, attemptsLeft));
    }

    /// The reservation is refused unless THIS node is the committed core leader: no committed leader is an election in progress (503), a
    /// committed leader that is another node is a state to retry against the leader (409), as the config routes answer.
    private static Result<LeaderValue> committedLeaderOf(ManageableNode node) {
        return node.kvStore()
                   .getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                   .fold(() -> new ManagementServerError.ServiceUnavailable("No core leader is committed yet; an election is in progress. Retry shortly.").<LeaderValue> result(),
                         leader -> node.isLeader() && leader.leader()
                                                            .equals(node.self())
                                   ? Result.success(leader)
                                   : new ManagementServerError.Conflict("Current core leader required to reserve node ids").<LeaderValue> result());
    }

    private Promise<ReserveNodeIdsResponse> attempt(ManageableNode node,
                                                    LeaderValue leader,
                                                    ReserveNodeIdsRequest request,
                                                    int attemptsLeft) {
        var key = NodeIdHighWaterKey.nodeIdHighWaterKey(request.source(), request.role());
        var stored = node.kvStore().getTyped(key, NodeIdHighWaterValue.class);
        var reservation = NodeIdReservation.reserve(stored.map(NodeIdHighWaterValue::highWater)
                                                          .or(() -> seed(node, request)),
                                                    request.source(),
                                                    request.role(),
                                                    request.count());
        var transactionId = UUID.randomUUID().toString();
        var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                      stored.map(value -> (AetherValue) value),
                                                                      Option.<AetherValue> some(reservation.next()));
        var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                              transactionId,
                                                                              leader,
                                                                              List.of(),
                                                                              List.of(mutation));

        return node.<Object> apply(List.of(command))
                   .flatMap(results -> accepted(results, transactionId)
                                       ? Promise.success(response(request, reservation))
                                       : retryOrRefuse(request, attemptsLeft));
    }

    private Promise<ReserveNodeIdsResponse> retryOrRefuse(ReserveNodeIdsRequest request, int attemptsLeft) {
        return attemptsLeft > 1
               ? commit(request, attemptsLeft - 1)
               : new ManagementServerError.Conflict("Node id reservation lost the compare-and-set " + COMMIT_ATTEMPTS
                                                   + " times to concurrent reservations for " + request.source()
                                                   + "/" + request.role()
                                                   + "; nothing was reserved. Retry.").<ReserveNodeIdsResponse> promise();
    }

    private static boolean accepted(List<Object> results, String transactionId) {
        return results.stream()
                      .filter(KVCommand.TransactionResult.class::isInstance)
                      .map(KVCommand.TransactionResult.class::cast)
                      .anyMatch(result -> result.transactionId()
                                                .equals(transactionId) && result.accepted());
    }

    private static ReserveNodeIdsResponse response(ReserveNodeIdsRequest request, Reservation reservation) {
        return new ReserveNodeIdsResponse(request.source(),
                                          request.role(),
                                          reservation.ids(),
                                          reservation.next().highWater());
    }

    /// First reservation of a (source, role): what bootstrap and earlier applies already took. The persisted config's desired count covers
    /// bootstrap's `<source>-<role>-<i>`; the identity records (never deleted) cover every id ever admitted, scaled-down ones included.
    private static int seed(ManageableNode node, ReserveNodeIdsRequest request) {
        var configured = node.kvStore()
                             .getTyped(ClusterConfigKey.CURRENT, ClusterConfigValue.class)
                             .map(config -> config.desiredCountFor(request.source(),
                                                                   request.role()))
                             .or(0);
        var known = new ArrayList<String>();

        node.kvStore()
            .forEach(NodeIdentityKey.class,
                     NodeLifecycleValue.class,
                     (identity, _) -> known.add(identity.nodeId().id()));

        return NodeIdReservation.seedHighWater(configured, request.source(), request.role(), known.stream());
    }
}
