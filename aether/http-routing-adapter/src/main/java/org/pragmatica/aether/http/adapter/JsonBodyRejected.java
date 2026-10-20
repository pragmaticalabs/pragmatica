// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.pragmatica.lang.Cause;


/// A JSON request body the generated codec refused, naming the target type. Its message reaches the client
/// as the 400's problem `detail`, so it also states the decoder's finding (offset, missing or refused
/// field): that is client input diagnostics, not internals, and matches what the Jackson path reports.
/// The decoder's cause still travels as `origin`.
///
/// @param type    the target type's simple name
/// @param origin  the decoder's failure
/// @param message the client-facing text
public record JsonBodyRejected(String type, Cause origin, String message) implements Cause.Wrapped {
    public static JsonBodyRejected jsonBodyRejected(String type, Cause origin) {
        return new JsonBodyRejected(type, origin, "Invalid JSON body for " + type + ": " + origin.message());
    }
}
