// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.config;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #2156: the one `auth_schemes` rule both runtimes apply, and the `JwtConfig` forms that carry it.
class AuthSchemesTest {
    private static final String JWKS = "https://auth.example.com/.well-known/jwks.json";

    @Test
    void authSchemes_validList_isKeptInOrder() {
        AuthSchemes.authSchemes(List.of("Token", "Bearer", "DPoP"))
                   .onFailure(cause -> fail(cause.message()))
                   .onSuccess(schemes -> assertThat(schemes).containsExactly("Token", "Bearer", "DPoP"));
    }

    @Test
    void authSchemes_result_isACopy_unaffectedByLaterChangesToTheInput() {
        var input = new ArrayList<>(List.of("Bearer"));
        var schemes = AuthSchemes.authSchemes(input).unwrap();

        input.add("Token");

        assertThat(schemes).containsExactly("Bearer");
    }

    @Test
    void authSchemes_empty_isRefused() {
        assertRefused(List.of(), "auth_schemes is empty");
    }

    @Test
    void authSchemes_duplicate_isRefused() {
        assertRefused(List.of("Token", "Token"), "names 'Token' more than once");
    }

    @Test
    void authSchemes_caseOnlyDuplicate_isRefused() {
        assertRefused(List.of("Bearer", "BEARER"), "names 'BEARER' more than once");
    }

    @Test
    void authSchemes_blankOrNonTokenEntry_isRefused() {
        for (var entry : List.of("", " ", "Bearer x", "Bear:er", "Bearer\t")) {
            assertRefused(List.of("Bearer", entry), "is not an RFC 7235 auth-scheme token");
        }
    }

    @Test
    void jwtConfig_shortForms_defaultToBearerOnly_andChallengeBearer() {
        var config = JwtConfig.jwtConfig(JWKS).unwrap();

        assertThat(config.authSchemes()).containsExactly("Bearer");
        assertThat(config.challengeScheme()).isEqualTo("Bearer");
    }

    @Test
    void jwtConfig_challengeScheme_isTheFirstConfigured() {
        var config = JwtConfig.jwtConfig(JWKS,
                                         Option.empty(),
                                         Option.empty(),
                                         "role",
                                         3600,
                                         30,
                                         List.of("Token", "Bearer"))
                              .unwrap();

        assertThat(config.challengeScheme()).isEqualTo("Token");
    }

    @Test
    void jwtConfig_invalidSchemes_isRefused() {
        JwtConfig.jwtConfig(JWKS, Option.empty(), Option.empty(), "role", 3600, 30, List.of())
                 .onSuccess(_ -> fail("an empty auth_schemes must refuse the JwtConfig"));
    }

    private static void assertRefused(List<String> schemes, String expectedReason) {
        AuthSchemes.authSchemes(schemes)
                   .onSuccess(_ -> fail("must be refused: " + schemes))
                   .onFailure(cause -> assertThat(cause.message()).contains(expectedReason));
    }
}
