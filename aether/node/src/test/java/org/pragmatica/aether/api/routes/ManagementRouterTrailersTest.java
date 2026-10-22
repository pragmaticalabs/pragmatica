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

import static org.assertj.core.api.Assertions.assertThat;

/// #2310: a management route reads the trailers of the server request it was dispatched for; the router's adapter
/// passes them on rather than answering the empty default.
class ManagementRouterTrailersTest {
    private static final Route<String> PROBE = Route.<String>post("/probe/trailers")
                                                    .toImmediate(context -> Result.success(context.trailers()
                                                                                                  .get("x-checksum")
                                                                                                  .or("none")))
                                                    .asText();

    @Test
    void managementRoute_readsTheServerRequestsTrailers() {
        assertThat(handle(Headers.trailers(Map.of("x-checksum", List.of("abc123"))))).isEqualTo("abc123");
    }

    @Test
    void managementRoute_seesNoTrailers_whenTheRequestCarriedNone() {
        assertThat(handle(Headers.empty())).as("a header of the same name is not a trailer").isEqualTo("none");
    }

    private static String handle(Headers trailers) {
        RouteSource source = () -> Stream.of(PROBE);
        var writer = new BodyWriter();

        assertThat(ManagementRouter.managementRouter(source).handle(new TrailerRequest(trailers), writer)).isTrue();

        return writer.body();
    }

    private record TrailerRequest(Headers trailers) implements HttpRequest {
        @Override public String requestId() {return "req-mgmt-trailers";}
        @Override public HttpMethod method() {return HttpMethod.POST;}
        @Override public String path() {return "/probe/trailers";}
        @Override public Headers headers() {return Headers.headers(Map.of("x-checksum", List.of("in-the-headers")));}
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
