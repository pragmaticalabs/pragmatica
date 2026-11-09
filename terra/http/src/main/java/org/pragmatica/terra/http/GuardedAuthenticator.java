// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.http;

import java.util.Objects;

import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Wraps the authenticator an [HttpAuthenticatorFactory] built (#2880), so that provider code can never answer a
/// request with a raw failure. An authenticator that throws, or returns no result, is answered as a refusal with a
/// generic body: the request gets HTTP 500, and neither the exception's type, message nor stack reaches the response.
/// The defect is logged at ERROR with the provider name and the exception, which is where an operator reads it.
///
/// Closing delegates too: a `close()` that throws or returns null is answered as a failed [Promise], never a throw.
public record GuardedAuthenticator(String provider, HttpAuthenticator delegate) implements HttpAuthenticator {
    private static final Logger log = LoggerFactory.getLogger(GuardedAuthenticator.class);
    /// The only text a request sees when the provider's authenticator fails.
    static final Cause UNAVAILABLE = Causes.cause("Authentication is temporarily unavailable");

    @Override
    public Result<SecurityContext> validate(HttpRequestContext request, SecurityPolicy policy) {
        return Result.lift(this::defect,
                           () -> Objects.requireNonNull(delegate.validate(request, policy),
                                                        "authenticator returned no result"))
                     .flatMap(result -> result);
    }

    @Override
    public Promise<Unit> close() {
        return Promise.lift(this::defect,
                            () -> Objects.requireNonNull(delegate.close(),
                                                         "authenticator returned no close Promise"))
                      .flatMap(promise -> promise);
    }

    private Cause defect(Throwable failure) {
        log.error("Authenticator provider '{}' failed", provider, failure);

        return UNAVAILABLE;
    }
}
