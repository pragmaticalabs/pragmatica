// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Result;


/// One place where a value the CALLER supplied (a path id, a body-carried coordinate or version) is parsed for a management
/// route (#1921). The domain parsers (`BlueprintId`, `Artifact`, `Version`, `NodeId`) fail with an untyped cause, which
/// `ProblemResponses` resolves to 500: telling the caller the cluster broke when their input was malformed. Routing every such
/// parse through [#asRequest] turns that failure into a 400 that keeps the parser's own message; a cause that already carries a
/// status is left alone.
public final class RequestParse {
    private RequestParse() {}

    public static <T> Result<T> asRequest(Result<T> parsed) {
        return parsed.mapError(cause -> cause instanceof HttpStatusAware
                                        ? cause
                                        : new ManagementServerError.InvalidRequest(cause.message()));
    }
}
