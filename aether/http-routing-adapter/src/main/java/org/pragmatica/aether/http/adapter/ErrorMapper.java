// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.pragmatica.http.routing.HttpError;
import org.pragmatica.http.routing.HttpStatus;
import org.pragmatica.lang.Cause;


@FunctionalInterface
public interface ErrorMapper {
    HttpError map(Cause cause);

    static ErrorMapper defaultMapper() {
        return cause -> cause instanceof HttpError he
                        ? he
                        : HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, cause);
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
