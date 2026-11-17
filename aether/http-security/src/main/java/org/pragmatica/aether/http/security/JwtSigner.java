// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.security;

import java.time.Clock;

import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.config.JwtSecret;
import org.pragmatica.lang.Result;


/// Issues HS256 login tokens (#2895) the HS256 verifier of #2880 accepts. It is built from the SAME [JwtConfig] the
/// verifier reads: the shared secret, the issuer and the audience, so a token is valid for the verifier configured
/// beside it by construction.
///
/// Guarantees per operation: [#sign] yields a compact JWS with header `{"alg":"HS256","typ":"JWT"}` and claims `sub`,
/// `iat` (the clock now), `exp` (now plus the lifetime), `iss` and `aud` when the config names them, plus the caller's
/// extra claims; the MAC is HMAC-SHA256 over the signed content with the shared secret, the primitive the verifier
/// checks with. Every failure is a [SecurityError], the vocabulary the verifier uses. A token is NOT revocable: it
/// stays valid until `exp`, and the signer holds ONE secret (no `kid`, no key rotation).
///
/// The only way to obtain a signer is [#jwtSigner], which refuses JWKS mode and a missing secret; a secret shorter than
/// [JwtSecret#MIN_BYTES] is refused here too, since a [JwtSecret]'s own constructor is public and cannot enforce it.
public interface JwtSigner {
    /// The compact JWS for `claims`, or a [SecurityError] when the claims cannot be serialized or the MAC cannot be
    /// computed.
    Result<String> sign(JwtClaims claims);

    /// A signer on the system clock; see [#jwtSigner(JwtConfig, Clock)].
    static Result<JwtSigner> jwtSigner(JwtConfig config) {
        return jwtSigner(config, Clock.systemUTC());
    }

    /// A signer for an HS256-mode config that carries its secret, or a [SecurityError]: signing under JWKS mode, or
    /// without a secret, is refused here instead of producing a token nothing would accept.
    static Result<JwtSigner> jwtSigner(JwtConfig config, Clock clock) {
        return JwtSigning.requireHs256(config)
                         .flatMap(_ -> config.sharedSecret()
                                             .toResult(JwtSigning.NO_SECRET))
                         .flatMap(JwtSigning::requireLongEnough)
                         .map(secret -> claims -> JwtSigning.sign(config, secret, clock, claims));
    }
}
