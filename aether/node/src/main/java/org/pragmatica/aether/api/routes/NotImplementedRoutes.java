// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.stream.Stream;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Unit;


/// #525: the single registry of Management-API routes that are DECLARED but deliberately NOT BUILT.
///
/// A declared route with no handler is worse than an absent one. It reaches the operator as a bare
/// 404 (or, under a claiming prefix handler, as a misleading parse error — that was #523), so the
/// operator debugs their own request instead of learning the capability does not exist. Every route
/// listed here answers an honest **501 Not Implemented** naming the capability and WHY it is
/// missing, so the failure is self-explaining and points at the work that would fix it.
///
/// This is a holding pen, not a destination. An entry belongs here only while the capability is
/// genuinely wanted but unbuilt; if it is not wanted, delete the enum entry, the CLI subcommand and
/// the docs together instead of parking it here. `ManagementRouteCoverageTest` treats registration
/// here as "served", so an entry silently rotting here still satisfies the coverage guard — the
/// reason text is what keeps it honest.
public final class NotImplementedRoutes implements RouteSource {
    private NotImplementedRoutes() {}

    public static NotImplementedRoutes notImplementedRoutes() {
        return new NotImplementedRoutes();
    }

    private static final NotImplemented WORKER_HEALTH = NotImplemented.notImplemented("Worker pool health",
                                                                                      "Workers publish only their community roster to consensus "
                                                                                     + "(GovernorAnnouncementValue); no per-worker health fact is replicated, so "
                                                                                     + "the leader has nothing to report. Use 'aether workers list' for the roster "
                                                                                     + "and 'aether cluster membership' for per-node SWIM state.");

    private static final NotImplemented WORKER_ENDPOINTS = NotImplemented.notImplemented("Worker endpoint listing",
                                                                                         "Only the GOVERNOR's tcpAddress is recorded in consensus, never per-worker "
                                                                                        + "endpoints, so a cluster-wide worker endpoint table cannot be assembled. "
                                                                                        + "Use 'aether routes' for the cluster HTTP route table.");

    private static final NotImplemented CLUSTER_MIGRATION = NotImplemented.notImplemented("Cluster cloud migration",
                                                                                          "No server-side migration implementation exists. The route was declared and "
                                                                                         + "granted permissions, but no handler was ever built.");

    /// Registrations are spelled out literally, one `ManagementRoutes.route(ManagementRoute.X)` per
    /// route, rather than folded into a loop or a helper taking the route as a parameter.
    /// `ManagementRouteCoverageTest` scans for exactly that literal form — passing the route through
    /// a variable hides it from the scanner and fails the coverage guard. The repetition is the
    /// price of the guard being exact, and it matches every other `RouteSource` in this package.
    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<Unit> route(ManagementRoute.WORKERS_HEALTH)
                                         .to(_ -> WORKER_HEALTH.promise())
                                         .asJson(),
                         ManagementRoutes.<Unit> route(ManagementRoute.WORKERS_ENDPOINTS)
                                         .to(_ -> WORKER_ENDPOINTS.promise())
                                         .asJson(),
                         ManagementRoutes.<Unit> route(ManagementRoute.CLUSTER_MIGRATE)
                                         .to(_ -> CLUSTER_MIGRATION.promise())
                                         .asJson(),
                         ManagementRoutes.<Unit> route(ManagementRoute.CLUSTER_MIGRATE_PLAN)
                                         .to(_ -> CLUSTER_MIGRATION.promise())
                                         .asJson());
    }

    /// Carries 501 to the wire through `ProblemResponses.writeProblem`, which reads `httpStatus()`
    /// off any `HttpStatusAware` cause. Without the mixin the funnel would default to 500 and the
    /// response would read as a server fault rather than an unbuilt capability.
    record NotImplemented(String capability, String reason) implements HttpStatusAware {
        static NotImplemented notImplemented(String capability, String reason) {
            return new NotImplemented(capability, reason);
        }

        @Override
        public String message() {
            return capability + " is not implemented. " + reason;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.NOT_IMPLEMENTED;
        }
    }
}
