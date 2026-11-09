// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.http.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.http.security.Hs256TestSupport.*;

/// #2880 fix round: an `alg` header that is PRESENT but empty or blank is refused in every mode. The base refused an
/// explicit empty `alg` in JWKS mode (`Unsupported algorithm`); the first #2880 head mapped it to RS256. A header with
/// no `alg` at all keeps its historical RS256 reading in JWKS mode and is refused in HS256 mode.
class JwtAlgHeaderTest {
    private static final String RS256_CONTROL = "{\"alg\":\"RS256\",\"kid\":\"k1\"}";

    @Test
    void jwksMode_acceptsAValidRs256Token_control() {
        var pair = rsaKeyPair();

        assertThat(accepts(jwksValidator(pair), rs256Token(pair, RS256_CONTROL, payload("")))).isTrue();
    }

    @Test
    void jwksMode_refusesAnExplicitEmptyAlg_evenWithAValidRsaSignature() {
        var pair = rsaKeyPair();
        var token = rs256Token(pair, "{\"alg\":\"\",\"kid\":\"k1\"}", payload(""));

        assertThat(refusal(jwksValidator(pair), token)).isInstanceOf(SecurityError.InvalidCredentials.class);
    }

    @Test
    void jwksMode_refusesABlankAlg_evenWithAValidRsaSignature() {
        var pair = rsaKeyPair();
        var token = rs256Token(pair, "{\"alg\":\"  \",\"kid\":\"k1\"}", payload(""));

        assertThat(refusal(jwksValidator(pair), token)).isInstanceOf(SecurityError.InvalidCredentials.class);
    }

    @Test
    void jwksMode_aHeaderWithNoAlg_keepsItsHistoricalRs256Reading() {
        var pair = rsaKeyPair();

        assertThat(accepts(jwksValidator(pair), rs256Token(pair, "{\"kid\":\"k1\"}", payload("")))).isTrue();
    }

    @Test
    void hs256Mode_control_acceptsAValidHs256Token() {
        assertThat(accepts(hsValidator(), hs256Token("{\"alg\":\"HS256\"}", payload("")))).isTrue();
    }

    @Test
    void hs256Mode_refusesAnExplicitEmptyAlg_evenWithAValidMac() {
        assertThat(refusal(hsValidator(), hs256Token("{\"alg\":\"\"}", payload("")))).isInstanceOf(SecurityError.InvalidCredentials.class);
    }

    @Test
    void hs256Mode_refusesABlankAlg_evenWithAValidMac() {
        assertThat(refusal(hsValidator(), hs256Token("{\"alg\":\" \"}", payload("")))).isInstanceOf(SecurityError.InvalidCredentials.class);
    }
}
