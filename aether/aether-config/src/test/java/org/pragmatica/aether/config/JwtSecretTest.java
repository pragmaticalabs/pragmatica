// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #2880: the HS256 secret is at least 256 bits (RFC 7518 section 3.2) and never printable.
class JwtSecretTest {
    private static final String THIRTY_TWO = "0123456789abcdef0123456789abcdef";

    @Test
    void jwtSecret_acceptsExactlyThirtyTwoBytes() {
        assertThat(JwtSecret.jwtSecret(THIRTY_TWO).isSuccess()).isTrue();
    }

    @Test
    void jwtSecret_refusesThirtyOneBytes_namingTheLengthButNotTheValue() {
        var short31 = THIRTY_TWO.substring(1);

        JwtSecret.jwtSecret(short31)
                 .onSuccess(_ -> fail("31 bytes must be refused"))
                 .onFailure(cause -> assertThat(cause.message()).contains("31 bytes").contains("32").doesNotContain(short31));
    }

    @Test
    void jwtSecret_countsBytesNotCharacters() {
        // 16 two-byte characters are 32 bytes
        assertThat(JwtSecret.jwtSecret("é".repeat(16)).isSuccess()).isTrue();
        assertThat(JwtSecret.jwtSecret("é".repeat(15)).isFailure()).isTrue();
    }

    @Test
    void toString_neverPrintsTheSecret() {
        assertThat(JwtSecret.jwtSecret(THIRTY_TWO).unwrap().toString()).doesNotContain(THIRTY_TWO).contains("redacted");
    }

    @Test
    void value_isADefensiveCopy() {
        var secret = JwtSecret.jwtSecret(THIRTY_TWO).unwrap();

        secret.value()[0] = 0;

        assertThat(secret.value()[0]).isEqualTo((byte) '0');
    }

    @Test
    void equals_comparesContent() {
        assertThat(JwtSecret.jwtSecret(THIRTY_TWO).unwrap()).isEqualTo(JwtSecret.jwtSecret(THIRTY_TWO).unwrap());
        assertThat(JwtSecret.jwtSecret(THIRTY_TWO).unwrap()).isNotEqualTo(JwtSecret.jwtSecret(THIRTY_TWO.toUpperCase()).unwrap());
    }
}
