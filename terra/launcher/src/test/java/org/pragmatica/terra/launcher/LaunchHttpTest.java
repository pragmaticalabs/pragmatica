// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.launcher;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.aether.http.security.SecurityError;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class LaunchHttpTest {
    @Test void parse_namedApiKey_preservesPrincipalAndRoles() {
        var source = TomlConfigSource.tomlConfigSource("""
            [http]
            security_mode = "api-key"
            [api_keys.orders]
            key = "test-only-credential"
            roles = ["orders-reader", "service"]
            authorization_role = "VIEWER"
            """).unwrap();
        var config = LaunchHttp.launchHttp(Path.of("."), ConfigurationProvider.configurationProvider(source)).unwrap();
        var request = HttpRequestContext.httpRequestContext("/", "GET", Map.of(), Map.of("X-API-Key", List.of("test-only-credential")), "test");
        var auth = config.authenticator().apply().validate(request, SecurityPolicy.authenticated()).unwrap();
        assertThat(auth.principal().value()).contains("orders");
        assertThat(auth.hasRole("orders-reader")).isTrue();
    }

    @Test void parse_securityAndHttpTypos_failInsteadOfWeakeningPolicy() {
        for (var values : List.of(Map.of("http.security_mode", "typo"), Map.of("http.securty_mode", "none"),
                                  Map.of("http.port", "invalid"), Map.of("http.security_mode", "jwt"),
                                  Map.of("http.tls_key", "key.pem"))) {
            assertThat(LaunchHttp.launchHttp(Path.of("."), ConfigurationProvider.builder().withDefaults(values).build()).isFailure()).isTrue();
        }
    }

    @Test void parse_noneMode_stillRefusesExplicitProtectedRoutes() {
        var config = LaunchHttp.launchHttp(Path.of("."), ConfigurationProvider.builder().withDefaults(Map.of("http.security_mode", "none")).build()).unwrap();
        var request = HttpRequestContext.httpRequestContext("/", "GET", Map.of(), Map.of(), "test");
        assertThat(config.authenticator().apply().validate(request, SecurityPolicy.authenticated()).isFailure()).isTrue();
    }

    /// #2156: `[jwt] auth_schemes`. A scheme in the list reaches token parsing (a malformed token is then refused as
    /// such); a scheme outside it never does (a missing credential). Absent, only `Bearer` is accepted.
    @Test void parse_jwtAuthSchemes_configuredTokenSchemeReachesTokenParsing() {
        var auth = jwtLaunch("auth_schemes = [\"Bearer\", \"Token\"]\n").unwrap().authenticator().apply();

        assertThat(refusal(auth, "Token not-a-jwt")).isInstanceOf(SecurityError.InvalidCredentials.class);
        assertThat(refusal(auth, "token not-a-jwt")).isInstanceOf(SecurityError.InvalidCredentials.class);
        assertThat(refusal(auth, "Basic not-a-jwt")).isInstanceOf(SecurityError.MissingCredentials.class);
    }

    @Test void parse_jwtAuthSchemes_absent_acceptsBearerOnly() {
        var auth = jwtLaunch("").unwrap().authenticator().apply();

        assertThat(refusal(auth, "Bearer not-a-jwt")).isInstanceOf(SecurityError.InvalidCredentials.class);
        assertThat(refusal(auth, "Token not-a-jwt")).isInstanceOf(SecurityError.MissingCredentials.class);
    }

    @Test void parse_jwtAuthSchemes_emptyOrDuplicate_failsTheLaunch() {
        var cases = Map.of("auth_schemes = []\n", "auth_schemes is empty",
                           "auth_schemes = [\"Token\", \"token\"]\n", "names 'token' more than once");

        cases.forEach((line, reason) -> assertLaunchRefused(jwtLaunch(line), line, reason));
    }

    private static void assertLaunchRefused(Result<LaunchHttp> launch, String line, String reason) {
        assertThat(launch.isFailure()).as(line).isTrue();
        launch.onFailure(cause -> assertThat(cause.message()).as(line).contains("Invalid [jwt]").contains(reason));
    }

    private static Result<LaunchHttp> jwtLaunch(String authSchemesLine) {
        var source = TomlConfigSource.tomlConfigSource("""
            [http]
            security_mode = "jwt"
            [jwt]
            jwks_url = "https://auth.example.com/.well-known/jwks.json"
            """ + authSchemesLine).unwrap();

        return LaunchHttp.launchHttp(Path.of("."), ConfigurationProvider.configurationProvider(source));
    }

    private static Cause refusal(HttpAuthenticator auth, String authorization) {
        var request = HttpRequestContext.httpRequestContext("/", "GET", Map.of(), Map.of("Authorization", List.of(authorization)), "test");
        return auth.validate(request, SecurityPolicy.bearerTokenRequired())
                   .fold(cause -> cause, _ -> fail("accepted: " + authorization));
    }
}
