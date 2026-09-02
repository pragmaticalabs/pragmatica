// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;


/// Request-validation failures raised by [DeployRoutes], each carrying the HTTP status its semantics
/// demand (#569).
///
/// These were plain `Causes.cause(...)` constants. `ProblemResponses.resolveStatus` tests
/// `cause instanceof HttpStatusAware` and silently defaults everything else to 500, so a request
/// missing a required field answered `500 Internal Server Error` — telling the caller the cluster
/// broke when in fact their request was malformed. Same defect class as [SchemaRouteError] (#542);
/// the domain-level half of this fix lives in
/// [org.pragmatica.aether.update.DeploymentError].
///
/// Every constant here is a malformed-request failure and therefore 400. The status is nevertheless
/// stored **per constant** rather than returned as a shared literal, so a future variant with
/// different semantics (a 409, say) has to state its own code rather than silently inheriting 400 —
/// the same reasoning that leaves `httpStatus()` abstract in the sibling types.
public enum DeployRouteError implements Cause, HttpStatusAware {
    MISSING_BLUEPRINT("Missing required field: blueprint", HttpStatus.BAD_REQUEST),
    MISSING_STRATEGY("Missing required field: strategy", HttpStatus.BAD_REQUEST),
    INVALID_STRATEGY("Invalid strategy; must be one of: canary, blue_green, rolling", HttpStatus.BAD_REQUEST),
    MISSING_CANARY_STAGES("Canary strategy requires at least one stage", HttpStatus.BAD_REQUEST);
    private final String msg;
    private final HttpStatus status;
    DeployRouteError(String msg, HttpStatus status) {
        this.msg = msg;
        this.status = status;
    }
    @Override
    public String message() {
        return msg;
    }
    @Override
    public HttpStatus httpStatus() {
        return status;
    }
}
