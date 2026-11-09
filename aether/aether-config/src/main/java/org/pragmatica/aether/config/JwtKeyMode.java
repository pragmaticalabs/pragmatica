// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;


/// How JWT mode verifies a token signature (#2880). The CONFIGURED mode picks the algorithm family; the token header
/// never does: a token whose `alg` disagrees with the mode is refused before any key is looked up.
public enum JwtKeyMode {
    /// RS256/384/512 and ES256/384/512 against the keys a JWKS endpoint publishes. The default.
    JWKS,
    /// HS256 against one configured shared secret. The JWKS is never consulted.
    HS256;
    /// The mode named by a `jwt_algorithm` / `[jwt] algorithm` value (case-insensitive); only `HS256` is a choice,
    /// since the JWKS mode is selected by not naming one.
    public static Option<JwtKeyMode> jwtKeyMode(String value) {
        return Option.option(value)
                     .map(String::trim)
                     .filter("hs256"::equalsIgnoreCase)
                     .map(_ -> HS256);
    }
}
