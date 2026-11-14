// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.util.List;

import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PgSchemaName;
import org.pragmatica.lang.Option;


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
/// - **v1** ([#stepOne]) — the original 8-column `CREATE TABLE aether_schema_history (...)`, created only
///   when absent (`IF NOT EXISTS`, the `OBJECT_ID` guard on SQL Server, the ORA-00955 block on Oracle) so an EXISTING cluster's table is
///   a no-op while a fresh cluster gets it created.
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

    /// The fixed single-row claim table naming the blueprint (`group:artifact`, version stripped)
    /// that owns migration of THIS physical database. Created `IF NOT EXISTS` and, like
    /// [#META_TABLE], never itself evolved — a new column would silently skip every existing
    /// cluster, whose `IF NOT EXISTS` create is a no-op.
    ///
    /// It exists because `aether_schema_history` is UNQUALIFIED: there is exactly one history table
    /// per PHYSICAL database, so two node config sections (`[database.a]`, `[database.b]`) pointing
    /// at the same database would let two blueprints interleave unrelated version sequences in one
    /// shared history. The publish-time gate in `BlueprintService.ensureMigrationOwnership` compares
    /// datasource NAMES and cannot see that, since the physical target is a node-local config
    /// resolution known only at migration time. This claim therefore lives IN the database being
    /// migrated, where the physical identity is the table's own location.
    String OWNER_TABLE = "aether_schema_owner";

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

    /// Renders the dialect-aware DDL statements for a step against a concrete [DatabaseType], optionally scoped to a
    /// PostgreSQL schema (#2100 E-B): with a schema the history table is `"<schema>".aether_schema_history`, with
    /// `none` every statement is byte-identical to the unscoped form (pinned by `SchemaHistoryStatementsFixtureTest`).
    @FunctionalInterface
    interface DdlRenderer {
        List<String> render(DatabaseType type, Option<PgSchemaName> schema);

        default List<String> render(DatabaseType type) {
            return render(type, Option.none());
        }
    }

    /// The history table's own name, before any schema qualification.
    String HISTORY_TABLE = "aether_schema_history";

    /// v1: the original 8-column history table, created only when absent (dialect-aware guard).
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

    /// `table` as it appears in a statement: unchanged with no schema, `"<schema>".table` with one. The quoted form is
    /// the only spelling [PgSchemaName] offers, so no caller can splice an unvalidated name.
    static String qualified(Option<PgSchemaName> schema, String table) {
        return schema.fold(() -> table, name -> name.quoted() + "." + table);
    }

    /// The statement that tests whether `schema` exists; it takes the schema name as its one parameter.
    static String schemaExistsSql() {
        return "SELECT 1 AS present FROM pg_namespace WHERE nspname = ?";
    }

    /// The statement that creates `schema` when absent.
    static String createSchemaSql(PgSchemaName schema) {
        return "CREATE SCHEMA IF NOT EXISTS " + schema.quoted();
    }

    /// `CREATE TABLE` that is a no-op when the table exists. SQL Server has no `IF NOT EXISTS` for tables (#3050), so it
    /// gets the `OBJECT_ID` guard. Oracle gets it only from 23ai, so earlier releases (19c+) get a PL/SQL block that
    /// swallows ORA-00955 (name already used) and re-raises anything else; every other engine takes `IF NOT EXISTS`.
    private static String createTableIfAbsent(DatabaseType type, String table, String columns) {
        var create = "CREATE TABLE " + table + " (" + columns + ")";

        return switch (type) {
            case SQLSERVER -> "IF OBJECT_ID(N'" + table + "', N'U') IS NULL " + create;
            case ORACLE -> "BEGIN EXECUTE IMMEDIATE q'[" + create + "]'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;";
            default -> "CREATE TABLE IF NOT EXISTS " + table + " (" + columns + ")";
        };
    }

    /// The dialect's `CREATE TABLE ... IF absent` for [#META_TABLE].
    static String createMetaTableSql(DatabaseType type) {
        return createMetaTableSql(type, Option.none());
    }

    /// The dialect's `CREATE TABLE ... IF absent` for [#META_TABLE] in `schema` (unqualified with `none`).
    static String createMetaTableSql(DatabaseType type, Option<PgSchemaName> schema) {
        return createTableIfAbsent(type, qualified(schema, META_TABLE), "schema_version INTEGER NOT NULL");
    }

    /// The dialect's `CREATE TABLE ... IF absent` for [#OWNER_TABLE].
    static String createOwnerTableSql(DatabaseType type) {
        return createOwnerTableSql(type, Option.none());
    }

    /// The dialect's `CREATE TABLE ... IF absent` for [#OWNER_TABLE] in `schema` (unqualified with `none`).
    static String createOwnerTableSql(DatabaseType type, Option<PgSchemaName> schema) {
        return createTableIfAbsent(type, qualified(schema, OWNER_TABLE), "blueprint_base VARCHAR(256) NOT NULL");
    }

    /// `DEFAULT` precedes `NOT NULL` (#3069): Oracle refuses the reverse with ORA-03076, every other engine accepts both.
    /// The 64-bit columns use [#bigintType] (found by the live Oracle IT: `BIGINT` is ORA-00902).
    private static List<String> stepOne(DatabaseType type, Option<PgSchemaName> schema) {
        return List.of(createTableIfAbsent(type,
                                           qualified(schema, HISTORY_TABLE),
                                           """

                version        INTEGER      NOT NULL,
                type           VARCHAR(16)  DEFAULT 'VERSIONED' NOT NULL,
                description    VARCHAR(256) NOT NULL,
                script         VARCHAR(512) NOT NULL,
                checksum       %1$s       NOT NULL,
                applied_by     VARCHAR(128) NOT NULL,
                applied_at     %1$s       NOT NULL,
                execution_ms   INTEGER      NOT NULL,
                PRIMARY KEY (version, type)
            """.formatted(bigintType(type))));
    }

    /// Oracle has no `BIGINT` (ORA-00902); its 64-bit integer is `NUMBER(19)`.
    private static String bigintType(DatabaseType type) {
        return type == DatabaseType.ORACLE
               ? "NUMBER(19)"
               : "BIGINT";
    }

    /// Renders the v2 `ALTER TABLE aether_schema_history ADD ...` adding `status` and
    /// `statements_completed` in the syntax flavor each engine family requires:
    /// - PostgreSQL-family / MySQL-family: two separate `ALTER TABLE ... ADD COLUMN ...` statements
    ///   (the most broadly portable spelling, accepted by PG/CockroachDB and MySQL/MariaDB).
    /// - Oracle / DB2: a single `ALTER TABLE ... ADD (col1 ..., col2 ...)` (parenthesized group).
    /// - SQL Server: a single `ALTER TABLE ... ADD col1 ..., col2 ...` (no `COLUMN` keyword, no parens).
    /// - H2 / SQLite (unwired execution dialects): use the portable two-`ADD COLUMN` spelling, which
    ///   both accept — keeping the meta-gated evolution path usable everywhere the history table runs.
    private static List<String> stepTwo(DatabaseType type, Option<PgSchemaName> schema) {
        var table = qualified(schema, HISTORY_TABLE);

        return switch (type) {
            case ORACLE, DB2 -> List.of("ALTER TABLE " + table + " ADD (" + "status VARCHAR(16) DEFAULT 'SUCCESS' NOT NULL, " + "statements_completed INTEGER DEFAULT 0 NOT NULL)");
            case SQLSERVER -> List.of("ALTER TABLE " + table + " ADD " + "status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS', " + "statements_completed INTEGER NOT NULL DEFAULT 0");
            case POSTGRESQL, COCKROACHDB, MYSQL, MARIADB, H2, SQLITE -> List.of("ALTER TABLE " + table + " ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS'",
                                                                                "ALTER TABLE " + table + " ADD COLUMN statements_completed INTEGER NOT NULL DEFAULT 0");
        };
    }
}
