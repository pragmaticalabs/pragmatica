// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import org.pragmatica.aether.pg.split.DialectSpec;
import org.pragmatica.aether.pg.split.Dialects;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Maps a runtime [DatabaseType] to the migration execution descriptor that governs how
/// [AetherSchemaManager] splits and wraps a migration file.
///
/// Only PostgreSQL-family dialects (`POSTGRESQL`, `COCKROACHDB`) are wired to the
/// dialect-aware [org.pragmatica.aether.pg.split.StatementSplitter]. Every other database
/// type resolves to [Option#none()], which keeps the legacy naive `split(";")` execution
/// path — guaranteeing zero behavior change for unwired dialects.
///
/// The [ExecutionDialect] descriptor carries a `ddlTransactional` flag so that autocommit-DDL
/// dialects (MySQL, Oracle) slot in later by populating the table with `ddlTransactional=false`
/// — no engine rework. The flag lives here, not in the splitter's [DialectSpec], which stays
/// purely lexical.
public sealed interface MigrationDialects {
    record unused() implements MigrationDialects {}

    /// Per-dialect migration execution descriptor.
    ///
    /// @param spec            the lexical dialect descriptor driving the splitter
    /// @param ddlTransactional whether this dialect runs DDL inside a transaction by default;
    ///                         PostgreSQL-family is `true`, autocommit-DDL dialects are `false`
    record ExecutionDialect(DialectSpec spec, boolean ddlTransactional) {}

    /// PostgreSQL-family descriptor: the splitter's PostgreSQL spec, DDL-transactional.
    ExecutionDialect POSTGRESQL = new ExecutionDialect(Dialects.POSTGRESQL, true);

    /// Resolves the execution descriptor for a database type.
    ///
    /// @param type the effective database type of the migration's connector
    ///
    /// @return the descriptor for PostgreSQL-family dialects, or [Option#none()] for every
    ///         other type (legacy naive execution path)
    static Option<ExecutionDialect> dialectFor(DatabaseType type) {
        return switch (type) {
            case POSTGRESQL, COCKROACHDB -> some(POSTGRESQL);
            case MYSQL, MARIADB, H2, SQLITE, ORACLE, SQLSERVER, DB2 -> none();
        };
    }
}
