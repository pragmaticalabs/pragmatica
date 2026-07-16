// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import java.util.List;

import org.pragmatica.aether.resource.db.DatabaseType;


/// Internally-versioned, self-evolving schema for the `aether_schema_history` table (#338-B1).
///
/// The history table carries its OWN internal schema version, tracked in the tiny fixed meta table
/// `aether_schema_history_meta(schema_version INTEGER NOT NULL)`. The table is defined here as an
/// ORDERED list of [Step]s, each gated by the internal version it raises the table TO. `bootstrap`
/// reads the current version (absent ⇒ 0) and runs every step from `version + 1` to [#LATEST_VERSION]
/// exactly once per cluster, then persists [#LATEST_VERSION] — making evolution idempotent and
/// portable WITHOUT relying on `ADD COLUMN IF NOT EXISTS` (which Oracle lacks and SQL Server spells
/// differently). The meta table itself never evolves; only the history table does.
///
/// Step model:
/// - **v1** ([#stepOne]) — the original 8-column `CREATE TABLE aether_schema_history (...)`, kept
///   `IF NOT EXISTS` so an EXISTING cluster's table is a no-op while a fresh cluster gets it created.
///   Dialect-independent.
/// - **v2** ([#stepTwo]) — adds `status` and `statements_completed` via DIALECT-AWARE `ALTER TABLE`
///   syntax keyed off [DatabaseType]. Because the meta-version gates this step (run exactly once),
///   the `ALTER` does NOT need `IF NOT EXISTS`.
///
/// Real-engine validation posture (mirrors #337's tiered validation): the v2 `ALTER` is validated
/// against a real engine only for PostgreSQL (and, by shared syntax, MySQL/MariaDB) via the
/// Testcontainers suite. The Oracle/DB2 (`ADD (col …, col …)`) and SQL Server (`ADD col …, col …`)
/// branches are correct by construction and unit-tested for the exact SQL string, but remain
/// UNVALIDATED against a real Oracle/DB2/SQL Server engine until container coverage exists.
public sealed interface SchemaHistoryEvolution {
    record unused() implements SchemaHistoryEvolution {}

    /// The latest internal schema version of the `aether_schema_history` table — the version every
    /// cluster converges to after [SchemaHistoryRepository#bootstrap].
    int LATEST_VERSION = 2;

    /// The fixed single-row meta table tracking the history table's own internal schema version.
    /// Created `IF NOT EXISTS` and never itself evolved.
    String META_TABLE = "aether_schema_history_meta";

    /// One ordered evolution step. `targetVersion` is the internal version this step raises the
    /// history table TO; `ddl` renders the dialect-aware statements (one or more) that perform it.
    ///
    /// @param targetVersion the internal version reached after this step runs
    /// @param ddl           the dialect-aware DDL statement list for a given [DatabaseType]
    record Step(int targetVersion, DdlRenderer ddl) {
        static Step step(int targetVersion, DdlRenderer ddl) {
            return new Step(targetVersion, ddl);
        }
    }

    /// Renders the dialect-aware DDL statements for a step against a concrete [DatabaseType].
    @FunctionalInterface
    interface DdlRenderer {
        List<String> render(DatabaseType type);
    }

    /// v1: the original 8-column history table, created `IF NOT EXISTS` (dialect-independent).
    Step STEP_V1 = Step.step(1, SchemaHistoryEvolution::stepOne);
    /// v2: add `status` + `statements_completed`, dialect-aware (#338-B2 consumes these columns).
    Step STEP_V2 = Step.step(2, SchemaHistoryEvolution::stepTwo);

    /// The ordered list of evolution steps, ascending by target version.
    List<Step> STEPS = List.of(STEP_V1, STEP_V2);

    /// Selects the steps that move the history table from `currentVersion` up to [#LATEST_VERSION].
    /// A cluster already at the latest version selects an empty list (no DDL runs).
    ///
    /// @param currentVersion the current internal schema version (0 when no meta row exists)
    ///
    /// @return the ordered sublist of steps whose `targetVersion` is `> currentVersion`
    static List<Step> stepsFrom(int currentVersion) {
        return STEPS.stream()
                    .filter(step -> step.targetVersion() > currentVersion)
                    .toList();
    }

    private static List<String> stepOne(DatabaseType type) {
        return List.of("""
            CREATE TABLE IF NOT EXISTS aether_schema_history (
                version        INTEGER      NOT NULL,
                type           VARCHAR(16)  NOT NULL DEFAULT 'VERSIONED',
                description    VARCHAR(256) NOT NULL,
                script         VARCHAR(512) NOT NULL,
                checksum       BIGINT       NOT NULL,
                applied_by     VARCHAR(128) NOT NULL,
                applied_at     BIGINT       NOT NULL,
                execution_ms   INTEGER      NOT NULL,
                PRIMARY KEY (version, type)
            )""");
    }

    /// Renders the v2 `ALTER TABLE aether_schema_history ADD ...` adding `status` and
    /// `statements_completed` in the syntax flavor each engine family requires:
    /// - PostgreSQL-family / MySQL-family: two separate `ALTER TABLE ... ADD COLUMN ...` statements
    ///   (the most broadly portable spelling, accepted by PG/CockroachDB and MySQL/MariaDB).
    /// - Oracle / DB2: a single `ALTER TABLE ... ADD (col1 ..., col2 ...)` (parenthesized group).
    /// - SQL Server: a single `ALTER TABLE ... ADD col1 ..., col2 ...` (no `COLUMN` keyword, no parens).
    /// - H2 / SQLite (unwired execution dialects): use the portable two-`ADD COLUMN` spelling, which
    ///   both accept — keeping the meta-gated evolution path usable everywhere the history table runs.
    private static List<String> stepTwo(DatabaseType type) {
        return switch (type) {
            case ORACLE, DB2 -> List.of("ALTER TABLE aether_schema_history ADD (" + "status VARCHAR(16) DEFAULT 'SUCCESS' NOT NULL, " + "statements_completed INTEGER DEFAULT 0 NOT NULL)");
            case SQLSERVER -> List.of("ALTER TABLE aether_schema_history ADD " + "status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS', " + "statements_completed INTEGER NOT NULL DEFAULT 0");
            case POSTGRESQL, COCKROACHDB, MYSQL, MARIADB, H2, SQLITE -> List.of("ALTER TABLE aether_schema_history ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS'",
                                                                                "ALTER TABLE aether_schema_history ADD COLUMN statements_completed INTEGER NOT NULL DEFAULT 0");
        };
    }
}
