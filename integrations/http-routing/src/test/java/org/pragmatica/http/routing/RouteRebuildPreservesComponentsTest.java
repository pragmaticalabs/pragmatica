// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.http.routing;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.routing.security.Access;
import org.pragmatica.http.routing.security.RequestSecurityContext;
import org.pragmatica.http.routing.security.RouteSecurityPolicy;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// #2965: every `Route` rebuild keeps every component of the route it rebuilds. The source route sets every component
/// to a value that differs from what a plain route carries, so a rebuild that drops one (as `withRequestContext` once
/// dropped `successSpec`) puts the default there and the comparison fails. Components are read from the route record
/// itself, so a component added later is covered once it is set here. `handler` is excluded (a rebuild may wrap it)
/// and `path` is compared separately (a prefix or mount changes it by design).
class RouteRebuildPreservesComponentsTest {
    private static final RouteSecurityPolicy DENY_ALL = new RouteSecurityPolicy() {
        @Override
        public <T extends RequestSecurityContext> Access canAccess(T context) {
            return Access.DENY;
        }
    };

    private static final Handler<Handler<String>> BINDING = _ -> Promise.success(_ -> Promise.success("ok"));

    private static final Route<Handler<String>> FULL = Route.route(HttpMethod.PUT,
                                                                   "/items/",
                                                                   BINDING,
                                                                   CommonContentType.APPLICATION_JSON,
                                                                   List.of("detail"),
                                                                   "putItem",
                                                                   DENY_ALL,
                                                                   3,
                                                                   2,
                                                                   List.of(1),
                                                                   UploadSpec.uploadSpec(true, 1_000, 5_000),
                                                                   SuccessSpec.successSpec(201, _ -> "/items/1"));

    private static final Route<Handler<String>> PLAIN = Route.route(HttpMethod.GET,
                                                                    "/plain",
                                                                    BINDING,
                                                                    CommonContentType.TEXT_PLAIN);

    private static final Map<String, Function<Route<Handler<String>>, Route<?>>> REBUILDS = Map.of("withPrefix",
                                                                                                    route -> (Route<?>) route.withPrefix("/api")
                                                                                                                              .routes()
                                                                                                                              .findFirst()
                                                                                                                              .orElseThrow(),
                                                                                                    "mountInPathMode",
                                                                                                    route -> Route.mountInPathMode(route,
                                                                                                                                   "/api"),
                                                                                                    "mountInHeaderMode",
                                                                                                    route -> Route.mountInHeaderMode(route,
                                                                                                                                     "/api"),
                                                                                                    "withSuccess",
                                                                                                    route -> route.withSuccess(route.successSpec()),
                                                                                                    "withRequestContext",
                                                                                                    Route::withRequestContext);

    @Test
    void theSourceRoute_setsEveryComponentApartFromThePlainRoute() throws Exception {
        for (var component : comparable()) {
            assertThat(value(FULL, component)).as("component %s differs from a plain route", component.getName())
                                              .isNotEqualTo(value(PLAIN, component));
        }
    }

    @Test
    void everyRebuild_keepsEveryComponentButHandlerAndPath() throws Exception {
        for (var rebuild : REBUILDS.entrySet()) {
            var rebuilt = rebuild.getValue().apply(FULL);

            for (var component : comparable()) {
                assertThat(value(rebuilt, component)).as("%s keeps %s", rebuild.getKey(), component.getName())
                                                     .isEqualTo(value(FULL, component));
            }
        }
    }

    private static List<RecordComponent> comparable() {
        return Stream.of(FULL.getClass().getRecordComponents())
                     .filter(component -> !component.getName().equals("handler"))
                     .filter(component -> !component.getName().equals("path"))
                     .toList();
    }

    private static Object value(Route<?> route, RecordComponent component) throws Exception {
        var accessor = component.getAccessor();

        accessor.setAccessible(true);
        return accessor.invoke(route);
    }
}
