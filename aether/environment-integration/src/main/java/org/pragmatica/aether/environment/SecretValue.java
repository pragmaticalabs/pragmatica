// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.time.Instant;

import static org.pragmatica.lang.Result.success;


public record SecretValue(String value, Option<String> version, Option<Instant> expiresAt) {
    public static Result<SecretValue> secretValue(String value) {
        return success(new SecretValue(value, Option.empty(), Option.empty()));
    }
}
