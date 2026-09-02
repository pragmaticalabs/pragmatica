// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.nio.charset.StandardCharsets;

import org.pragmatica.http.ContentCategory;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.http.ProblemDetail;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;


/// Canonical RFC 9457 ProblemDetail emission helper for the management plane.
///
/// Replaces ad-hoc `{"error":"..."}` envelopes scattered across `ManagementRouter`,
/// `ManagementServer` forward/probe error paths, and the inline 404 builder. All
/// management error responses now share one wire shape: `application/problem+json`
/// with the standard `type/title/status/detail/instance` members plus the `requestId`
/// extension for request tracing.
///
/// Cause → status resolution: any cause implementing `HttpStatusAware` surfaces its
/// `httpStatus()` (which includes `HttpError` instances via the default mapping to
/// `status()`); any other Cause defaults to HTTP 500. Per-domain sealed `*Error` types
/// should implement `HttpStatusAware` with a default and per-variant overrides where
/// semantics differ (e.g. `ClusterConfigError` defaults to 400 with `VersionConflict`→409,
/// `ClusterNotFound`→404, etc.). The fallback to 500 preserves today's behavior for
/// un-mapped causes — incrementally fixable per domain.
public final class ProblemResponses {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    private static final ContentType CONTENT_TYPE_PROBLEM = ContentType.contentType("application/problem+json",
                                                                                    ContentCategory.JSON);

    private ProblemResponses() {}

    /// Write a ProblemDetail response derived from a Cause.
    ///
    /// @param response  the underlying response writer
    /// @param cause     failure cause (status taken from `HttpError.status()`, else 500)
    /// @param instance  request path (becomes the `instance` member)
    /// @param requestId request identifier (extension member)
    @Contract
    public static void writeProblem(ResponseWriter response, Cause cause, String instance, String requestId) {
        writeProblem(response, resolveStatus(cause), cause.message(), instance, requestId);
    }

    /// Write a ProblemDetail response with explicit status and detail.
    ///
    /// @param response  the underlying response writer
    /// @param status    routing-layer HTTP status (becomes both wire status and `status` member)
    /// @param detail    error detail text (becomes the `detail` member)
    /// @param instance  request path (becomes the `instance` member)
    /// @param requestId request identifier (extension member)
    @Contract
    public static void writeProblem(ResponseWriter response,
                                    HttpStatus status,
                                    String detail,
                                    String instance,
                                    String requestId) {
        var problem = ProblemDetail.problemDetail(status, detail, instance, requestId);
        var serverStatus = toServerStatus(status.code());

        MAPPER.writeAsString(problem)
              .onSuccess(json -> response.header(ResponseWriter.X_REQUEST_ID, requestId)
                                         .write(serverStatus,
                                                json.getBytes(StandardCharsets.UTF_8),
                                                CONTENT_TYPE_PROBLEM))
              .onFailure(_ -> response.error(serverStatus, detail));
    }

    /// Render a ProblemDetail as JSON bytes — used by paths that must bypass `ResponseWriter`
    /// (e.g. inline construction of `HttpResponseData` for the cluster-forward channel).
    /// Falls back to the legacy `{"error":"..."}` envelope on serialization failure.
    public static byte[] renderProblemBytes(HttpStatus status, String detail, String instance, String requestId) {
        var problem = ProblemDetail.problemDetail(status, detail, instance, requestId);

        return MAPPER.writeAsString(problem)
                     .map(json -> json.getBytes(StandardCharsets.UTF_8))
                     .or(() -> ("{\"error\":\"" + escapeJson(detail) + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    public static String problemContentType() {
        return "application/problem+json";
    }

    private static HttpStatus resolveStatus(Cause cause) {
        return cause instanceof HttpStatusAware ha
               ? ha.httpStatus()
               : HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static org.pragmatica.http.HttpStatus toServerStatus(int code) {
        for (var status : org.pragmatica.http.HttpStatus.values()) {
            if (status.code() == code) {
                return status;
            }
        }

        return org.pragmatica.http.HttpStatus.INTERNAL_SERVER_ERROR;
    }

    // RET-06: defensive null-coalescing when serializing a possibly-absent string field to JSON.
    @SuppressWarnings("JBCT-RET-06")
    private static String escapeJson(String s) {
        return s == null
               ? ""
               : s.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r");
    }
}
