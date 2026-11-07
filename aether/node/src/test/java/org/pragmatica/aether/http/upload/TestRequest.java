// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.util.List;
import java.util.Map;

import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.QueryParams;

/// A request head as the server hands it to `classify` and to a sink's `complete`: its body is empty.
public record TestRequest(String requestId, HttpMethod method, String path, Headers headers, QueryParams queryParams) implements HttpRequest {
    /// A POST to `path` declaring `contentType` and, when `length` is not negative, a `content-length`.
    public static TestRequest post(String path, String contentType, long length) {
        var headers = length < 0
                      ? Map.of("content-type", List.of(contentType))
                      : Map.of("content-type", List.of(contentType), "content-length", List.of(String.valueOf(length)));

        return new TestRequest("req-ingest", HttpMethod.POST, path, Headers.headers(headers), QueryParams.queryParams(Map.of()));
    }

    @Override
    public byte[] body() {
        return new byte[0];
    }
}
