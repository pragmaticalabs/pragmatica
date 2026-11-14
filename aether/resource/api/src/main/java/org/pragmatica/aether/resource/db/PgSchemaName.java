// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

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
///
/// **Shape (G1):** a sealed interface whose only implementation, `PgSchemaNameValue`, is package-private, so
/// [#pgSchemaName(String)] is the only way to obtain a value outside this package; no public constructor exists.
public sealed interface PgSchemaName permits PgSchemaNameValue {
    /// The validated schema name, unquoted.
    String value();

    static Result<PgSchemaName> pgSchemaName(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::notNull,
                             PgSchemaNameError.malformed(raw))
                     .flatMap(PgSchemaNameValue::matchGrammar)
                     .flatMap(PgSchemaNameValue::rejectReserved)
                     .map(PgSchemaNameValue::new);
    }

    /// The name as a double-quoted identifier. The grammar admits no quote character, so no escaping is needed.
    default String quoted() {
        return "\"" + value() + "\"";
    }
}
