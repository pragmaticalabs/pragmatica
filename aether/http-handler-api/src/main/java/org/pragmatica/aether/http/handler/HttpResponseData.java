// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.lang.Result.success;


@Codec
public record HttpResponseData(int statusCode, Map<String, String> headers, byte[] body) {
    private static final byte[] EMPTY_BODY = new byte[0];

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json; charset=UTF-8");

    private static final Map<String, String> TEXT_HEADERS = Map.of("Content-Type", "text/plain; charset=UTF-8");

    public HttpResponseData {
        Objects.requireNonNull(headers, "headers");
        if (body == null || body.length == 0) {
            body = EMPTY_BODY;
        }
    }

    public static Result<HttpResponseData> httpResponseData(Result<Integer> statusCode,
                                                            Result<Map<String, String>> headers,
                                                            Result<byte[]> body) {
        return Result.all(statusCode, headers, body).map(HttpResponseData::new);
    }

    public static HttpResponseData httpResponseData(int statusCode, byte[] body) {
        return httpResponseData(statusCode, JSON_HEADERS, body);
    }

    public static HttpResponseData httpResponseData(int statusCode, String body) {
        return httpResponseData(statusCode, TEXT_HEADERS, body.getBytes(StandardCharsets.UTF_8));
    }

    public static HttpResponseData httpResponseData(int statusCode) {
        return httpResponseData(statusCode, Map.of(), EMPTY_BODY);
    }

    public static HttpResponseData httpResponseData(int statusCode, Map<String, String> headers, byte[] body) {
        return Result.all(success(statusCode),
                          success(headers),
                          success(body))
                     .map(HttpResponseData::new)
                     .unwrap();
    }
}
