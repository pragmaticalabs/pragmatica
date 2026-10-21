// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.wireform;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// A value object: bare string when nested in a body or response, the object of its component as a whole one.
public record Email(String value) {
    public static Result<Email> email(String value) {
        return value.contains("@")
               ? Result.success(new Email(value))
               : Causes.cause("not an email: " + value).result();
    }
}
