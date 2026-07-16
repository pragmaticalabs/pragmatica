// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.split;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class StatementSplitterTest {
    private static final DialectSpec PG = Dialects.POSTGRESQL;

    private static List<Statement> split(String sql) {
        return StatementSplitter.split(sql, PG)
                                .onFailure(cause -> fail("expected success but got: " + cause.message()))
                                .or(List.of());
    }

    private static List<String> texts(String sql) {
        return split(sql).stream().map(Statement::text).toList();
    }

    @Nested
    class HappyPath {
        @Test
        void split_keepsFunctionBodyIntact_forDollarQuotedBody() {
            var sql = "CREATE FUNCTION f() RETURNS void AS $$ BEGIN x; y; END; $$ LANGUAGE plpgsql;";

            assertThat(texts(sql)).containsExactly(
                "CREATE FUNCTION f() RETURNS void AS $$ BEGIN x; y; END; $$ LANGUAGE plpgsql");
        }

        @Test
        void split_keepsBodyIntact_forTaggedDollarQuote() {
            var sql = "CREATE FUNCTION f() AS $func$ SELECT 1; SELECT 2; $func$ LANGUAGE sql;";

            assertThat(texts(sql)).containsExactly(
                "CREATE FUNCTION f() AS $func$ SELECT 1; SELECT 2; $func$ LANGUAGE sql");
        }

        @Test
        void split_treatsEmptyTagBodyAsOpaque_forBareDollarQuote() {
            var sql = "SELECT $$a;b$$; SELECT 1;";

            assertThat(texts(sql)).containsExactly("SELECT $$a;b$$", " SELECT 1");
        }

        @Test
        void split_ignoresSemicolons_insideNestedBlockComment() {
            var sql = "SELECT 1 /* outer ; /* inner ; */ still ; */ ; SELECT 2;";

            assertThat(texts(sql)).hasSize(2);
            assertThat(texts(sql).get(0)).contains("/* outer ; /* inner ; */ still ; */");
        }

        @Test
        void split_ignoresSemicolon_insideEscapeStringWithBackslash() {
            var sql = "SELECT E'a\\nb;c'; SELECT 2;";

            assertThat(texts(sql)).containsExactly("SELECT E'a\\nb;c'", " SELECT 2");
        }

        @Test
        void split_ignoresSemicolon_insideDoubledQuoteString() {
            var sql = "SELECT 'it''s; ok'; SELECT 2;";

            assertThat(texts(sql)).containsExactly("SELECT 'it''s; ok'", " SELECT 2");
        }

        @Test
        void split_ignoresSemicolon_insideQuotedIdentifier() {
            var sql = "SELECT 1 AS \"weird;ident\"; SELECT 2;";

            assertThat(texts(sql)).containsExactly("SELECT 1 AS \"weird;ident\"", " SELECT 2");
        }

        @Test
        void split_ignoresSemicolon_insideLineComment() {
            var sql = "SELECT 1 -- a ; comment\n; SELECT 2;";

            assertThat(texts(sql)).hasSize(2);
            assertThat(texts(sql).get(0)).contains("-- a ; comment");
        }

        @Test
        void split_returnsAllStatements_forMultiStatementScript() {
            var sql = "SELECT 1; SELECT 2; SELECT 3;";

            assertThat(texts(sql)).containsExactly("SELECT 1", " SELECT 2", " SELECT 3");
        }

        @Test
        void split_treatsCopyDataAsOpaque_andResumesAfterMarker() {
            var sql = "COPY t FROM STDIN;\n1\t2;3\n\\.\nSELECT 1;";

            var texts = texts(sql);

            assertThat(texts).hasSize(2);
            assertThat(texts.get(0)).isEqualTo("COPY t FROM STDIN;\n1\t2;3\n\\.");
            assertThat(texts.get(1)).isEqualTo("SELECT 1");
        }

        @Test
        void split_stripsLeadingBom() {
            var sql = "﻿SELECT 1;";

            assertThat(texts(sql)).containsExactly("SELECT 1");
        }

        @Test
        void split_emitsTrailingStatement_whenNoFinalSemicolon() {
            var sql = "SELECT 1; SELECT 2";

            assertThat(texts(sql)).containsExactly("SELECT 1", " SELECT 2");
        }

        @Test
        void split_emitsStatement_forSingleTrailingSemicolon() {
            assertThat(texts("SELECT 1;")).containsExactly("SELECT 1");
        }

        @Test
        void split_recordsStartLine_perStatement() {
            var sql = "SELECT 1;\nSELECT 2;\n\nSELECT 3;";

            var statements = split(sql);

            assertThat(statements).hasSize(3);
            assertThat(statements.get(0).startLine()).isEqualTo(1);
            assertThat(statements.get(1).startLine()).isEqualTo(2);
            assertThat(statements.get(2).startLine()).isEqualTo(4);
        }
    }

    @Nested
    class EmptyAndBlank {
        @Test
        void split_returnsEmptyList_forCommentOnlyInput() {
            assertThat(split("-- just a comment\n/* and a block */")).isEmpty();
        }

        @Test
        void split_returnsEmptyList_forWhitespaceOnlyInput() {
            assertThat(split("   \n\t  ")).isEmpty();
        }

        @Test
        void split_returnsEmptyList_forEmptyInput() {
            assertThat(split("")).isEmpty();
        }

        @Test
        void split_skipsEmptyStatements_betweenSemicolons() {
            assertThat(texts("SELECT 1;;;SELECT 2;")).containsExactly("SELECT 1", "SELECT 2");
        }
    }

    @Nested
    class Failures {
        @Test
        void split_fails_forUnterminatedDollarQuote() {
            StatementSplitter.split("CREATE FUNCTION f() AS $$ BEGIN", PG)
                             .onSuccess(s -> fail("expected failure but got " + s))
                             .onFailure(cause -> assertThat(cause)
                                 .isInstanceOf(SplitError.UnterminatedDollarQuote.class));
        }

        @Test
        void split_fails_forUnterminatedString() {
            assertFailure("SELECT 'open", SplitError.UnterminatedString.class);
        }

        @Test
        void split_fails_forUnterminatedEscapeString() {
            assertFailure("SELECT E'open\\", SplitError.UnterminatedString.class);
        }

        @Test
        void split_fails_forUnterminatedBlockComment() {
            assertFailure("SELECT 1 /* open", SplitError.UnterminatedBlockComment.class);
        }

        @Test
        void split_fails_forUnterminatedNestedBlockComment() {
            assertFailure("SELECT 1 /* /* inner closed */ still open", SplitError.UnterminatedBlockComment.class);
        }

        @Test
        void split_fails_forUnterminatedQuotedIdentifier() {
            assertFailure("SELECT 1 AS \"open", SplitError.UnterminatedQuotedIdentifier.class);
        }

        @Test
        void split_fails_forUnterminatedCopyData() {
            assertFailure("COPY t FROM STDIN;\n1\t2\n", SplitError.UnterminatedCopyData.class);
        }

        private void assertFailure(String sql, Class<? extends SplitError> expected) {
            StatementSplitter.split(sql, PG)
                             .onSuccess(s -> fail("expected failure but got " + s))
                             .onFailure(cause -> assertThat(cause).isInstanceOf(expected));
        }
    }

    @Nested
    class VerbatimText {
        @Test
        void split_preservesVerbatimText_includingInternalWhitespace() {
            var sql = "  SELECT   1  ;  SELECT 2;";

            assertThat(texts(sql)).containsExactly("  SELECT   1  ", "  SELECT 2");
        }

        @Test
        void split_keepsCaseSensitiveDollarTag() {
            Result<List<Statement>> result = StatementSplitter.split("SELECT $Tag$x;y$Tag$;", PG);

            result.onFailure(cause -> fail(cause.message()))
                  .onSuccess(list -> assertThat(list.get(0).text()).isEqualTo("SELECT $Tag$x;y$Tag$"));
        }
    }
}
