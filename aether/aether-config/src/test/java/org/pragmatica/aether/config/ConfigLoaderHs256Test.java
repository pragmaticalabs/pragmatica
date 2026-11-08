// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.config;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #2880: `[app-http] jwt_algorithm = "HS256"` selects the shared-secret mode; the secret comes from the environment
/// (`AETHER_JWT_SECRET`, or the file `AETHER_JWT_SECRET_FILE` names), never from the TOML.
class ConfigLoaderHs256Test {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String BASE = """
        [cluster]
        environment = "docker"
        nodes = 3

        [app-http]
        enabled = "true"
        security_mode = "jwt"
        """;
    private static final String HS256 = BASE + "jwt_algorithm = \"HS256\"\n";

    private static Option<JwtConfig> jwt(String toml, Map<String, String> env) {
        return ConfigLoader.loadFromString(toml, env::get)
                           .onFailure(cause -> fail("must load: " + cause.message()))
                           .unwrap()
                           .appHttp()
                           .jwtConfig();
    }

    private static void assertRefused(String toml, Map<String, String> env, String reason) {
        ConfigLoader.loadFromString(toml, env::get)
                    .onSuccess(_ -> fail("must be refused at load"))
                    .onFailure(cause -> assertThat(cause.message()).contains(reason).doesNotContain(SECRET));
    }

    @Test
    void hs256_withSecretInEnvironment_loadsInSharedSecretMode() {
        var config = jwt(HS256, Map.of("AETHER_JWT_SECRET", SECRET)).unwrap();

        assertThat(config.keyMode()).isEqualTo(JwtKeyMode.HS256);
        assertThat(config.hasKey()).isTrue();
        assertThat(config.sharedSecret()).isEqualTo(Option.some(JwtSecret.jwtSecret(SECRET).unwrap()));
        assertThat(config.toString()).doesNotContain(SECRET);
    }

    @Test
    void hs256_withoutSecret_loadsWithoutKey_theNodeStartRefusesIt() {
        var config = jwt(HS256, Map.of()).unwrap();

        assertThat(config.keyMode()).isEqualTo(JwtKeyMode.HS256);
        assertThat(config.hasKey()).isFalse();
    }

    @Test
    void hs256_withShortSecret_isRefusedAtLoad_withoutNamingIt() {
        var env = Map.of("AETHER_JWT_SECRET", "short-secret");

        assertRefused(HS256, env, "too short");
    }

    @Test
    void hs256_withJwksUrl_isRefused() {
        assertRefused(HS256 + "jwks_url = \"https://auth.example.com/jwks.json\"\n", Map.of("AETHER_JWT_SECRET", SECRET), "never reads a JWKS");
    }

    @Test
    void hs256_unknownAlgorithm_isRefused() {
        assertRefused(BASE + "jwt_algorithm = \"HS512\"\n", Map.of("AETHER_JWT_SECRET", SECRET), "is not supported");
    }

    @Test
    void jwksMode_ignoresAnAmbientSecretVariable_andKeepsItsDefaults() {
        var config = jwt(BASE + "jwks_url = \"https://auth.example.com/jwks.json\"\n", Map.of("AETHER_JWT_SECRET", SECRET)).unwrap();

        assertThat(config.keyMode()).isEqualTo(JwtKeyMode.JWKS);
        assertThat(config.sharedSecret().isEmpty()).isTrue();
    }

    @Test
    void jwtConfig_jwksModeWithASecret_isRefused() {
        var secret = JwtSecret.jwtSecret(SECRET).unwrap();

        JwtConfig.jwtConfig("https://auth.example.com/jwks.json",
                            Option.empty(),
                            Option.empty(),
                            "role",
                            3600,
                            30,
                            AuthSchemes.DEFAULT,
                            JwtKeyMode.JWKS,
                            Option.some(secret))
                 .onSuccess(_ -> fail("a secret beside JWKS mode must be refused"))
                 .onFailure(cause -> assertThat(cause.message()).contains("mode is JWKS"));
    }

    @Test
    void preflight_withEmptyEnvironment_acceptsAnHs256DocumentWhoseSecretTheNodeSupplies() {
        assertThat(ConfigLoader.checkNodeDocument(TomlParser.parse(HS256 + "port = 8070\n").unwrap()).isSuccess()).isTrue();
    }
}
