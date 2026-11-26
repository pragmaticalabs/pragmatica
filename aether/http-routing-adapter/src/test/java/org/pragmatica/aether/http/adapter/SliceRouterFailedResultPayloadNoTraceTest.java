// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

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
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// #3723 / #3794 (F3): a slice route whose PAYLOAD is a failed `Result` (or holds a `Cause`) has that failure written by the
/// Jackson Result codec into a `200` body. Whatever mapper the router is given, the body carries the failure's client text
/// only: no class name, no trace of a caught throwable.
class SliceRouterFailedResultPayloadNoTraceTest {
    private static final String SENTINEL = "SENTINEL-3794-slice-payload";

    record Outcome(Cause failure) {}

    private static final ErrorMapper MAPPER = cause -> switch (cause) {
        case HttpError he -> he;
        default -> HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, cause);
    };

    private static Cause aThrowable() {
        return Causes.fromThrowable(new IllegalStateException(SENTINEL));
    }

    private static String respond(Object payload, JsonMapper mapper) {
        Route<Object> route = Route.route(HttpMethod.GET,
                                          "/payload",
                                          ctx -> Promise.success(payload),
                                          CommonContentType.APPLICATION_JSON,
                                          List.of(),
                                          "payload");
        RouteSource source = () -> Stream.of(route);
        var request = HttpRequestContext.httpRequestContext("/payload", "GET", Map.of(), Map.of(), "req_3794");
        HttpResponseData response = SliceRouter.sliceRouter(source, MAPPER, mapper).handle(request).await().unwrap();

        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static void assertClientText(String body) {
        assertThat(body).doesNotContain(SENTINEL)
                        .doesNotContain("IllegalStateException")
                        .doesNotContain("\\tat ")
                        .doesNotContain("at java")
                        .contains("Internal error");
    }

    @Test
    void failedResultPayload_overAThrowable_defaultMapper_writesClientText() {
        assertClientText(respond(aThrowable().<String> result(), JsonMapper.defaultJsonMapper()));
    }

    @Test
    void failedResultPayload_overAThrowable_valueObjectMapper_writesClientText() {
        assertClientText(respond(aThrowable().<String> result(), JsonMapper.valueObjectJsonMapper()));
    }

    @Test
    void causeInsideAPayloadRecord_writesClientText() {
        assertClientText(respond(new Outcome(aThrowable()), JsonMapper.valueObjectJsonMapper()));
    }

    @Test
    void successResultPayload_isWrittenUnchanged() {
        assertThat(respond(Result.success("fine"), JsonMapper.defaultJsonMapper())).contains("\"success\":true").contains("fine");
    }
}
