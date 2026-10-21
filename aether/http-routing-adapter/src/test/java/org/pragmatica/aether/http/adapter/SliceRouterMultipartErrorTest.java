// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.MultipartError;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// I7/A6 through the real `SliceRouter` dispatch: a multipart route answers a request that is not multipart with
/// 415 and a malformed body with 400, each a ProblemDetail whose detail is the error's fixed text with no exception
/// class or decoder message. A slice error mapper that maps every cause to 500 (as the generated mapper's default
/// does) does not override it, and `ErrorMapper.defaultMapper` keeps the typed status. Before the fix all three
/// cases answered 500 with the decoder's exception text.
class SliceRouterMultipartErrorTest {
    private static final String BOUNDARY = "B0undary";
    private static final ErrorMapper EVERYTHING_IS_500 = cause -> HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, cause);

    private final AtomicBoolean invoked = new AtomicBoolean();

    private SliceRouter router(ErrorMapper errorMapper) {
        var route = Route.<String> post("/upload")
                         .withMultipartBody()
                         .to(_ -> {
                             invoked.set(true);
                             return Promise.success("stored");
                         })
                         .as(CommonContentType.TEXT_PLAIN);
        RouteSource source = () -> Stream.of(route);

        return SliceRouter.sliceRouter(source, errorMapper, JsonMapper.defaultJsonMapper());
    }

    private HttpResponseData send(ErrorMapper errorMapper, String contentType, String body) {
        var request = HttpRequestContext.httpRequestContext("/upload",
                                                            "POST",
                                                            Map.of(),
                                                            Map.of("content-type", List.of(contentType)),
                                                            body.getBytes(StandardCharsets.UTF_8),
                                                            "req_multipart");

        return router(errorMapper).handle(request).await().unwrap();
    }

    private static String bodyOf(HttpResponseData response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    @Test
    void nonMultipartRequest_returns415_withFixedDetail() {
        var response = send(ErrorMapper.defaultMapper(), "application/json", "{}");

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(bodyOf(response)).contains("\"status\":415").contains("Request is not multipart/form-data");
        assertThat(invoked).isFalse();
    }

    @Test
    void truncatedBody_returns400_evenUnderAnAllTo500SliceMapper() {
        var response = send(EVERYTHING_IS_500,
                            "multipart/form-data; boundary=" + BOUNDARY,
                            "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"a\"\r\n\r\nPART");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(bodyOf(response)).contains("\"status\":400").contains("no closing boundary delimiter");
        assertThat(invoked).isFalse();
    }

    @Test
    void undecodablePart_returns400_withoutExceptionText() {
        var response = send(ErrorMapper.defaultMapper(),
                            "multipart/form-data; boundary=" + BOUNDARY,
                            "--" + BOUNDARY + "\r\nContent-Type: text/plain\r\n\r\nx\r\n--" + BOUNDARY + "--\r\n");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(bodyOf(response)).contains("Multipart body could not be parsed")
                                    .doesNotContain("Exception")
                                    .doesNotContain("nameAttribute");
    }

    /// A slice that parses multipart itself and returns the raw error still gets its typed status, even under a
    /// slice mapper that maps everything to 500, and the detail is the fixed text.
    @Test
    void rawMultipartErrorFromHandler_returnsItsStatus_underAnAllTo500SliceMapper() {
        var route = Route.<String> post("/raw")
                         .withByteBody()
                         .to(_ -> MultipartError.General.MISSING_BOUNDARY.<String> promise())
                         .as(CommonContentType.TEXT_PLAIN);
        RouteSource source = () -> Stream.of(route);
        var request = HttpRequestContext.httpRequestContext("/raw", "POST", Map.of(), Map.of(), new byte[0], "req_raw");

        var response = SliceRouter.sliceRouter(source, EVERYTHING_IS_500, JsonMapper.defaultJsonMapper())
                                  .handle(request)
                                  .await()
                                  .unwrap();

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(bodyOf(response)).contains("Multipart Content-Type has no boundary parameter");
    }

    @Test
    void defaultMapper_keepsTheTypedStatus() {
        assertThat(ErrorMapper.defaultMapper().map(MultipartError.General.NOT_MULTIPART).status())
            .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(ErrorMapper.defaultMapper().map(MultipartError.General.MISSING_CLOSE_DELIMITER).status())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
