// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.security;

import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Option;


/// An authenticated request: the validated context and the ADMIN grant minted at the credential match, none otherwise.
public record Authenticated(SecurityContext context, Option<AdminGrant> grant) {
    public static Authenticated authenticated(SecurityContext context, Option<AdminGrant> grant) {
        return new Authenticated(context, grant);
    }

    /// No grant: the outcome of every path that matched no ADMIN credential.
    public static Authenticated authenticated(SecurityContext context) {
        return authenticated(context, Option.none());
    }
}
