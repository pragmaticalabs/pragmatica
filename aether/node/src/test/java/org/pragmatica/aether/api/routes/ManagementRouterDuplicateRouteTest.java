// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class ManagementRouterDuplicateRouteTest {
    private record FirstSource(ManagementRoute declared) implements RouteSource {
        @Override
        public Stream<Route<?>> routes() {
            return Stream.of(ManagementRoutes.<String>route(declared).toJson(_ -> Promise.success("\"first\"")));
        }
    }

    private record SecondSource(ManagementRoute declared) implements RouteSource {
        @Override
        public Stream<Route<?>> routes() {
            return Stream.of(ManagementRoutes.<String>route(declared).toJson(_ -> Promise.success("\"second\"")));
        }
    }

    @Test
    void managementRouter_sameNameFromTwoSources_refusesNamingTheRouteAndBothSources() {
        var outcome = ManagementRouter.managementRouter(new FirstSource(ManagementRoute.HEALTH_LIVE),
                                                        new SecondSource(ManagementRoute.HEALTH_LIVE));

        assertThat(outcome.isFailure()).as("a duplicate is refused, not resolved by keeping the first").isTrue();
        outcome.onSuccess(_ -> fail("a table with a duplicate name must not assemble"))
               .onFailure(cause -> assertThat(cause.message()).contains(ManagementRoute.HEALTH_LIVE.name())
                                                              .contains("FirstSource")
                                                              .contains("SecondSource"));
    }

    @Test
    void managementRouter_sameNameRegisteredTwiceByOneSource_refuses() {
        RouteSource twice = () -> Stream.concat(new FirstSource(ManagementRoute.HEALTH_LIVE).routes(),
                                                new SecondSource(ManagementRoute.HEALTH_LIVE).routes());

        assertThat(ManagementRouter.managementRouter(twice).isFailure()).isTrue();
    }

    @Test
    void managementRouter_distinctNames_assemblesAndResolvesEach() {
        var outcome = ManagementRouter.managementRouter(new FirstSource(ManagementRoute.HEALTH_LIVE),
                                                        new SecondSource(ManagementRoute.HEALTH_READY));

        assertThat(outcome.isSuccess()).isTrue();
        outcome.onSuccess(router -> assertThat(router.registered(ManagementRoute.HEALTH_LIVE.name()).isPresent()).isTrue())
               .onSuccess(router -> assertThat(router.registered(ManagementRoute.HEALTH_READY.name()).isPresent()).isTrue());
    }

    /// A route with no name dispatches by path and is no registration under a name, so repeating it is not a duplicate.
    @Test
    void managementRouter_unnamedRoutesRepeating_assembles() {
        RouteSource first = () -> Stream.of(Route.<String>get("/a").toJson(_ -> Promise.success("\"a\"")));
        RouteSource second = () -> Stream.of(Route.<String>get("/b").toJson(_ -> Promise.success("\"b\"")));

        assertThat(ManagementRouter.managementRouter(first, second).isSuccess()).isTrue();
    }
}
