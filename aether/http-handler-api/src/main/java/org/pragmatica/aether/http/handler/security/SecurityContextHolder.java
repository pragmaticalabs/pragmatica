// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.handler.security;

import org.pragmatica.lang.Option;


public final class SecurityContextHolder {
    private static final ScopedValue<SecurityContext> SECURITY_CONTEXT = ScopedValue.newInstance();

    private SecurityContextHolder() {}

    public static Option<SecurityContext> currentContext() {
        return SECURITY_CONTEXT.isBound()
              ? Option.option(SECURITY_CONTEXT.get())
              : Option.empty();
    }

    public static boolean isAuthenticated() {
        return SECURITY_CONTEXT.isBound() && SECURITY_CONTEXT.get().isAuthenticated();
    }

    public static ScopedValue<SecurityContext> scopedValue() {
        return SECURITY_CONTEXT;
    }
}
