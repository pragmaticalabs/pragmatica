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
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Pins the statuses `SliceRouterCompositeErrorTest` does not reach, against the generated-slice mapper shape whose `default`
/// is a hard-coded 500: a parameter error that reaches the mapper answers 400 (a path mismatch included), a parameter
/// error beside a server fault stays 500, and a composite whose members are ALL transient answers 503.
class SliceRouterStatusPinsTest {
    record Refused(String message) implements Cause.Transient {}

    private static final ErrorMapper GENERATED_STYLE_MAPPER = cause -> switch (cause) {
        case HttpError he -> he;
        default -> HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, cause);
    };

    private static final ErrorMapper COMPOSITE_422_MAPPER = cause -> cause instanceof Causes.CompositeCause
                                                                      ? HttpError.httpError(HttpStatus.UNPROCESSABLE_ENTITY, cause)
                                                                      : GENERATED_STYLE_MAPPER.map(cause);

    /// A spacer mismatch normally ends as the router's own 404 at route lookup and never reaches a handler (#764); when
    /// the cause does arrive as a handler failure it is a parameter error like the others and answers 400, the same as the
    /// management API.
    @Test
    void handle_pathMismatchFromHandler_returns400() {
        var route = failingRoute(new ParameterError.PathMismatch("edit", "other"));

        assertThat(send(route, "/items")).isEqualTo(400);
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

    @Test
    void handle_parameterErrorBesideAServerFault_answers500() {
        var route = failingRoute(composite(new ParameterError.InvalidParameter("limit"), Causes.cause("storage failure")));

        assertThat(send(route, "/items")).as("a client error must not conceal a server fault").isEqualTo(500);
    }

    @Test
    void handle_onlyParameterErrorsInAComposite_answer400() {
        var route = failingRoute(composite(new ParameterError.InvalidParameter("limit"), new ParameterError.MissingParameter("offset")));

        assertThat(send(route, "/items")).isEqualTo(400);
    }

    /// A mapper that answers a composite with a non-500 status has decided; member-by-member resolution only
    /// re-derives a 500, so the mapper's 422 wins over the members' 400 ...
    @Test
    void handle_customMapperAnswering422ForUniformComposite_winsOverMemberResolution() {
        var route = failingRoute(composite(new ParameterError.InvalidParameter("limit"), new ParameterError.MissingParameter("offset")));

        assertThat(send(route, "/items", COMPOSITE_422_MAPPER)).isEqualTo(422);
    }

    /// ... and over the 500 that mixed members would otherwise answer.
    @Test
    void handle_customMapperAnswering422ForMixedComposite_winsOverMemberResolution() {
        var route = failingRoute(composite(new ParameterError.InvalidParameter("limit"), Causes.cause("storage failure")));

        assertThat(send(route, "/items", COMPOSITE_422_MAPPER)).isEqualTo(422);
    }

    private static Cause composite(Cause first, Cause second) {
        return Result.allOf(List.<Result<String>>of(first.result(), second.result())).fold(cause -> cause, _ -> Causes.cause("unreachable"));
    }

    private static Route<String> failingRoute(Cause failure) {
        return Route.<String>get("/items").withoutParameters().to(_ -> failure.<String> promise()).asJson();
    }

    private static int send(Route<?> route, String path) {
        return send(route, path, GENERATED_STYLE_MAPPER);
    }

    private static int send(Route<?> route, String path, ErrorMapper mapper) {
        var router = SliceRouter.sliceRouter(route, mapper, JsonMapper.defaultJsonMapper());

        return router.handle(HttpRequestContext.httpRequestContext(path, "GET", Map.of(), Map.of(), "test"))
                     .await(timeSpan(5).seconds())
                     .unwrap()
                     .statusCode();
    }
}
