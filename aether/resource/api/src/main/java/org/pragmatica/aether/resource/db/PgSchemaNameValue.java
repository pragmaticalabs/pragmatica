// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// The sole implementation of [PgSchemaName]. Package-private on purpose: instances come only from
/// [PgSchemaName#pgSchemaName(String)], which validates (#3241 G1).
record PgSchemaNameValue(String value) implements PgSchemaName {
    private static final Pattern GRAMMAR = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final String RESERVED_PREFIX = "pg_";
    private static final Set<String> RESERVED_EXACT = Set.of("information_schema", "public");

    static Result<String> matchGrammar(String raw) {
        return Verify.ensure(raw, Verify.Is::matches, GRAMMAR, PgSchemaNameError.malformed(raw));
    }

    static Result<String> rejectReserved(String name) {
        return Verify.ensure(name, PgSchemaNameValue::isUnreserved, PgSchemaNameError.reserved(name));
    }

    private static boolean isUnreserved(String name) {
        return !name.startsWith(RESERVED_PREFIX) && !RESERVED_EXACT.contains(name);
    }
}
