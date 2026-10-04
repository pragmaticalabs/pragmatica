// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #909 — `security_mode = "jwt"` on a serving app-http with no `jwks_url` is refused at load with a
/// typed cause naming the missing key, distinct from #888's request-time deny.
class ConfigLoaderJwtRefusalTest {
    private static final String CLUSTER = """
        [cluster]
        environment = "docker"
        nodes = 3
        """;

    @Test
    void jwtWithoutJwksUrl_isRefusedAtLoad_withATypedCauseNamingTheKey() {
        var result = ConfigLoader.loadFromString(CLUSTER + """

            [app-http]
            enabled = "true"
            security_mode = "jwt"
            """);

        result.onSuccess(_ -> fail("security_mode = jwt without jwks_url must be refused at load"))
              .onFailure(cause -> {
                  assertThat(cause).isInstanceOf(ConfigValidator.ConfigError.SecurityMisconfigured.class);
                  assertThat(cause.message()).contains("[app-http] jwks_url is missing");
              });
    }

    @Test
    void jwtWithJwksUrl_loads() {
        ConfigLoader.loadFromString(CLUSTER + """

            [app-http]
            enabled = "true"
            security_mode = "jwt"
            jwks_url = "https://auth.example.com/.well-known/jwks.json"
            """)
                    .onFailure(cause -> fail("control: a complete jwt config must load: " + cause.message()))
                    .onSuccess(config -> assertThat(config.appHttp().jwtConfig().isPresent()).isTrue());
    }

    @Test
    void jwtWithoutJwksUrl_onADisabledServer_loads_becauseNothingServes() {
        ConfigLoader.loadFromString(CLUSTER + """

            [app-http]
            enabled = "false"
            security_mode = "jwt"
            """)
                    .onFailure(cause -> fail("a disabled app-http refuses nothing and must not be refused: " + cause.message()));
    }

    @Test
    void apiKeyAndNoneModes_areUnaffected() {
        for (var mode : new String[]{"api-key", "none"}) {
            ConfigLoader.loadFromString(CLUSTER + "\n[app-http]\nenabled = \"true\"\nsecurity_mode = \"" + mode + "\"\n")
                        .onFailure(cause -> fail("control, mode " + mode + ": " + cause.message()));
        }
    }
}
