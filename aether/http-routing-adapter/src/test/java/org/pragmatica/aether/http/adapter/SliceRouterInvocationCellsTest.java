// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.slice.ObservabilityStrategyCell;
import org.pragmatica.aether.slice.ObservabilityStrategyCell.InvocationStrategy;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.routing.Handler;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// #277 increment 2, north-south seam: `SliceRouter.withInvocationCells` must wrap EACH route's handler
/// with its own per-injection-point cell, once, at construction. A sentinel strategy planted on one
/// route's cell decorates only that route's response; the sibling route (identity) is untouched. An
/// un-`.named()` route keys its cell by path. Mirrors the publisher's decorator (mint cell, register,
/// rewrap handler over `cell.around`).
class SliceRouterInvocationCellsTest {
    private static final String ARTIFACT_BASE = "com.example:my-slice";

    private final Map<String, ObservabilityStrategyCell> cellsByKey = new HashMap<>();

    private SliceRouter router() {
        Route<String> named = Route.route(HttpMethod.GET,
                                          "/one",
                                          ctx -> Promise.success("one"),
                                          CommonContentType.APPLICATION_JSON,
                                          List.of(),
                                          "getOne");
        Route<String> unnamed = Route.route(HttpMethod.GET,
                                            "/two",
                                            ctx -> Promise.success("two"),
                                            CommonContentType.APPLICATION_JSON,
                                            List.of(),
                                            "");
        RouteSource source = () -> Stream.of(named, unnamed);

        return SliceRouter.sliceRouter(source, ErrorMapper.defaultMapper(), JsonMapper.defaultJsonMapper())
                          .withInvocationCells(this::decorate);
    }

    private Route<?> decorate(Route<?> route) {
        // Mirrors HttpRoutePublisher.routeCellKey: name, else path normalized without the router's
        // trailing slash — the operator-facing key matches the path as authored.
        var key = route.name().isEmpty()
                  ? stripTrailingSlash(route.path())
                  : route.name();
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT_BASE, key);

        cellsByKey.put(key, cell);

        return wrap(cell, route);
    }

    private static String stripTrailingSlash(String path) {
        return path.length() > 1 && path.endsWith("/")
               ? path.substring(0, path.length() - 1)
               : path;
    }

    private static <T> Route<T> wrap(ObservabilityStrategyCell cell, Route<T> route) {
        var original = route.handler();
        Handler<T> wrapped = ctx -> cell.around(() -> original.handle(ctx));

        return Route.route(route.method(),
                           route.path(),
                           wrapped,
                           route.contentType(),
                           route.spacers(),
                           route.name(),
                           route.security(),
                           route.version(),
                           route.pathParamCount());
    }

    private static String body(SliceRouter router, String path) {
        var request = HttpRequestContext.httpRequestContext(path, "GET", Map.of(), Map.of(), "req_test");

        return new String(router.handle(request)
                                .await()
                                .unwrap()
                                .body());
    }

    @Test
    void withInvocationCells_decoratesOnlyTheRoute_whoseCellHoldsASentinel() {
        var router = router();

        cellsByKey.get("getOne").swap(decorating());

        assertThat(body(router, "/one")).contains("decorated:one");
        assertThat(body(router, "/two")).contains("two");
        assertThat(body(router, "/two")).doesNotContain("decorated:");
    }

    @Test
    void withInvocationCells_keysUnnamedRoute_byPath() {
        router();

        assertThat(cellsByKey).containsKey("getOne");
        assertThat(cellsByKey).containsKey("/two");
    }

    @Test
    void withInvocationCells_leavesRoutesUntouched_whileCellsStayIdentity() {
        var router = router();

        assertThat(body(router, "/one")).contains("one");
        assertThat(body(router, "/one")).doesNotContain("decorated:");
        assertThat(cellsByKey.get("getOne").strategy()).isSameAs(InvocationStrategy.IDENTITY);
    }

    // Decorates the wrapped call's result so a fired cell is observable in the response body.
    private static InvocationStrategy decorating() {
        return proceed -> proceed.apply().map(value -> "decorated:" + value);
    }
}
