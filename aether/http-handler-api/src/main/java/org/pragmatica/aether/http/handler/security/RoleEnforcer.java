// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.handler.security;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public sealed interface RoleEnforcer {
    sealed interface AuthorizationError extends Cause {
        record AccessDenied(String message) implements AuthorizationError{}

        @SuppressWarnings("unused") record unused() implements AuthorizationError {
            @Override public String message() {
                return "";
            }
        }
    }

    static Result<SecurityContext> enforce(SecurityContext context, RoutePermission permission) {
        return permission.allows(context.authorizationRole())
              ? success(context)
              : accessDeniedCause(context.authorizationRole(), permission.minimumRole()).result();
    }

    private static AuthorizationError.AccessDenied accessDeniedCause(AuthorizationRole actual,
                                                                     AuthorizationRole required) {
        return new AuthorizationError.AccessDenied("Access denied: role " + actual + " cannot access " + required + " endpoint");
    }

    record unused() implements RoleEnforcer{}
}
