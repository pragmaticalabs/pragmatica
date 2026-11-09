// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.http.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.http.security.Hs256TestSupport.*;

/// #2880 fix round: `nbf` and `iat` are enforced in every mode (the base enforced `exp`, `iss` and `aud` only). The
/// clock-skew allowance (30 s here) applies to both as it does to `exp`; a non-numeric value is a refusal, never an
/// exception.
class JwtTimeClaimsTest {
    private static final String HS256 = "{\"alg\":\"HS256\"}";
    private static final String RS256 = "{\"alg\":\"RS256\",\"kid\":\"k1\"}";

    private static boolean hsAccepts(String extraClaims) {
        return accepts(hsValidator(), hs256Token(HS256, payload(extraClaims)));
    }

    @Test
    void hs256_acceptsTokensWithoutNbfAndIat_andWithTheirPastValues() {
        assertThat(hsAccepts("")).isTrue();
        assertThat(hsAccepts(",\"nbf\":" + (now() - 60) + ",\"iat\":" + (now() - 60))).isTrue();
    }

    @Test
    void hs256_acceptsNbfAndIatInsideTheSkewAllowance() {
        assertThat(hsAccepts(",\"nbf\":" + (now() + 10) + ",\"iat\":" + (now() + 10))).isTrue();
    }

    @Test
    void hs256_refusesNbfInTheFuture_asNotYetValid() {
        assertThat(refusal(hsValidator(), hs256Token(HS256, payload(",\"nbf\":" + (now() + 86400))))).isInstanceOf(SecurityError.TokenNotYetValid.class);
    }

    @Test
    void hs256_refusesNbfJustBeyondTheSkew() {
        assertThat(hsAccepts(",\"nbf\":" + (now() + 120))).isFalse();
    }

    @Test
    void hs256_refusesIatInTheFuture() {
        assertThat(refusal(hsValidator(), hs256Token(HS256, payload(",\"iat\":" + (now() + 86400))))).isInstanceOf(SecurityError.InvalidCredentials.class);
    }

    @Test
    void hs256_refusesIatJustBeyondTheSkew() {
        assertThat(hsAccepts(",\"iat\":" + (now() + 120))).isFalse();
    }

    private static final long SKEW = 30;

    /// Whether a token whose `claim` sits `offset` seconds from now is accepted. The boundary is exact to the second, so
    /// the answer counts only when the clock did not tick between building the token and validating it.
    private static boolean acceptsAtOffset(String claim, long offset) {
        while (true) {
            var before = now();
            var accepted = hsAccepts(",\"" + claim + "\":" + (before + offset));

            if (now() == before) {
                return accepted;
            }
        }
    }

    @Test
    void hs256_nbfAtExactlyTheSkewBoundary_isAccepted_oneSecondBeyondIsRefused() {
        assertThat(acceptsAtOffset("nbf", SKEW)).isTrue();
        assertThat(acceptsAtOffset("nbf", SKEW + 1)).isFalse();
    }

    @Test
    void hs256_iatAtExactlyTheSkewBoundary_isAccepted_oneSecondBeyondIsRefused() {
        assertThat(acceptsAtOffset("iat", SKEW)).isTrue();
        assertThat(acceptsAtOffset("iat", SKEW + 1)).isFalse();
    }

    @Test
    void hs256_nonNumericTimeClaims_areRefusedNotThrown() {
        assertThat(refusal(hsValidator(), hs256Token(HS256, payload(",\"nbf\":\"soon\"")))).isInstanceOf(SecurityError.InvalidCredentials.class);
        assertThat(refusal(hsValidator(), hs256Token(HS256, payload(",\"iat\":true")))).isInstanceOf(SecurityError.InvalidCredentials.class);
        assertThat(refusal(hsValidator(), hs256Token(HS256, "{\"sub\":\"svc-1\",\"exp\":\"never\"}"))).isInstanceOf(SecurityError.InvalidCredentials.class);
    }

    @Test
    void jwks_enforcesNbfAndIatToo() {
        var pair = rsaKeyPair();
        var validator = jwksValidator(pair);

        assertThat(accepts(validator, rs256Token(pair, RS256, payload(",\"nbf\":" + (now() - 60) + ",\"iat\":" + (now() - 60))))).isTrue();
        assertThat(refusal(validator, rs256Token(pair, RS256, payload(",\"nbf\":" + (now() + 86400))))).isInstanceOf(SecurityError.TokenNotYetValid.class);
        assertThat(refusal(validator, rs256Token(pair, RS256, payload(",\"iat\":" + (now() + 86400))))).isInstanceOf(SecurityError.InvalidCredentials.class);
    }
}
