// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.launcher;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.TomlConfigSource;
import static org.assertj.core.api.Assertions.assertThat;

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
}
