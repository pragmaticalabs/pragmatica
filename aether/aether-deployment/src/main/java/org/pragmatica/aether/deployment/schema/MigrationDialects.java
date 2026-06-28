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
/// PostgreSQL-family dialects (`POSTGRESQL`, `COCKROACHDB`), the MySQL family
/// (`MYSQL`, `MARIADB`), `DB2`, `SQLSERVER`, and `ORACLE` are wired to the dialect-aware
/// [org.pragmatica.aether.pg.split.StatementSplitter]. Every still-unmapped database type
/// (`H2`, `SQLITE`) resolves to [Option#none()], which keeps the legacy naive
/// `split(";")` execution path — guaranteeing zero behavior change for unwired dialects.
///
/// The [ExecutionDialect] descriptor carries a `ddlTransactional` flag: PostgreSQL-family,
/// DB2, and SQL Server are `true` (whole file wrapped in a transaction unless a
/// non-transactional statement forces autocommit), while MySQL/MariaDB and Oracle are `false`
/// because their DDL auto-commits — wrapping it in a transaction is pointless, so the whole file
/// runs in autocommit. The flag lives here, not in the splitter's [DialectSpec], which stays
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
    /// MySQL/MariaDB descriptor: the splitter's MySQL spec, DDL auto-committing
    /// (`ddlTransactional=false`), so the whole migration file runs in autocommit.
    ExecutionDialect MYSQL = new ExecutionDialect(Dialects.MYSQL, false);
    /// DB2 descriptor: the splitter's DB2 spec, DDL-transactional.
    ExecutionDialect DB2 = new ExecutionDialect(Dialects.DB2, true);
    /// SQL Server descriptor: the splitter's SQL Server (T-SQL) spec, DDL-transactional.
    ExecutionDialect SQLSERVER = new ExecutionDialect(Dialects.SQLSERVER, true);

    /// Oracle descriptor: the splitter's Oracle spec, DDL auto-committing
    /// (`ddlTransactional=false`), so the whole migration file runs in autocommit.
    ExecutionDialect ORACLE = new ExecutionDialect(Dialects.ORACLE, false);

    /// Resolves the execution descriptor for a database type.
    ///
    /// @param type the effective database type of the migration's connector
    ///
    /// @return the descriptor for PostgreSQL-family, MySQL-family, DB2, SQL Server, and Oracle
    ///         dialects, or [Option#none()] for every still-unmapped type (`H2`, `SQLITE` —
    ///         legacy naive execution path)
    static Option<ExecutionDialect> dialectFor(DatabaseType type) {
        return switch (type) {
            case POSTGRESQL, COCKROACHDB -> some(POSTGRESQL);
            case MYSQL, MARIADB -> some(MYSQL);
            case DB2 -> some(DB2);
            case SQLSERVER -> some(SQLSERVER);
            case ORACLE -> some(ORACLE);
            case H2, SQLITE -> none();
        };
    }
}
