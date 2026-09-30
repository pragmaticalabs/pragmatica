// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;


@FunctionalInterface
public interface ErrorMapper {
    HttpError map(Cause cause);

    /// Unmapped causes answer 500, except a [Cause#isTransient] one: a refusal that passes when retried
    /// (#1737) answers 503 so a client can tell it from a server fault. A cause that is not classified
    /// transient — including a publish whose outcome is unknown, which is not retry-safe without a
    /// message ID — stays 500. No `Retry-After` is set, as on the Management-API publish path (#1735).
    static ErrorMapper defaultMapper() {
        return cause -> cause instanceof HttpError he
                        ? he
                        : HttpError.httpError(unmappedStatus(cause), cause);
    }

    private static HttpStatus unmappedStatus(Cause cause) {
        return cause.isTransient()
               ? HttpStatus.SERVICE_UNAVAILABLE
               : HttpStatus.INTERNAL_SERVER_ERROR;
    }

    default ErrorMapper orElse(ErrorMapper other) {
        return cause -> {
            var result = this.map(cause);

            if (result.status() == HttpStatus.INTERNAL_SERVER_ERROR && !(cause instanceof HttpError)) {
                return other.map(cause);
            }

            return result;
        };
    }
}
