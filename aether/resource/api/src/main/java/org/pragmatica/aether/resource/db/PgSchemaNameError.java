// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;


/// Typed refusals of a PostgreSQL schema name (#2100 E-B): one per rule, so an operator sees which rule the value broke.
public sealed interface PgSchemaNameError extends Cause {
    static PgSchemaNameError malformed(String value) {
        return Malformed.FACTORY.apply(value);
    }

    static PgSchemaNameError reserved(String value) {
        return Reserved.FACTORY.apply(value);
    }

    /// The value is not a lower-case identifier of 1 to 63 characters (`[a-z_][a-z0-9_]{0,62}`), including null, blank,
    /// upper case, quotes, semicolons and anything else outside the grammar.
    record Malformed(String value, String message) implements PgSchemaNameError {
        static final Fn1<Malformed, String> FACTORY = Causes.forOneValue("PostgreSQL schema name '%s' must match [a-z_][a-z0-9_]{0,62}",
                                                                         Malformed::new);
    }

    /// The name is syntactically valid but reserved: `pg_*` and `information_schema` belong to PostgreSQL, `public`
    /// is refused as an entity schema (see [PgSchemaName#pgSchemaName]).
    record Reserved(String value, String message) implements PgSchemaNameError {
        static final Fn1<Reserved, String> FACTORY = Causes.forOneValue("PostgreSQL schema name '%s' is reserved and cannot hold entity tables",
                                                                        Reserved::new);
    }
}
