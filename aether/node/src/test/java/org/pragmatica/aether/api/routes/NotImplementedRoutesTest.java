// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api.routes;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.routes.NotImplementedRoutes.NotImplemented;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.http.routing.Route;
import org.pragmatica.lang.Cause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #525: routes that are declared but deliberately unbuilt must fail HONESTLY.
///
/// The failure mode being guarded is not "the route errors" — it is the operator being told the
/// wrong thing. A missing handler yields a bare 404 (or, behind a claiming prefix handler, the
/// misleading `400 Cannot parse path` of #523), both of which send the operator to debug their own
/// request. A 501 that names the capability tells them the truth in one round trip.
class NotImplementedRoutesTest {
    @Test
    void routes_coverEveryDeclaredButUnbuiltRoute() {
        assertThat(routeNames()).containsExactlyInAnyOrder(ManagementRoute.WORKERS_HEALTH.name(),
                                                            ManagementRoute.WORKERS_ENDPOINTS.name(),
                                                            ManagementRoute.CLUSTER_MIGRATE.name(),
                                                            ManagementRoute.CLUSTER_MIGRATE_PLAN.name());
    }

    /// The status is what makes the answer honest. `ProblemResponses` reads it off `HttpStatusAware`;
    /// a cause that skipped the mixin would surface as 500 and read as a server fault instead.
    @Test
    void handler_everyRoute_failsWithNotImplementedStatus() {
        NotImplementedRoutes.notImplementedRoutes()
                            .routes()
                            .forEach(NotImplementedRoutesTest::assertRespondsNotImplemented);
    }

    /// A 501 whose body says only "not implemented" is barely better than a 404. Each message must
    /// name the capability AND why it is absent, so the operator learns what would have to be built.
    @Test
    void handler_everyRoute_explainsWhichCapabilityIsMissing() {
        NotImplementedRoutes.notImplementedRoutes()
                            .routes()
                            .forEach(NotImplementedRoutesTest::assertMessageNamesCapability);
    }

    @Test
    void notImplemented_mapsToStatus501() {
        var cause = NotImplemented.notImplemented("Thing", "Because.");

        assertThat(cause.httpStatus()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        assertThat(cause.message()).isEqualTo("Thing is not implemented. Because.");
    }

    private static void assertRespondsNotImplemented(Route<?> route) {
        assertThat(statusOf(failureOf(route)))
                .withFailMessage("Route %s must answer 501; a non-HttpStatusAware cause would surface as 500",
                                  route.name())
                .isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }

    private static void assertMessageNamesCapability(Route<?> route) {
        assertThat(failureOf(route).message())
                .withFailMessage("Route %s must name the missing capability and the reason", route.name())
                .contains("is not implemented.")
                .hasSizeGreaterThan("is not implemented.".length() + 40);
    }

    private static HttpStatus statusOf(Cause cause) {
        return cause instanceof HttpStatusAware aware
               ? aware.httpStatus()
               : HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /// `to(_ -> cause.promise())` ignores the request context, so no request needs to be faked.
    private static Cause failureOf(Route<?> route) {
        var holder = new Cause[1];

        route.handler()
             .handle(null)
             .await()
             .onSuccess(value -> fail("Route " + route.name() + " must not succeed, got: " + value))
             .onFailure(cause -> holder[0] = cause);

        return holder[0];
    }

    private static List<String> routeNames() {
        return NotImplementedRoutes.notImplementedRoutes()
                                   .routes()
                                   .map(Route::name)
                                   .toList();
    }
}
