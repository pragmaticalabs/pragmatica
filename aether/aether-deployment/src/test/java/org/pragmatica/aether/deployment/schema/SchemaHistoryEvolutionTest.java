// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pragmatica.aether.resource.db.DatabaseType;

import static org.assertj.core.api.Assertions.assertThat;

/// Pure unit test for the internally-versioned, self-evolving history-table schema (#338-B1):
/// asserts the ordered step selection by current internal version and the dialect-aware v2 `ALTER`
/// SQL chosen per [DatabaseType]. No I/O, no container — these are the by-construction guarantees
/// that hold for every one of the 9 dialects, including those without real-engine container coverage.
class SchemaHistoryEvolutionTest {

    @Nested
    class StepSelection {

        /// A fresh cluster (no meta row ⇒ version 0) runs BOTH steps: create-table (v1) then the
        /// add-columns ALTER (v2).
        @Test
        void stepsFrom_runsV1AndV2_forFreshClusterAtVersion0() {
            var steps = SchemaHistoryEvolution.stepsFrom(0);

            assertThat(steps).hasSize(2);
            assertThat(steps.get(0).targetVersion()).isEqualTo(1);
            assertThat(steps.get(1).targetVersion()).isEqualTo(2);
        }

        /// An existing cluster already at v1 (8-col table present) runs only the v2 add-columns step.
        @Test
        void stepsFrom_runsOnlyV2_forExistingClusterAtVersion1() {
            var steps = SchemaHistoryEvolution.stepsFrom(1);

            assertThat(steps).hasSize(1);
            assertThat(steps.getFirst().targetVersion()).isEqualTo(2);
        }

        /// A cluster already at the latest version runs NO steps — the evolution is idempotent.
        @Test
        void stepsFrom_runsNothing_forClusterAtLatestVersion() {
            assertThat(SchemaHistoryEvolution.stepsFrom(SchemaHistoryEvolution.LATEST_VERSION)).isEmpty();
        }

        @Test
        void latestVersion_isTwo() {
            assertThat(SchemaHistoryEvolution.LATEST_VERSION).isEqualTo(2);
        }
    }

    @Nested
    class StepOneDdl {

        /// v1 is dialect-independent and always the `CREATE TABLE IF NOT EXISTS` for all 8 columns.
        @ParameterizedTest
        @EnumSource(DatabaseType.class)
        void stepV1_isCreateTableIfNotExists_forEveryDialect(DatabaseType type) {
            var statements = SchemaHistoryEvolution.STEP_V1.ddl().render(type);

            assertThat(statements).hasSize(1);
            assertThat(statements.getFirst())
                    .contains("CREATE TABLE IF NOT EXISTS aether_schema_history")
                    .contains("PRIMARY KEY (version, type)")
                    .doesNotContain("status")
                    .doesNotContain("statements_completed");
        }
    }

    @Nested
    class StepTwoDialectAwareAlter {

        /// PostgreSQL-family and MySQL-family use two portable `ALTER TABLE ... ADD COLUMN ...`
        /// statements — the broadly accepted spelling. The v2 ALTER never carries `IF NOT EXISTS`
        /// because the meta-version gates it to run exactly once.
        @ParameterizedTest
        @EnumSource(value = DatabaseType.class, names = {"POSTGRESQL", "COCKROACHDB", "MYSQL", "MARIADB", "H2", "SQLITE"})
        void stepV2_usesTwoAddColumnStatements_forAddColumnDialects(DatabaseType type) {
            var statements = SchemaHistoryEvolution.STEP_V2.ddl().render(type);

            assertThat(statements).hasSize(2);
            assertThat(statements.get(0))
                    .isEqualTo("ALTER TABLE aether_schema_history ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS'");
            assertThat(statements.get(1))
                    .isEqualTo("ALTER TABLE aether_schema_history ADD COLUMN statements_completed INTEGER NOT NULL DEFAULT 0");
            assertThat(statements).noneMatch(s -> s.contains("IF NOT EXISTS"));
        }

        /// Oracle and DB2 use a single parenthesized `ALTER TABLE ... ADD (col1 ..., col2 ...)`
        /// — neither supports `ADD COLUMN` nor `IF NOT EXISTS`.
        @ParameterizedTest
        @EnumSource(value = DatabaseType.class, names = {"ORACLE", "DB2"})
        void stepV2_usesParenthesizedAddGroup_forOracleAndDb2(DatabaseType type) {
            var statements = SchemaHistoryEvolution.STEP_V2.ddl().render(type);

            assertThat(statements).hasSize(1);
            assertThat(statements.getFirst())
                    .isEqualTo("ALTER TABLE aether_schema_history ADD ("
                               + "status VARCHAR(16) DEFAULT 'SUCCESS' NOT NULL, "
                               + "statements_completed INTEGER DEFAULT 0 NOT NULL)");
            assertThat(statements.getFirst()).doesNotContain("ADD COLUMN").doesNotContain("IF NOT EXISTS");
        }

        /// SQL Server uses a single `ALTER TABLE ... ADD col1 ..., col2 ...` — no `COLUMN` keyword,
        /// no parentheses, no `IF NOT EXISTS`.
        @Test
        void stepV2_usesAddWithoutColumnKeyword_forSqlServer() {
            var statements = SchemaHistoryEvolution.STEP_V2.ddl().render(DatabaseType.SQLSERVER);

            assertThat(statements).hasSize(1);
            assertThat(statements.getFirst())
                    .isEqualTo("ALTER TABLE aether_schema_history ADD "
                               + "status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS', "
                               + "statements_completed INTEGER NOT NULL DEFAULT 0");
            assertThat(statements.getFirst()).doesNotContain("ADD COLUMN").doesNotContain("ADD (").doesNotContain("IF NOT EXISTS");
        }

        /// Every dialect's v2 ALTER adds BOTH new columns with their B2 defaults.
        @ParameterizedTest
        @EnumSource(DatabaseType.class)
        void stepV2_addsBothColumnsWithDefaults_forEveryDialect(DatabaseType type) {
            var joined = String.join(" | ", SchemaHistoryEvolution.STEP_V2.ddl().render(type));

            assertThat(joined).contains("status").contains("SUCCESS")
                              .contains("statements_completed").contains("0");
            assertThat(joined).startsWith("ALTER TABLE aether_schema_history ADD");
        }
    }
}
