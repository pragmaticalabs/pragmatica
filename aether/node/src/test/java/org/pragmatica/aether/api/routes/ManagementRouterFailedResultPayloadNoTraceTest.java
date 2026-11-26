// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #3723 / #3794 (F3): a management route whose payload is a failed `Result` has it written by the Jackson Result codec into
/// the response body. The router's codec writes the failure's client text only: no class name, no trace of a caught
/// throwable.
class ManagementRouterFailedResultPayloadNoTraceTest {
    private static final String SENTINEL = "SENTINEL-3794-management-payload";

    private static final Route<Result<String>> PROBE = Route.<Result<String>>get("/probe/failed-result")
                                                            .toImmediate(_ -> Result.success(Causes.fromThrowable(new IllegalStateException(SENTINEL))
                                                                                                   .<String> result()))
                                                            .asJson();

    @Test
    void managementRoute_returningAFailedResultOverAThrowable_writesClientText() {
        RouteSource source = () -> Stream.of(PROBE);
        var writer = new BodyWriter();

        assertThat(ManagementRouter.managementRouter(source).handle(new Request(), writer)).isTrue();

        assertThat(writer.body()).doesNotContain(SENTINEL)
                                 .doesNotContain("IllegalStateException")
                                 .doesNotContain("\\tat ")
                                 .doesNotContain("at java")
                                 .contains("Internal error");
    }

    private record Request() implements HttpRequest {
        @Override public String requestId() {return "req-mgmt-payload";}
        @Override public HttpMethod method() {return HttpMethod.GET;}
        @Override public String path() {return "/probe/failed-result";}
        @Override public Headers headers() {return Headers.headers(Map.of());}
        @Override public Headers trailers() {return Headers.empty();}
        @Override public QueryParams queryParams() {return QueryParams.empty();}
        @Override public byte[] body() {return new byte[0];}
    }

    private static final class BodyWriter implements ResponseWriter {
        private final AtomicReference<String> body = new AtomicReference<>();

        String body() {
            assertThat(body.get()).as("the route answered").isNotNull();

            return body.get();
        }

        @Override
        public void write(HttpStatus status, byte[] content, ContentType contentType) {
            body.set(new String(content, StandardCharsets.UTF_8));
        }

        @Override
        public ResponseWriter header(String name, String value) {
            return this;
        }
    }
}
