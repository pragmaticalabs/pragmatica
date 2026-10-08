// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.ParameterError;
import org.pragmatica.http.routing.Route;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Pins the statuses `SliceRouterCompositeErrorTest` does not reach: a path-segment mismatch answers 404 and a
/// missing parameter 400 (not the slice mapper's 500), and a composite whose members are ALL transient answers 503.
/// The mapper is the generated-slice shape, whose `default` is a hard-coded 500.
class SliceRouterStatusPinsTest {
    record Refused(String message) implements Cause.Transient {}

    private static final ErrorMapper GENERATED_STYLE_MAPPER = cause -> switch (cause) {
        case HttpError he -> he;
        default -> HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, cause);
    };

    /// Spacers are matched during route lookup, so a mismatch normally ends as the router's own 404 before any
    /// handler runs; this pins the status when the mismatch reaches the error mapper as a handler failure.
    @Test
    void handle_pathMismatchFromHandler_returns404() {
        var route = failingRoute(new ParameterError.PathMismatch("edit", "other"));

        assertThat(send(route, "/items")).isEqualTo(404);
    }

    @Test
    void handle_missingParameterFromHandler_returns400() {
        var route = failingRoute(new ParameterError.MissingParameter("limit"));

        assertThat(send(route, "/items")).isEqualTo(400);
    }

    @Test
    void handle_allTransientComposite_returns503() {
        var route = Route.<String>get("/items").withoutParameters()
                         .to(_ -> Result.allOf(List.<Result<String>>of(new Refused("one").result(), new Refused("two").result()))
                                        .async()
                                        .map(_ -> "unused"))
                         .asJson();

        assertThat(send(route, "/items")).isEqualTo(503);
    }

    private static Route<String> failingRoute(Cause failure) {
        return Route.<String>get("/items").withoutParameters().to(_ -> failure.<String> promise()).asJson();
    }

    private static int send(Route<?> route, String path) {
        var router = SliceRouter.sliceRouter(route, GENERATED_STYLE_MAPPER, JsonMapper.defaultJsonMapper());

        return router.handle(HttpRequestContext.httpRequestContext(path, "GET", Map.of(), Map.of(), "test"))
                     .await(timeSpan(5).seconds())
                     .unwrap()
                     .statusCode();
    }
}
