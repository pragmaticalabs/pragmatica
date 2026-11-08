// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.http;

import java.util.Map;

import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;


/// A 401 refusal that carries its `WWW-Authenticate` challenge (#2926). The client body stays the status text; the
/// origin is for the server log only.
record TerraAuthenticationRefusal(Cause origin, String challenge) implements HttpError {
    static TerraAuthenticationRefusal refusal(Cause origin, String challenge) {
        return new TerraAuthenticationRefusal(origin, challenge);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNAUTHORIZED;
    }

    @Override
    public Map<String, String> responseHeaders() {
        return Map.of("WWW-Authenticate", challenge);
    }

    @Override
    public String message() {
        return status().message() + ": " + origin.message();
    }

    @Override
    public Option<Cause> source() {
        return Option.some(origin);
    }
}
