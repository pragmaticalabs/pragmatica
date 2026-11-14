// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Cause;


/// Typed refusals of a PostgreSQL schema name (#2100 E-B): one per rule, so an operator sees which rule the value broke.
public sealed interface PgSchemaNameError extends Cause {
    static PgSchemaNameError malformed(String value) {
        return new Malformed(value);
    }

    static PgSchemaNameError reserved(String value) {
        return new Reserved(value);
    }

    /// The value is not a lower-case identifier of 1 to 63 characters (`[a-z_][a-z0-9_]{0,62}`), including null, blank,
    /// upper case, quotes, semicolons and anything else outside the grammar.
    record Malformed(String value) implements PgSchemaNameError {
        @Override
        public String message() {
            return "PostgreSQL schema name '" + value + "' must match [a-z_][a-z0-9_]{0,62}";
        }
    }

    /// The name is syntactically valid but reserved: `pg_*` and `information_schema` belong to PostgreSQL, `public`
    /// is refused as an entity schema (see [PgSchemaName#pgSchemaName]).
    record Reserved(String value) implements PgSchemaNameError {
        @Override
        public String message() {
            return "PostgreSQL schema name '" + value + "' is reserved and cannot hold entity tables";
        }
    }
}
