// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.config;

import java.util.List;

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

    private static String appHttpJwt(String jwksUrl) {
        return CLUSTER + """

            [app-http]
            enabled = "true"
            security_mode = "jwt"
            jwks_url = "%s"
            """.formatted(jwksUrl);
    }

    /// F2: a jwks_url that is blank, relative, unparseable or plain http to a remote host is refused at load, by the same predicate PF-34 applies.
    @Test
    void anUnusableJwksUrl_isRefusedAtLoad_withTheSameTypedCause() {
        for (var bad : List.of("   ", "/relative/jwks.json", "http://auth.example.com/jwks.json", "ht tp://x")) {
            ConfigLoader.loadFromString(appHttpJwt(bad))
                        .onSuccess(_ -> fail("jwks_url '" + bad + "' must be refused at load"))
                        .onFailure(cause -> {
                            assertThat(cause).as(bad).isInstanceOf(ConfigValidator.ConfigError.SecurityMisconfigured.class);
                            assertThat(cause.message()).as(bad).contains("jwks_url").contains("https");
                        });
        }
    }

    @Test
    void httpsAndLoopbackHttpJwksUrls_load() {
        for (var good : List.of("https://auth.example.com/jwks.json", "http://localhost:8080/jwks.json", "http://127.0.0.1:9000/jwks.json")) {
            assertThat(ConfigLoader.loadFromString(appHttpJwt(good)).isSuccess()).as(good).isTrue();
        }
    }

    /// F5: the missing-key refusal must not suggest issuer/audience are required.
    @Test
    void theMissingKeyMessage_saysIssuerAndAudienceAreOptional() {
        ConfigLoader.loadFromString(CLUSTER + "\n[app-http]\nenabled = \"true\"\nsecurity_mode = \"jwt\"\n")
                    .onFailure(cause -> assertThat(cause.message()).contains("jwks_url is required (issuer/audience optional)"));
    }

    /// N1: a padded jwks_url is accepted AND stored trimmed. The string the predicate judged is the string the node will fetch, so the node cannot
    /// boot on a URL with a leading space and then reject every token.
    @Test
    void aPaddedJwksUrl_isStoredTrimmed() {
        var loaded = ConfigLoader.loadFromString(appHttpJwt("   https://auth.example.com/jwks.json  "));

        assertThat(loaded.isSuccess()).isTrue();
        loaded.onSuccess(config -> assertThat(config.appHttp().jwtConfig().map(JwtConfig::jwksUrl).or("<none>")).isEqualTo("https://auth.example.com/jwks.json"));
    }

    /// C10/A6: the operator-facing text of the two refusals, pinned.
    @Test
    void theMessages_nameTheKey_theCause_andTheRule() {
        ConfigLoader.loadFromString(appHttpJwt("http://auth.example.com/jwks.json"))
                    .onFailure(cause -> assertThat(cause.message()).contains("Security misconfiguration: [app-http] security_mode = \"jwt\" but jwks_url "
                                                                             + "'http://auth.example.com/jwks.json' must use https (http is accepted only to a loopback host)")
                                                                   .contains("jwks_url must be an absolute https URL (http only to a loopback host)."));
        ConfigLoader.loadFromString(appHttpJwt("   "))
                    .onFailure(cause -> assertThat(cause.message()).contains("but jwks_url is blank. jwks_url must be an absolute https URL"));
    }
}
