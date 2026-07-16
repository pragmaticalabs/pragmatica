// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge.load.pattern;

import java.util.UUID;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record UuidGenerator() implements PatternGenerator {
    public static final String TYPE = "uuid";

    public static Result<UuidGenerator> uuidGenerator() {
        return success(new UuidGenerator());
    }

    @Override
    public String generate() {
        return UUID.randomUUID().toString();
    }

    @Override
    public String pattern() {
        return "${uuid}";
    }
}
