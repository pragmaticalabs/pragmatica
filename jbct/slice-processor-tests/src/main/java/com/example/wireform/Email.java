// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.wireform;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

/// A value object: travels as its bare string on every route of the slice.
public record Email(String value) {
    public static Result<Email> email(String value) {
        return value.contains("@")
               ? Result.success(new Email(value))
               : Causes.cause("not an email: " + value).result();
    }
}
