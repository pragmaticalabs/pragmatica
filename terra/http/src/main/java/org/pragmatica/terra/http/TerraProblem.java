// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.http;

import java.util.Map;

import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.http.ContentCategory;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.ProblemDetail;
import org.pragmatica.http.ProblemDetailJson;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Contract;


/// #2791: the error answers Terra writes itself (a route miss, a failed dispatch, a refused admission) are RFC 9457
/// problem documents, encoded by the same generated codec as the slice router's, so a Terra route answers every error
/// as `application/problem+json`, as it does on Aether.
sealed interface TerraProblem {
    String PROBLEM_JSON = "application/problem+json";
    ContentType CONTENT_TYPE = ContentType.contentType(PROBLEM_JSON, ContentCategory.JSON);

    /// The problem as a router answer, for a failure Terra answers inside the routing path.
    static HttpResponseData response(HttpStatus status, String detail, String path, String requestId) {
        return HttpResponseData.httpResponseData(status.code(),
                                                 Map.of("Content-Type", PROBLEM_JSON),
                                                 encode(status, detail, path, requestId));
    }

    /// The problem written straight to the transport, for a request refused before routing.
    @Contract
    static void write(ResponseWriter writer, HttpStatus status, String detail, String path, String requestId) {
        writer.write(status, encode(status, detail, path, requestId), CONTENT_TYPE);
    }

    private static byte[] encode(HttpStatus status, String detail, String path, String requestId) {
        return ProblemDetailJson.CODEC.encode(ProblemDetail.problemDetail(status, detail, path, requestId));
    }

    record unused() implements TerraProblem {}
}
