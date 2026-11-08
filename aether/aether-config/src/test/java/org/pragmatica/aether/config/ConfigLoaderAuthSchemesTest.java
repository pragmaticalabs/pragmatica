// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #2156: `[app-http] auth_schemes` names the `Authorization` schemes JWT mode accepts. Absent, the default is
/// `["Bearer"]`; a list that is empty, names a scheme twice (case-insensitively) or holds a non-token entry refuses the
/// load and is never replaced by the default.
class ConfigLoaderAuthSchemesTest {
    private static final String JWT_APP_HTTP = """
        [cluster]
        environment = "docker"
        nodes = 3

        [app-http]
        enabled = "true"
        security_mode = "jwt"
        jwks_url = "https://auth.example.com/.well-known/jwks.json"
        """;

    @Test
    void authSchemes_absent_defaultsToBearerOnly() {
        assertThat(loadedSchemes(JWT_APP_HTTP)).containsExactly("Bearer");
    }

    @Test
    void authSchemes_configured_areLoadedInOrder() {
        assertThat(loadedSchemes(JWT_APP_HTTP + "auth_schemes = [\"Token\", \"Bearer\"]\n")).containsExactly("Token", "Bearer");
    }

    @Test
    void authSchemes_empty_isRefused() {
        assertRefused(JWT_APP_HTTP + "auth_schemes = []\n", "auth_schemes is empty");
    }

    @Test
    void authSchemes_duplicate_isRefused() {
        assertRefused(JWT_APP_HTTP + "auth_schemes = [\"Bearer\", \"Token\", \"Bearer\"]\n", "names 'Bearer' more than once");
    }

    @Test
    void authSchemes_duplicateDifferingOnlyInCase_isRefused() {
        assertRefused(JWT_APP_HTTP + "auth_schemes = [\"Bearer\", \"bearer\"]\n", "names 'bearer' more than once");
    }

    @Test
    void authSchemes_entryThatIsNotAToken_isRefused() {
        assertRefused(JWT_APP_HTTP + "auth_schemes = [\"Bearer token\"]\n", "is not an RFC 7235 auth-scheme token");
    }

    private static List<String> loadedSchemes(String toml) {
        return ConfigLoader.loadFromString(toml)
                           .onFailure(cause -> fail("must load: " + cause.message()))
                           .unwrap()
                           .appHttp()
                           .jwtConfig()
                           .map(JwtConfig::authSchemes)
                           .or(List.of());
    }

    private static void assertRefused(String toml, String expectedReason) {
        ConfigLoader.loadFromString(toml)
                    .onSuccess(_ -> fail("must be refused at load"))
                    .onFailure(cause -> assertThat(cause.message()).contains("[app-http]")
                                                                   .contains(expectedReason));
    }
}
