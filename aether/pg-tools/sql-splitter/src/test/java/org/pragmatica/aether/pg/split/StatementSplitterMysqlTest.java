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

/// Pure (no-DB) unit tests for the MySQL/MariaDB [DialectSpec]. Exercises the redefinable
/// `DELIMITER` terminator, backtick identifiers, double-quote strings, `#` comments, flat
/// (non-nesting) block comments, and the `--`-requires-space rule.
class StatementSplitterMysqlTest {
    private static final DialectSpec MYSQL = Dialects.MYSQL;

    private static List<Statement> split(String sql) {
        return StatementSplitter.split(sql, MYSQL)
                                .onFailure(cause -> fail("expected success but got: " + cause.message()))
                                .or(List.of());
    }

    private static List<String> texts(String sql) {
        return split(sql).stream().map(Statement::text).toList();
    }

    @Nested
    class Delimiter {
        @Test
        void split_yieldsOneStatement_forDelimiterWrappedProcedure() {
            var sql = "DELIMITER $$\nCREATE PROCEDURE p() BEGIN SELECT 1; SELECT 2; END$$\nDELIMITER ;";

            assertThat(texts(sql)).containsExactly("CREATE PROCEDURE p() BEGIN SELECT 1; SELECT 2; END");
        }

        @Test
        void split_keepsInternalSemicolonsIntact_underRedefinedTerminator() {
            var sql = "DELIMITER //\nSELECT 1; SELECT 2; SELECT 3//\nDELIMITER ;";

            assertThat(texts(sql)).containsExactly("SELECT 1; SELECT 2; SELECT 3");
        }

        @Test
        void split_resetsTerminator_afterDelimiterReset() {
            var sql = "DELIMITER $$\nSELECT 1$$\nDELIMITER ;\nSELECT 2; SELECT 3;";

            assertThat(texts(sql)).containsExactly("SELECT 1", "SELECT 2", " SELECT 3");
        }

        @Test
        void split_isCaseInsensitive_forDelimiterKeyword() {
            var sql = "delimiter $$\nSELECT 1$$\ndelimiter ;\nSELECT 2;";

            assertThat(texts(sql)).containsExactly("SELECT 1", "SELECT 2");
        }

        @Test
        void split_toleratesLeadingWhitespace_beforeDelimiterDirective() {
            var sql = "   DELIMITER $$\nSELECT 1$$\n";

            assertThat(texts(sql)).containsExactly("SELECT 1");
        }

        @Test
        void split_doesNotTreatInlineDelimiterAsDirective_whenNotAtStatementStart() {
            var sql = "SELECT 1; SELECT 2;";

            assertThat(texts(sql)).containsExactly("SELECT 1", " SELECT 2");
        }
    }

    @Nested
    class Quoting {
        @Test
        void split_ignoresSemicolon_insideBacktickIdentifier() {
            var sql = "SELECT 1 AS `weird;col`; SELECT 2;";

            assertThat(texts(sql)).containsExactly("SELECT 1 AS `weird;col`", " SELECT 2");
        }

        @Test
        void split_ignoresSemicolon_insideDoubleQuoteString() {
            var sql = "SELECT \"a;b\"; SELECT 2;";

            assertThat(texts(sql)).containsExactly("SELECT \"a;b\"", " SELECT 2");
        }

        @Test
        void split_keepsDoubledQuotesIntact_insideDoubleQuoteString() {
            var sql = "SELECT \"a\"\"b;c\"; SELECT 2;";

            assertThat(texts(sql)).containsExactly("SELECT \"a\"\"b;c\"", " SELECT 2");
        }

        @Test
        void split_keepsDoubledBacktickIntact_insideBacktickIdentifier() {
            var sql = "SELECT 1 AS `a``b;c`; SELECT 2;";

            assertThat(texts(sql)).containsExactly("SELECT 1 AS `a``b;c`", " SELECT 2");
        }
    }

    @Nested
    class Comments {
        @Test
        void split_ignoresSemicolon_insideHashComment() {
            var sql = "SELECT 1 # c;d\n; SELECT 2;";

            assertThat(texts(sql)).hasSize(2);
            assertThat(texts(sql).get(0)).contains("# c;d");
        }

        @Test
        void split_closesAtFirstStar_forNonNestedBlockComment() {
            var sql = "SELECT 1 /* a; b */; SELECT 2;";

            assertThat(texts(sql)).containsExactly("SELECT 1 /* a; b */", " SELECT 2");
        }

        @Test
        void split_doesNotNest_forNonNestedBlockComment() {
            var sql = "SELECT 1 /* outer /* inner */ x; SELECT 2;";

            assertThat(texts(sql)).containsExactly("SELECT 1 /* outer /* inner */ x", " SELECT 2");
        }

        @Test
        void split_treatsDashSpaceAsComment_whenDashFollowedByWhitespace() {
            var sql = "SELECT 1 -- a ; comment\n; SELECT 2;";

            assertThat(texts(sql)).hasSize(2);
            assertThat(texts(sql).get(0)).contains("-- a ; comment");
        }

        @Test
        void split_doesNotTreatDashAsComment_whenNotFollowedByWhitespace() {
            var sql = "SELECT 1--2; SELECT 2;";

            assertThat(texts(sql)).containsExactly("SELECT 1--2", " SELECT 2");
        }
    }

    @Nested
    class DefaultDelimiter {
        @Test
        void split_splitsOnSemicolon_withDefaultDelimiter() {
            var sql = "SELECT 1; SELECT 2; SELECT 3;";

            assertThat(texts(sql)).containsExactly("SELECT 1", " SELECT 2", " SELECT 3");
        }

        @Test
        void split_emitsTrailingStatement_whenNoFinalSemicolon() {
            var sql = "SELECT 1; SELECT 2";

            assertThat(texts(sql)).containsExactly("SELECT 1", " SELECT 2");
        }
    }
}
