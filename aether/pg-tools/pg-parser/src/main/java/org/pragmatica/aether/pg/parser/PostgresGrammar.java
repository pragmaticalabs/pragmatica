// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


public final class PostgresGrammar {
    private PostgresGrammar() {}

    public static final String GRAMMAR = loadGrammar().unwrap();

    private static Result<String> loadGrammar() {
        try (var stream = PostgresGrammar.class.getResourceAsStream("postgres.peg")) {
            if (stream == null) {
                return Causes.cause("postgres.peg resource not found").result();
            }

            return Result.success(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Causes.cause("Failed to load postgres.peg: " + e.getMessage()).result();
        }
    }
}
