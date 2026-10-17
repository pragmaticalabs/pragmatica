// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;


/// The submitted blueprint was refused by `BlueprintParser`/`SliceSpec` — malformed TOML, a missing id, a
/// bad artifact, `instances` below the #1495 floor, `minAvailable` above `instances`. The caller sent
/// something invalid, so it is 400 (#1495; same defect class as #569): the parser's causes live in the
/// HTTP-free `slice` module, and `ProblemResponses.resolveStatus` answers 500 for any cause that is not
/// `HttpStatusAware`. The typed parser cause stays reachable through [#origin()], and the message quotes
/// it verbatim so the refusal (e.g. the floor) reaches the ProblemDetail `detail`.
public record BlueprintRejected(Cause origin, String message) implements Cause.Wrapped, HttpStatusAware {
    public static final Fn1<BlueprintRejected, Cause> FACTORY = origin -> new BlueprintRejected(origin,
                                                                                                "Blueprint rejected: " + origin.message());

    @Override
    public HttpStatus httpStatus() {
        return HttpStatus.BAD_REQUEST;
    }
}
