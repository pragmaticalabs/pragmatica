// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.JsonCodec;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.routing.JsonCodecAdapter;
import org.pragmatica.http.routing.RequestRouter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// The Forge API is an HTTP boundary: a body it writes carries a failure's client text, never its `message()`, which for
/// a caught throwable is the printed stack trace (#3723). Two sites write an error body from a `Cause`: a route handler
/// that fails (400) and a success value that cannot be serialized (500). Mutation: writing `cause.message()` at either
/// site turns the matching test red (the second site is pinned by the failing-codec test); the codec copying the mapper's
/// message into its error turns the end-to-end unserializable test red.
class ForgeApiHandlerClientErrorsTest {
    private static final String INTERNAL_TEXT = "secret-internal-text";

    private final RequestRouter router = RequestRouter.with(failingRoute(), unserializableRoute(), plainRoute());
    private final ForgeApiHandler handler = ForgeApiHandler.forgeApiHandler(router, JsonCodecAdapter.defaultCodec());

    @Test
    void failingRouteHandler_writesTheClientText_notTheThrowablesTrace() {
        var writer = new RecordingWriter();
        var cause = Causes.fromThrowable(new IllegalStateException(INTERNAL_TEXT));

        handler.handle(new StubRequest(HttpMethod.GET, "/api/failing"), writer);

        assertThat(cause.message()).as("control: the cause's own message carries the internal text").contains(INTERNAL_TEXT);
        assertThat(writer.status).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(writer.body).contains(HttpError.clientMessage(cause));
        assertThat(writer.body).doesNotContain(INTERNAL_TEXT).doesNotContain("IllegalStateException").doesNotContain("\\tat ");
    }

    @Test
    void unserializableSuccessValue_writesTheClientText_notTheSerializersMessage() {
        var writer = new RecordingWriter();

        handler.handle(new StubRequest(HttpMethod.GET, "/api/unserializable"), writer);

        assertThat(writer.status).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(writer.body).contains("Serialization failed");
        assertThat(writer.body).doesNotContain(INTERNAL_TEXT).doesNotContain("Exception").doesNotContain("\\tat ");
    }

    /// The codec fails with a caught throwable, whose `message()` is its stack trace: only the handler's own rendering of the
    /// failure can hide it, so this pins the handler's site independently of what a real codec puts in its error.
    @Test
    void codecFailingWithAThrowable_writesTheFixedInternalText_notTheTrace() {
        var writer = new RecordingWriter();
        var codec = new FailingCodec(Causes.fromThrowable(new IllegalStateException(INTERNAL_TEXT)));

        ForgeApiHandler.forgeApiHandler(router, codec).handle(new StubRequest(HttpMethod.GET, "/api/plain"), writer);

        assertThat(writer.status).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(writer.body).contains(HttpError.INTERNAL_ERROR_TEXT);
        assertThat(writer.body).doesNotContain(INTERNAL_TEXT).doesNotContain("IllegalStateException").doesNotContain("\\tat ");
    }

    private static Route<String> plainRoute() {
        return Route.<String> get("/api/plain")
                    .to(_ -> Promise.success("value"))
                    .asJson();
    }

    private record FailingCodec(Cause failure) implements JsonCodec {
        @Override
        public Result<byte[]> serialize(Object value) {
            return failure.result();
        }

        @Override
        public <T> Result<T> deserialize(byte[] bytes, TypeToken<T> token) {
            return failure.result();
        }
    }

    private static Route<String> failingRoute() {
        return Route.<String> get("/api/failing")
                    .to(_ -> Causes.fromThrowable(new IllegalStateException(INTERNAL_TEXT)).<String> promise())
                    .asJson();
    }

    private static Route<Unserializable> unserializableRoute() {
        return Route.<Unserializable> get("/api/unserializable")
                    .to(_ -> Promise.success(new Unserializable("value")))
                    .asJson();
    }

    /// Jackson reads the accessor, which throws: the serializer's failure message quotes the internal text.
    private record Unserializable(String value) {
        @Override
        public String value() {
            throw new IllegalStateException(INTERNAL_TEXT);
        }
    }

    private record StubRequest(HttpMethod method, String path) implements HttpRequest {
        @Override
        public String requestId() {
            return "forge-api-handler-client-errors-test";
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
        private volatile HttpStatus status;
        private volatile String body = "";

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            this.status = status;
            this.body = new String(body, StandardCharsets.UTF_8);
        }

        @Override
        public ResponseWriter header(String name, String value) {
            return this;
        }
    }
}
