// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// A PostgreSQL schema name that is safe to splice into DDL (#2100 E-B, design §c.3).
///
/// Grammar `[a-z_][a-z0-9_]{0,62}`: lower case only, at most 63 characters (PostgreSQL's identifier limit), no quote,
/// semicolon or whitespace can ever reach a statement. [#quoted()] is the only form a renderer emits, always
/// double-quoted.
///
/// Refused as reserved: `pg_*` and `information_schema` (PostgreSQL's own), and `public`.
///
/// **CTO guess, pending the owner (OPEN 2):** `public` is REFUSED, so an entity table never shares a history or owner
/// table with the user schema. It is one rule (`RESERVED_EXACT`) and one test row; flipping it is a one-line change.
public record PgSchemaName(String value) {
    private static final Pattern GRAMMAR = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final String RESERVED_PREFIX = "pg_";
    private static final Set<String> RESERVED_EXACT = Set.of("information_schema", "public");

    public static Result<PgSchemaName> pgSchemaName(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::notNull,
                             PgSchemaNameError.malformed(raw))
                     .flatMap(PgSchemaName::matchGrammar)
                     .flatMap(PgSchemaName::rejectReserved)
                     .map(PgSchemaName::new);
    }

    /// The name as a double-quoted identifier. The grammar admits no quote character, so no escaping is needed.
    public String quoted() {
        return "\"" + value + "\"";
    }

    private static Result<String> matchGrammar(String raw) {
        return Verify.ensure(raw, Verify.Is::matches, GRAMMAR, PgSchemaNameError.malformed(raw));
    }

    private static Result<String> rejectReserved(String name) {
        return Verify.ensure(name, PgSchemaName::isUnreserved, PgSchemaNameError.reserved(name));
    }

    private static boolean isUnreserved(String name) {
        return ! name.startsWith(RESERVED_PREFIX) && !RESERVED_EXACT.contains(name);
    }
}
