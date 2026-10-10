// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.deployment.cluster.UpgradeRunService;
import org.pragmatica.aether.deployment.cluster.UpgradeRunService.Refusal;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunValue;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;


/// #1543 F — the operator surface of the rolling-upgrade run: look at it, pause it, resume it, abort it. The run is STARTED by
/// `POST /api/v1/cluster/upgrade` (which stores the version and then begins replacing nodes); a pause or an abort is a request that
/// takes effect when the replacement in flight reaches a terminal state, never mid-phase.
public final class UpgradeRunRoutes implements RouteSource {
    /// `state` is RUNNING, PAUSED, COMPLETED or ABORTED; `stop` is the pending request (NONE, PAUSE or ABORT); `index` of `total` nodes
    /// are done; `inFlight` is the node being replaced now (empty = none).
    public record UpgradeRunResponse(boolean present,
                                     String targetVersion,
                                     String state,
                                     String stop,
                                     int index,
                                     int total,
                                     String inFlight,
                                     List<String> order,
                                     String reason,
                                     long startedAtMs,
                                     long updatedAtMs,
                                     long epoch) {
        static UpgradeRunResponse none() {
            return new UpgradeRunResponse(false, "", "NONE", "NONE", 0, 0, "", List.of(), "", 0L, 0L, 0L);
        }

        static UpgradeRunResponse of(UpgradeRunValue run) {
            return new UpgradeRunResponse(true,
                                          run.targetVersion(),
                                          run.state().name(),
                                          run.stop().name(),
                                          run.index(),
                                          run.order().size(),
                                          run.inFlight(),
                                          run.order().stream().map(node -> node.id()).toList(),
                                          run.reason(),
                                          run.startedAtMs(),
                                          run.updatedAtMs(),
                                          run.epoch());
        }
    }

    private final Supplier<ManageableNode> nodeSupplier;

    private UpgradeRunRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static UpgradeRunRoutes upgradeRunRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new UpgradeRunRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<UpgradeRunResponse> route(ManagementRoute.UPGRADE_STATUS).toJson(this::status),
                         ManagementRoutes.<UpgradeRunResponse> route(ManagementRoute.UPGRADE_PAUSE).toJson(_ -> pause()),
                         ManagementRoutes.<UpgradeRunResponse> route(ManagementRoute.UPGRADE_RESUME).toJson(_ -> resume()),
                         ManagementRoutes.<UpgradeRunResponse> route(ManagementRoute.UPGRADE_ABORT).toJson(_ -> abort()));
    }

    UpgradeRunResponse status() {
        return service().status()
                      .fold(UpgradeRunResponse::none, UpgradeRunResponse::of);
    }

    Promise<UpgradeRunResponse> pause() {
        return service().pause()
                      .map(UpgradeRunResponse::of)
                      .mapError(UpgradeRunRoutes::asManagementError);
    }

    Promise<UpgradeRunResponse> resume() {
        return service().resume()
                      .map(UpgradeRunResponse::of)
                      .mapError(UpgradeRunRoutes::asManagementError);
    }

    Promise<UpgradeRunResponse> abort() {
        return service().abort()
                      .map(UpgradeRunResponse::of)
                      .mapError(UpgradeRunRoutes::asManagementError);
    }

    private UpgradeRunService service() {
        return nodeSupplier.get()
                           .upgradeRunService();
    }

    /// A refusal is a statement about the request or the run's state, not a server fault: 404 when there is no run, 409 otherwise.
    static Cause asManagementError(Cause cause) {
        return switch (cause) {
            case Refusal.NoRun refusal -> new ManagementServerError.NotFound(refusal.message());
            case Refusal refusal -> new ManagementServerError.Conflict(refusal.message());
            default -> cause;
        };
    }
}
