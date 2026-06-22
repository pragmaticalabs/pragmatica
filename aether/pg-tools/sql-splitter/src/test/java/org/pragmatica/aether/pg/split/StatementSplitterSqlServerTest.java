// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.split;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Pure (no-DB) unit tests for the SQL Server (T-SQL) [DialectSpec]. Exercises the `GO` batch
/// separator (line-anchored, optional repeat count, consumed without emitting), bracket
/// identifiers (`[…]` with `]]` an embedded bracket), the `N'…'` national-string prefix,
/// double-quote identifiers, nested block comments, and — the headline behavior —
/// `semicolonTerminates=false`, under which a top-level `;` never splits and only `GO` bounds
/// a batch.
class StatementSplitterSqlServerTest {
    private static final DialectSpec SQLSERVER = Dialects.SQLSERVER;

    private static List<Statement> split(String sql) {
        return StatementSplitter.split(sql, SQLSERVER)
                                .onFailure(cause -> fail("expected success but got: " + cause.message()))
                                .or(List.of());
    }

    private static List<String> texts(String sql) {
        return split(sql).stream().map(Statement::text).toList();
    }

    private static List<String> trimmedTexts(String sql) {
        return split(sql).stream().map(Statement::text).map(String::trim).toList();
    }

    @Nested
    class BatchSeparator {
        @Test
        void split_yieldsOneStatementPerBatch_forGoSeparatedBatches() {
            var sql = "CREATE TABLE a (id INT);\nGO\nCREATE TABLE b (id INT);\nGO\n";

            assertThat(trimmedTexts(sql)).containsExactly(
                "CREATE TABLE a (id INT);",
                "CREATE TABLE b (id INT);");
        }

        @Test
        void split_keepsProcedureBodyIntact_acrossInternalSemicolons() {
            var sql = "CREATE PROCEDURE p AS BEGIN SELECT 1; SELECT 2; END\nGO\nCREATE TABLE t (id INT);\nGO\n";

            assertThat(trimmedTexts(sql)).containsExactly(
                "CREATE PROCEDURE p AS BEGIN SELECT 1; SELECT 2; END",
                "CREATE TABLE t (id INT);");
        }

        @Test
        void split_doesNotEmitGo_andStripsTheSeparatorLine() {
            var sql = "SELECT 1\nGO\nSELECT 2\nGO\n";

            assertThat(trimmedTexts(sql)).containsExactly("SELECT 1", "SELECT 2");
        }

        @Test
        void split_honorsRepeatCount_forGoWithInteger() {
            var sql = "INSERT INTO t VALUES (1)\nGO 3\nINSERT INTO t VALUES (2)\nGO\n";

            assertThat(trimmedTexts(sql)).containsExactly(
                "INSERT INTO t VALUES (1)",
                "INSERT INTO t VALUES (2)");
        }

        @Test
        void split_isCaseInsensitive_forGoKeyword() {
            var sql = "SELECT 1\ngo\nSELECT 2\nGo\n";

            assertThat(trimmedTexts(sql)).containsExactly("SELECT 1", "SELECT 2");
        }

        @Test
        void split_emitsTrailingBatch_whenNoFinalGo() {
            var sql = "SELECT 1\nGO\nSELECT 2";

            assertThat(trimmedTexts(sql)).containsExactly("SELECT 1", "SELECT 2");
        }

        @Test
        void split_doesNotTreatGoAsSeparator_whenNotAloneOnLine() {
            var sql = "SELECT 1 GO 2\nGO\n";

            assertThat(trimmedTexts(sql)).containsExactly("SELECT 1 GO 2");
        }
    }

    @Nested
    class SemicolonSuppression {
        @Test
        void split_doesNotSplitOnSemicolon_forBatchWithoutGo() {
            var sql = "SELECT 1; SELECT 2; SELECT 3;";

            assertThat(texts(sql)).containsExactly("SELECT 1; SELECT 2; SELECT 3;");
        }

        @Test
        void split_keepsMultipleSemicolonsInOneStatement_withinASingleBatch() {
            var sql = "INSERT INTO t VALUES (1); INSERT INTO t VALUES (2)\nGO\n";

            assertThat(trimmedTexts(sql)).containsExactly("INSERT INTO t VALUES (1); INSERT INTO t VALUES (2)");
        }
    }

    @Nested
    class Quoting {
        @Test
        void split_treatsBracketIdentifierAsOpaque_forSemicolonInside() {
            var sql = "SELECT 1 AS [weird;ident]\nGO\n";

            assertThat(trimmedTexts(sql)).containsExactly("SELECT 1 AS [weird;ident]");
        }

        @Test
        void split_keepsDoubledBracketIntact_insideBracketIdentifier() {
            var sql = "SELECT 1 AS [a]]b;c]\nGO\n";

            assertThat(trimmedTexts(sql)).containsExactly("SELECT 1 AS [a]]b;c]");
        }

        @Test
        void split_treatsNationalStringAsOpaque_forSemicolonInside() {
            var sql = "INSERT INTO t VALUES (N'a;b')\nGO\n";

            assertThat(trimmedTexts(sql)).containsExactly("INSERT INTO t VALUES (N'a;b')");
        }

        @Test
        void split_keepsDoubledSingleQuoteIntact_insideNationalString() {
            var sql = "INSERT INTO t VALUES (N'a''b;c')\nGO\n";

            assertThat(trimmedTexts(sql)).containsExactly("INSERT INTO t VALUES (N'a''b;c')");
        }

        @Test
        void split_treatsDoubleQuoteIdentifierAsOpaque_forSemicolonInside() {
            var sql = "SELECT 1 AS \"qident;col\"\nGO\n";

            assertThat(trimmedTexts(sql)).containsExactly("SELECT 1 AS \"qident;col\"");
        }
    }

    @Nested
    class Comments {
        @Test
        void split_nestsBlockComments_forTSqlNestedComment() {
            var sql = "SELECT 1 /* outer /* inner */ still-comment ; */ AS c\nGO\n";

            assertThat(trimmedTexts(sql)).containsExactly("SELECT 1 /* outer /* inner */ still-comment ; */ AS c");
        }

        @Test
        void split_ignoresSemicolon_insideLineComment() {
            var sql = "SELECT 1 -- c;d\nGO\n";

            assertThat(trimmedTexts(sql).getFirst()).contains("-- c;d");
        }
    }
}
