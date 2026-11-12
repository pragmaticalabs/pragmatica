// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.testlogging.LogCapture;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// #2881: a 4xx response is the caller's fault and is logged at DEBUG; a 5xx is logged at WARN with the cause chain.
/// The RFC 9457 body is unchanged by the level.
class SliceRouterErrorLogLevelTest {
    private static final String DETAIL = "detail-marker";
    private static final ErrorMapper PASS_THROUGH_MAPPER = cause -> switch (cause) {
        case HttpError he -> he;
        default -> HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, cause);
    };

    private LogCapture capture;

    @BeforeEach
    void attach() {
        capture = LogCapture.capture(SliceRouter.class, Level.DEBUG, true);
    }

    @AfterEach
    void detach() {
        capture.close();
    }

    private HttpResponseData respond(Cause failure) {
        Route<String> route = Route.route(HttpMethod.GET,
                                          "/boom",
                                          _ -> failure.<String> promise(),
                                          CommonContentType.APPLICATION_JSON,
                                          List.of(),
                                          "boom");
        RouteSource source = () -> Stream.of(route);
        var request = HttpRequestContext.httpRequestContext("/boom", "GET", Map.of(), Map.of(), "req_2881");

        return SliceRouter.sliceRouter(source, PASS_THROUGH_MAPPER, JsonMapper.defaultJsonMapper())
                          .handle(request)
                          .await()
                          .unwrap();
    }

    private static Cause failure(HttpStatus status) {
        return HttpError.httpError(status, Causes.cause(DETAIL));
    }

    private static String body(HttpResponseData response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    @Test
    void notFound_logsNoWarn_butLogsAtDebug() {
        var response = respond(failure(HttpStatus.NOT_FOUND));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(capture.messages(Level.WARN)).isEmpty();
        assertThat(capture.messages(Level.DEBUG)).anyMatch(m -> m.contains("req_2881") && m.contains("404"));
    }

    @Test
    void badRequest_logsNoWarn_butLogsAtDebug() {
        var response = respond(failure(HttpStatus.BAD_REQUEST));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(capture.messages(Level.WARN)).isEmpty();
        assertThat(capture.messages(Level.DEBUG)).anyMatch(m -> m.contains("req_2881") && m.contains("400"));
    }

    @Test
    void internalError_logsWarn_withCauseChain() {
        var response = respond(Causes.cause(DETAIL));

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(capture.messages(Level.WARN)).singleElement()
                                                .satisfies(m -> assertThat(m).contains("req_2881", "500", DETAIL, "cause chain"));
        assertThat(capture.messages(Level.DEBUG)).isEmpty();
    }

    @Test
    void serviceUnavailable_logsWarn() {
        var response = respond(failure(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(capture.messages(Level.WARN)).hasSize(1);
    }

    @Test
    void problemBody_isUnchangedByLogLevel() {
        var response = respond(failure(HttpStatus.NOT_FOUND));

        assertThat(body(response)).contains("\"status\":404").contains(DETAIL).contains("req_2881");
    }
}
