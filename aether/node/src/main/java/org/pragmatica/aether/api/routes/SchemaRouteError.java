// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;


/// Failure causes raised by [SchemaRoutes], each projecting itself onto the HTTP status its
/// semantics demand.
///
/// These were plain `Causes.cause(...)` constants, and `ProblemResponses.resolveStatus` tests
/// `cause instanceof HttpStatusAware` and silently defaults everything else to 500 — so a missing
/// schema record and a refused retry were indistinguishable on the wire from a node fault, and a
/// scripted client had no way to tell "your request was wrong" from "the cluster broke". The same
/// defect class was fixed on the publish path by `SchemaError.DatasourceOwnershipConflict`.
///
/// **`httpStatus()` is deliberately left abstract** rather than given a `default` of 500 (the shape
/// `ManagementServerError` uses). Every variant here has a genuine semantic code, and a default
/// would let the next variant added silently inherit the very 500 this type exists to eliminate.
/// Leaving it abstract makes the omission a compile error instead of a wire-level regression.
public sealed interface SchemaRouteError extends Cause, HttpStatusAware {
    /// 404 — the addressed datasource has no schema version record. Raised by every route that reads
    /// an existing record (status, history, migrate, undo, baseline, retry), so it is the dominant
    /// failure of the whole group. The datasource is named in the message because the ProblemDetail
    /// `detail` member is the only place the operator sees which of several datasources was missing.
    record SchemaRecordNotFound(String datasource) implements SchemaRouteError {
        public static SchemaRecordNotFound schemaRecordNotFound(String datasource) {
            return new SchemaRecordNotFound(datasource);
        }

        @Override
        public String message() {
            return "Schema status not found for datasource '" + datasource + "'";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.NOT_FOUND;
        }
    }

    /// 409 — `retry` was addressed at a datasource that is not in `FAILED` or `PENDING` (#724: PENDING
    /// is also retriable, since a migration that never dispatched has no other lever short of a
    /// redeploy). The request is well-formed and the datasource exists; the conflict is with current
    /// cluster state, which is exactly `CONFLICT` rather than `BAD_REQUEST`. Carrying the observed
    /// status makes the response self-explaining: the operator learns what the state actually is
    /// without a second call.
    ///
    /// **`message()` must keep the literal substring "not in FAILED state"** — two integration
    /// scripts (`10-database/test-schema-retry.sh`, `06-deployment/test-schema-migration.sh`,
    /// pinned by `SchemaRouteStatusTest.RetryConflict.problemBody_retainsScriptedClientPhrase_whenSchemaIsNotFailed`)
    /// grep the response body for that exact phrase.
    ///
    /// Name kept as `SchemaNotFailed` despite the widened gate: three references total codebase-wide,
    /// and `SchemaNotRetriable` would ripple further than this ticket's scope justifies. A taste call,
    /// not a correctness one — the `message()` text is the part a caller actually observes.
    record SchemaNotFailed(String datasource, SchemaStatus currentStatus) implements SchemaRouteError {
        public static SchemaNotFailed schemaNotFailed(String datasource, SchemaStatus currentStatus) {
            return new SchemaNotFailed(datasource, currentStatus);
        }

        @Override
        public String message() {
            return "Schema for datasource '" + datasource
                 + "' is not in FAILED state (currently " + currentStatus.name()
                 + ") — retry applies to FAILED or PENDING migrations only";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    /// 400 — a present `?version=` / `?targetVersion=` query parameter that is not an integer. This
    /// was previously not a `Cause` at all: `Integer.parseInt` threw out of the handler, past the
    /// routing layer (which lifts nothing) and past `ManagementRouter`, and was caught only by the
    /// outermost Netty guard — which answers 500 with a bare `{"error":"Internal Server Error"}`
    /// envelope, bypassing the RFC 9457 funnel entirely and losing the request from management
    /// metrics. An unparseable parameter is the caller's error, so it is a 400 with a body naming
    /// the offending parameter and value. An ABSENT parameter keeps its documented default
    /// (`0` for undo, `1` for baseline) and is not an error.
    record InvalidVersionParameter(String parameterName, String value) implements SchemaRouteError {
        public static InvalidVersionParameter invalidVersionParameter(String parameterName, String value) {
            return new InvalidVersionParameter(parameterName, value);
        }

        @Override
        public String message() {
            return "Invalid '" + parameterName + "' parameter: '" + value + "' is not an integer";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.BAD_REQUEST;
        }
    }
}
