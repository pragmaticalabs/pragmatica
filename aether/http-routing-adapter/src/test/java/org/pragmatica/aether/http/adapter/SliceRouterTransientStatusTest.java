// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.slice.PublishOutcomeUnknown;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// #1737: an app (slice) route whose handler fails with a [Cause.Transient] refusal answers 503 (retryable),
/// not 500 — through a mapper whose `default` is the generated hard-coded 500, which is what a slice's
/// `errorMapper()` looks like. A non-transient failure stays 500, and so does a publish whose outcome is unknown
/// (not retry-safe without a message ID, #1750), even when the cause it wraps is itself transient.
class SliceRouterTransientStatusTest {
    record Refused(String message) implements Cause.Transient {}

    // The generated slice mapper: a switch whose default is a hard-coded 500 (RouteSourceGenerator).
    private static final ErrorMapper GENERATED_STYLE_MAPPER = cause -> switch (cause) {
        case HttpError he -> he;
        default -> HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, cause);
    };

    private static SliceRouter router(Cause failure) {
        Route<String> route = Route.route(HttpMethod.GET,
                                          "/publish",
                                          ctx -> failure.<String> promise(),
                                          CommonContentType.APPLICATION_JSON,
                                          List.of(),
                                          "publish");
        RouteSource source = () -> Stream.of(route);

        return SliceRouter.sliceRouter(source, GENERATED_STYLE_MAPPER, JsonMapper.defaultJsonMapper());
    }

    private static int statusOf(Cause failure) {
        var request = HttpRequestContext.httpRequestContext("/publish", "GET", Map.of(), Map.of(), "req_1737");

        return router(failure).handle(request)
                              .await()
                              .unwrap()
                              .statusCode();
    }

    @Test
    void handle_answers503_forTransientCause() {
        assertThat(statusOf(new Refused("Stream partition s[0] is not yet promoted on this node"))).isEqualTo(503);
    }

    @Test
    void handle_answers500_forNonTransientCause() {
        assertThat(statusOf(Causes.cause("disk on fire"))).isEqualTo(500);
    }

    @Test
    void handle_answers500_forPublishOutcomeUnknown_evenOverTransientOrigin() {
        assertThat(statusOf(PublishOutcomeUnknown.FACTORY.apply(new Refused("acks timed out")))).isEqualTo(500);
    }

    @Test
    void handle_keepsExplicitHttpStatus_overTransience() {
        assertThat(statusOf(HttpStatus.CONFLICT.with(new Refused("conflict")))).isEqualTo(409);
    }

    @Test
    void defaultMapper_answers503_forTransientCause() {
        assertThat(ErrorMapper.defaultMapper().map(new Refused("x")).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
