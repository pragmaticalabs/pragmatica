// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.launcher;

import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.aether.http.security.SecurityError;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Result;
import org.pragmatica.terra.http.HttpAuthenticatorFactory;

/// Test provider (#2880) registered through `META-INF/services`: accepts the header `X-Test-Token` equal to the
/// value of `authenticator.test-header.token`.
public final class TestHeaderAuthenticator implements HttpAuthenticatorFactory {
    @Override
    public String name() {
        return "test-header";
    }

    @Override
    public Result<HttpAuthenticator> authenticator(ConfigurationProvider settings) {
        return settings.getString("authenticator.test-header.token")
                       .toResult(new TerraLaunchError("authenticator.test-header.token is required"))
                       .map(TestHeaderAuthenticator::acceptingToken);
    }

    private static HttpAuthenticator acceptingToken(String token) {
        return (request, _) -> request.headers()
                                      .getOrDefault("X-Test-Token", java.util.List.of())
                                      .contains(token)
                               ? SecurityContext.securityContext("custom-user", Set.of(), Map.of())
                               : SecurityError.INVALID_API_KEY.result();
    }
}
