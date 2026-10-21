// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.wireform;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


/// Mirrors the test blueprints' `PublishRequest`: a one-component record with a factory is a value object, so as a
/// whole request body it is the object of its component (`{"payload":"..."}`), and bare inside a collection body.
public record PublishRequest(String payload) {
    public static Result<PublishRequest> publishRequest(String payload) {
        return payload.isBlank()
               ? cause("blank payload").result()
               : success(new PublishRequest(payload));
    }
}
