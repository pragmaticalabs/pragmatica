// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.server.ResponseWriter;

import static org.assertj.core.api.Assertions.assertThat;

/// Forge serves a REST API and nothing else: the web dashboard it used to serve was removed, so a path outside
/// `/api/` must not reach any file handler. It answers `404` with a JSON error naming the path, and an `OPTIONS`
/// preflight is still answered by the CORS branch. Mutation: restoring a static-file branch (or answering 200 for a
/// non-`/api/` path) turns the first test red; dropping the OPTIONS branch turns the second red.
class ForgeRequestHandlerTest {
    // The API handler is only reached for `/api/` paths; none of these tests sends one, so a null handler is never dereferenced.
    private final ForgeRequestHandler handler = ForgeRequestHandler.forgeRequestHandler(null);

    @Test
    void nonApiPath_isAnswered404_andFormerDashboardAssetsAreNotServed() {
        for (var path : List.of("/", "/index.html", "/js/app.js", "/css/tokens.css", "/dashboard")) {
            var writer = new RecordingWriter();

            handler.handle(new StubRequest(HttpMethod.GET, path), writer);

            assertThat(writer.status).as("status for %s", path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(writer.body).as("body for %s", path).contains("Not found: " + path);
        }
    }

    @Test
    void optionsPreflight_isStillAnsweredByTheCorsBranch() {
        var writer = new RecordingWriter();

        handler.handle(new StubRequest(HttpMethod.OPTIONS, "/api/anything"), writer);

        assertThat(writer.status).isEqualTo(HttpStatus.OK);
        assertThat(writer.headers).containsKey("Access-Control-Allow-Origin");
    }

    private record StubRequest(HttpMethod method, String path) implements HttpRequest {
        @Override
        public String requestId() {
            return "forge-request-handler-test";
        }

        @Override
        public Headers headers() {
            return Headers.empty();
        }

        @Override
        public QueryParams queryParams() {
            return QueryParams.empty();
        }

        @Override
        public byte[] body() {
            return new byte[0];
        }
    }

    private static final class RecordingWriter implements ResponseWriter {
        private final java.util.Map<String, String> headers = new java.util.HashMap<>();
        private volatile HttpStatus status;
        private volatile String body = "";

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            this.status = status;
            this.body = new String(body, StandardCharsets.UTF_8);
        }

        @Override
        public ResponseWriter header(String name, String value) {
            headers.put(name, value);

            return this;
        }
    }
}
