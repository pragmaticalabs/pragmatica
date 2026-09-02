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

/// Pure (no-DB) unit tests for the H2 [DialectSpec]. Exercises the SQL-standard lexical set H2
/// uses: `''`-doubled single-quoted strings with NO backslash escapes, double-quote identifiers,
/// flat (non-nesting) `/*…*/` block comments, and `--` line comments. H2 has no dollar quoting,
/// no redefinable terminator, and no batch separator; a top-level `;` terminates a statement
/// (`semicolonTerminates=true`).
class StatementSplitterH2Test {
    private static final DialectSpec H2 = Dialects.H2;

    private static List<Statement> split(String sql) {
        return StatementSplitter.split(sql, H2)
                                .onFailure(cause -> fail("expected success but got: " + cause.message()))
                                .or(List.of());
    }

    private static List<String> texts(String sql) {
        return split(sql).stream().map(Statement::text).toList();
    }

    @Nested
    class Quoting {
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
    }

    @Nested
    class DefaultTerminator {
        @Test
        void split_splitsOnSemicolon_forSimpleStatements() {
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
