// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.http;

import java.util.Map;

import org.pragmatica.aether.http.security.SecurityError;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;


/// A 401 refusal (#2926): carries its `WWW-Authenticate` challenge, when the authenticator names a scheme, and a
/// client-safe detail. The client body never says which check failed (signature, issuer, key id, ...); the origin
/// stays below the detail in the cause chain, for the server log only.
record TerraAuthenticationRefusal(Cause origin, Option<String> challenge) implements HttpError {
    private static final String REFUSED_DETAIL = "Invalid credentials";

    static TerraAuthenticationRefusal refusal(Cause origin, Option<String> challenge) {
        return new TerraAuthenticationRefusal(origin, challenge);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNAUTHORIZED;
    }

    @Override
    public Map<String, String> responseHeaders() {
        return challenge.map(value -> Map.of("WWW-Authenticate", value))
                        .or(Map.of());
    }

    @Override
    public String message() {
        return status().message() + ": " + origin.message();
    }

    @Override
    public Option<Cause> source() {
        return Option.some(new ClientDetail(clientText(), origin));
    }

    private String clientText() {
        return origin instanceof SecurityError.MissingCredentials missing
               ? missing.message()
               : REFUSED_DETAIL;
    }

    private record ClientDetail(String message, Cause origin) implements Cause {
        @Override
        public Option<Cause> source() {
            return Option.some(origin);
        }
    }
}
