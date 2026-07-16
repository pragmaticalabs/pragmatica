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

/// Pure (no-DB) unit tests for the DB2 [DialectSpec]. Exercises the `--#SET TERMINATOR`
/// redefinable terminator (reusing the engine's terminator machinery with a multi-word,
/// `--`-prefixed keyword recognized as a directive BEFORE line-comment handling), double-quote
/// identifiers, `''`-doubled strings, and flat (non-nesting) block comments. DB2 keeps `;`
/// splitting by default (`semicolonTerminates=true`).
class StatementSplitterDb2Test {
    private static final DialectSpec DB2 = Dialects.DB2;

    private static List<Statement> split(String sql) {
        return StatementSplitter.split(sql, DB2)
                                .onFailure(cause -> fail("expected success but got: " + cause.message()))
                                .or(List.of());
    }

    private static List<String> texts(String sql) {
        return split(sql).stream().map(Statement::text).toList();
    }

    @Nested
    class SetTerminator {
        @Test
        void split_stripsDirectiveAndKeepsInternalSemicolons_forSetTerminatorProcedure() {
            var sql = "--#SET TERMINATOR @\n"
                      + "CREATE PROCEDURE p() BEGIN SELECT 1; SELECT 2; END@\n"
                      + "--#SET TERMINATOR ;\n"
                      + "SELECT 3; SELECT 4;";

            assertThat(texts(sql)).containsExactly(
                "CREATE PROCEDURE p() BEGIN SELECT 1; SELECT 2; END",
                "SELECT 3",
                " SELECT 4");
        }

        @Test
        void split_terminatesOnRedefinedToken_underSetTerminator() {
            var sql = "--#SET TERMINATOR @\nSELECT 1@ SELECT 2@";

            assertThat(texts(sql)).containsExactly("SELECT 1", " SELECT 2");
        }

        @Test
        void split_isCaseInsensitive_forSetTerminatorKeyword() {
            var sql = "--#set terminator @\nSELECT 1@ SELECT 2@";

            assertThat(texts(sql)).containsExactly("SELECT 1", " SELECT 2");
        }

        @Test
        void split_resetsTerminator_afterSecondSetTerminator() {
            var sql = "--#SET TERMINATOR @\nSELECT 1@\n--#SET TERMINATOR ;\nSELECT 2; SELECT 3;";

            assertThat(texts(sql)).containsExactly("SELECT 1", "SELECT 2", " SELECT 3");
        }

        @Test
        void split_toleratesLeadingWhitespace_beforeSetTerminatorDirective() {
            var sql = "   --#SET TERMINATOR @\nSELECT 1@";

            assertThat(texts(sql)).containsExactly("SELECT 1");
        }

        @Test
        void split_doesNotTreatDirectiveAsLineComment_forSetTerminator() {
            var sql = "--#SET TERMINATOR @\nSELECT 1@";

            assertThat(texts(sql)).containsExactly("SELECT 1");
        }
    }

    @Nested
    class Quoting {
        @Test
        void split_ignoresSemicolon_insideDoubleQuoteIdentifier() {
            var sql = "SELECT 1 AS \"ident;col\"; SELECT 2;";

            assertThat(texts(sql)).containsExactly("SELECT 1 AS \"ident;col\"", " SELECT 2");
        }

        @Test
        void split_keepsDoubledQuotesIntact_insideDoubleQuoteIdentifier() {
            var sql = "SELECT 1 AS \"a\"\"b;c\"; SELECT 2;";

            assertThat(texts(sql)).containsExactly("SELECT 1 AS \"a\"\"b;c\"", " SELECT 2");
        }

        @Test
        void split_ignoresSemicolon_insideString() {
            var sql = "INSERT INTO t VALUES ('a;b'); SELECT 2;";

            assertThat(texts(sql)).containsExactly("INSERT INTO t VALUES ('a;b')", " SELECT 2");
        }

        @Test
        void split_keepsDoubledSingleQuoteIntact_insideString() {
            var sql = "INSERT INTO t VALUES ('a''b;c'); SELECT 2;";

            assertThat(texts(sql)).containsExactly("INSERT INTO t VALUES ('a''b;c')", " SELECT 2");
        }
    }

    @Nested
    class Comments {
        @Test
        void split_ignoresSemicolon_insideLineComment() {
            var sql = "SELECT 1 -- c;d\n; SELECT 2;";

            assertThat(texts(sql)).hasSize(2);
            assertThat(texts(sql).getFirst()).contains("-- c;d");
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
    }

    @Nested
    class DefaultTerminator {
        @Test
        void split_splitsOnSemicolon_beforeAnyDirective() {
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
