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

/// Pure (no-DB) unit tests for the Oracle [DialectSpec]. Exercises the three Oracle-specific
/// engine primitives: the `/` block-line terminator (line-anchored, consumed without emitting),
/// alternative quoting `q'X…X'`/`Q'X…X'` (mirror- and same-delimiter forms, opaque to `;`), and
/// PL/SQL BLOCK MODE — a per-statement decision keyed off the leading keywords under which a
/// PL/SQL block (`DECLARE`/`BEGIN`/`CREATE … PROCEDURE|FUNCTION|TRIGGER|PACKAGE|TYPE …`) keeps
/// its internal `;` and ends only on a `/`-line, while a plain `CREATE TABLE`/`INSERT`/`ALTER`
/// terminates on `;` as usual. Also covers double-quote identifiers, non-nesting block comments,
/// and the unterminated-span failure path.
class StatementSplitterOracleTest {
    private static final DialectSpec ORACLE = Dialects.ORACLE;

    private static List<Statement> split(String sql) {
        return StatementSplitter.split(sql, ORACLE)
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
    class PlsqlBlockMode {
        @Test
        void split_keepsBodyIntact_forCreateOrReplaceProcedure() {
            var sql = "CREATE OR REPLACE PROCEDURE p AS BEGIN x := 1; y := 2; END;\n/\n";

            assertThat(trimmedTexts(sql)).containsExactly(
                "CREATE OR REPLACE PROCEDURE p AS BEGIN x := 1; y := 2; END;");
        }

        @Test
        void split_keepsBodyIntact_forCreateFunction() {
            var sql = "CREATE FUNCTION f RETURN NUMBER AS BEGIN x := 1; RETURN x; END;\n/\n";

            assertThat(trimmedTexts(sql)).containsExactly(
                "CREATE FUNCTION f RETURN NUMBER AS BEGIN x := 1; RETURN x; END;");
        }

        @Test
        void split_keepsBodyIntact_forCreateTrigger() {
            var sql = "CREATE TRIGGER trg BEFORE INSERT ON t FOR EACH ROW BEGIN a := 1; b := 2; END;\n/\n";

            assertThat(trimmedTexts(sql)).containsExactly(
                "CREATE TRIGGER trg BEFORE INSERT ON t FOR EACH ROW BEGIN a := 1; b := 2; END;");
        }

        @Test
        void split_keepsBodyIntact_forCreatePackageBody() {
            var sql = "CREATE PACKAGE BODY pkg AS PROCEDURE q IS BEGIN x := 1; y := 2; END q; END pkg;\n/\n";

            assertThat(trimmedTexts(sql)).containsExactly(
                "CREATE PACKAGE BODY pkg AS PROCEDURE q IS BEGIN x := 1; y := 2; END q; END pkg;");
        }

        @Test
        void split_keepsBodyIntact_forCreateOrReplaceEditionableProcedure() {
            var sql = "CREATE OR REPLACE EDITIONABLE PROCEDURE p AS BEGIN x := 1; END;\n/\n";

            assertThat(trimmedTexts(sql)).containsExactly(
                "CREATE OR REPLACE EDITIONABLE PROCEDURE p AS BEGIN x := 1; END;");
        }

        @Test
        void split_keepsBodyIntact_forAnonymousDeclareBlock() {
            var sql = "DECLARE n NUMBER; BEGIN n := 1; INSERT INTO t VALUES (n); END;\n/\n";

            assertThat(trimmedTexts(sql)).containsExactly(
                "DECLARE n NUMBER; BEGIN n := 1; INSERT INTO t VALUES (n); END;");
        }

        @Test
        void split_keepsBodyIntact_forAnonymousBeginBlock() {
            var sql = "BEGIN a := 1; b := 2; END;\n/\n";

            assertThat(trimmedTexts(sql)).containsExactly("BEGIN a := 1; b := 2; END;");
        }

        @Test
        void split_isCaseInsensitive_forBlockStartKeywords() {
            var sql = "create or replace procedure p as begin x := 1; end;\n/\n";

            assertThat(trimmedTexts(sql)).containsExactly(
                "create or replace procedure p as begin x := 1; end;");
        }

        @Test
        void split_entersBlockMode_despiteLeadingComment() {
            var sql = "-- make a proc\nCREATE PROCEDURE p AS BEGIN x := 1; y := 2; END;\n/\n";

            assertThat(trimmedTexts(sql)).containsExactly(
                "-- make a proc\nCREATE PROCEDURE p AS BEGIN x := 1; y := 2; END;".trim());
        }
    }

    @Nested
    class PlainStatements {
        @Test
        void split_splitsOnSemicolon_forPlainCreateTableAndInsert() {
            var sql = "CREATE TABLE t (id NUMBER); INSERT INTO t VALUES (1);";

            assertThat(texts(sql)).containsExactly(
                "CREATE TABLE t (id NUMBER)",
                " INSERT INTO t VALUES (1)");
        }

        @Test
        void split_splitsOnSemicolon_forPlainAlter() {
            var sql = "ALTER TABLE t ADD (c NUMBER); ALTER TABLE t DROP COLUMN c;";

            assertThat(texts(sql)).containsExactly(
                "ALTER TABLE t ADD (c NUMBER)",
                " ALTER TABLE t DROP COLUMN c");
        }

        @Test
        void split_doesNotEnterBlockMode_forCreateTableOfType() {
            var sql = "CREATE TABLE t (id NUMBER); INSERT INTO t VALUES (2);";

            assertThat(texts(sql)).hasSize(2);
        }

        @Test
        void split_emitsTrailingStatement_whenNoFinalSemicolon() {
            var sql = "CREATE TABLE t (id NUMBER); INSERT INTO t VALUES (1)";

            assertThat(texts(sql)).containsExactly(
                "CREATE TABLE t (id NUMBER)",
                " INSERT INTO t VALUES (1)");
        }
    }

    @Nested
    class MixedScript {
        @Test
        void split_yieldsThreeStatements_forTableThenBlockThenInsert() {
            var sql = "CREATE TABLE t (id NUMBER);\n"
                      + "CREATE PROCEDURE p AS BEGIN x := 1; y := 2; END;\n"
                      + "/\n"
                      + "INSERT INTO t VALUES (1);\n";

            assertThat(trimmedTexts(sql)).containsExactly(
                "CREATE TABLE t (id NUMBER)",
                "CREATE PROCEDURE p AS BEGIN x := 1; y := 2; END;",
                "INSERT INTO t VALUES (1)");
        }

        @Test
        void split_keepsBlockBodyIntact_inMixedScript() {
            var sql = "CREATE TABLE t (id NUMBER);\n"
                      + "CREATE PROCEDURE p AS BEGIN x := 1; y := 2; END;\n"
                      + "/\n"
                      + "INSERT INTO t VALUES (1);\n";

            assertThat(trimmedTexts(sql).get(1))
                .contains("x := 1;")
                .contains("y := 2;");
        }
    }

    @Nested
    class BlockLineTerminator {
        @Test
        void split_stripsSlashLine_andDoesNotEmitIt() {
            var sql = "BEGIN x := 1; END;\n/\n";

            assertThat(trimmedTexts(sql)).containsExactly("BEGIN x := 1; END;");
        }

        @Test
        void split_doesNotTreatSlashAsTerminator_whenNotAloneOnLine() {
            var sql = "INSERT INTO t VALUES (1 / 2);";

            assertThat(texts(sql)).containsExactly("INSERT INTO t VALUES (1 / 2)");
        }

        @Test
        void split_emitsBlock_forSlashLineWithSurroundingWhitespace() {
            var sql = "BEGIN x := 1; END;\n   /   \n";

            assertThat(trimmedTexts(sql)).containsExactly("BEGIN x := 1; END;");
        }
    }

    @Nested
    class AltQuoting {
        @Test
        void split_treatsBracketAltQuoteAsOpaque_forSemicolonInside() {
            var sql = "INSERT INTO t VALUES (q'[a;b]'); INSERT INTO t VALUES (2);";

            assertThat(texts(sql)).containsExactly(
                "INSERT INTO t VALUES (q'[a;b]')",
                " INSERT INTO t VALUES (2)");
        }

        @Test
        void split_treatsBraceAltQuoteAsOpaque_forSemicolonInside() {
            var sql = "INSERT INTO t VALUES (q'{a;b}'); INSERT INTO t VALUES (2);";

            assertThat(texts(sql)).containsExactly(
                "INSERT INTO t VALUES (q'{a;b}')",
                " INSERT INTO t VALUES (2)");
        }

        @Test
        void split_treatsBangAltQuoteAsOpaque_forSemicolonInside() {
            var sql = "INSERT INTO t VALUES (q'!a;b!'); INSERT INTO t VALUES (2);";

            assertThat(texts(sql)).containsExactly(
                "INSERT INTO t VALUES (q'!a;b!')",
                " INSERT INTO t VALUES (2)");
        }

        @Test
        void split_treatsParenAltQuoteAsOpaque_forSemicolonInside() {
            var sql = "INSERT INTO t VALUES (q'(a;b)'); INSERT INTO t VALUES (2);";

            assertThat(texts(sql)).containsExactly(
                "INSERT INTO t VALUES (q'(a;b)')",
                " INSERT INTO t VALUES (2)");
        }

        @Test
        void split_treatsAngleAltQuoteAsOpaque_forSemicolonInside() {
            var sql = "INSERT INTO t VALUES (q'<a;b>'); INSERT INTO t VALUES (2);";

            assertThat(texts(sql)).containsExactly(
                "INSERT INTO t VALUES (q'<a;b>')",
                " INSERT INTO t VALUES (2)");
        }

        @Test
        void split_treatsUppercaseAltQuoteAsOpaque_forSemicolonInside() {
            var sql = "INSERT INTO t VALUES (Q'[a;b]'); INSERT INTO t VALUES (2);";

            assertThat(texts(sql)).containsExactly(
                "INSERT INTO t VALUES (Q'[a;b]')",
                " INSERT INTO t VALUES (2)");
        }

        @Test
        void split_doesNotCloseOnLoneDelimiter_withoutClosingQuote() {
            var sql = "INSERT INTO t VALUES (q'[a]b;c]'); INSERT INTO t VALUES (2);";

            assertThat(texts(sql)).containsExactly(
                "INSERT INTO t VALUES (q'[a]b;c]')",
                " INSERT INTO t VALUES (2)");
        }
    }

    @Nested
    class Quoting {
        @Test
        void split_ignoresSemicolon_insideDoubleQuoteIdentifier() {
            var sql = "SELECT 1 AS \"weird;ident\" FROM dual; SELECT 2 FROM dual;";

            assertThat(texts(sql)).containsExactly(
                "SELECT 1 AS \"weird;ident\" FROM dual",
                " SELECT 2 FROM dual");
        }

        @Test
        void split_ignoresSemicolon_insideString() {
            var sql = "INSERT INTO t VALUES ('a;b'); INSERT INTO t VALUES ('c');";

            assertThat(texts(sql)).containsExactly(
                "INSERT INTO t VALUES ('a;b')",
                " INSERT INTO t VALUES ('c')");
        }

        @Test
        void split_keepsDoubledSingleQuoteIntact_insideString() {
            var sql = "INSERT INTO t VALUES ('a''b;c'); INSERT INTO t VALUES ('d');";

            assertThat(texts(sql)).containsExactly(
                "INSERT INTO t VALUES ('a''b;c')",
                " INSERT INTO t VALUES ('d')");
        }
    }

    @Nested
    class Comments {
        @Test
        void split_closesAtFirstStar_forNonNestedBlockComment() {
            var sql = "SELECT 1 /* a; b */ FROM dual; SELECT 2 FROM dual;";

            assertThat(texts(sql)).containsExactly(
                "SELECT 1 /* a; b */ FROM dual",
                " SELECT 2 FROM dual");
        }

        @Test
        void split_doesNotNest_forNonNestedBlockComment() {
            var sql = "SELECT 1 /* outer /* inner */ x FROM dual; SELECT 2 FROM dual;";

            assertThat(texts(sql)).containsExactly(
                "SELECT 1 /* outer /* inner */ x FROM dual",
                " SELECT 2 FROM dual");
        }

        @Test
        void split_ignoresSemicolon_insideLineComment() {
            var sql = "SELECT 1 -- c;d\nFROM dual; SELECT 2 FROM dual;";

            assertThat(texts(sql)).hasSize(2);
            assertThat(texts(sql).getFirst()).contains("-- c;d");
        }
    }

    @Nested
    class Failures {
        @Test
        void split_fails_forUnterminatedAltQuote() {
            StatementSplitter.split("INSERT INTO t VALUES (q'[a;b", ORACLE)
                             .onSuccess(_ -> fail("expected failure for unterminated alt-quote"))
                             .onFailure(cause -> assertThat(cause).isInstanceOf(SplitError.UnterminatedString.class));
        }

        @Test
        void split_fails_forUnterminatedString() {
            StatementSplitter.split("INSERT INTO t VALUES ('a;b", ORACLE)
                             .onSuccess(_ -> fail("expected failure for unterminated string"))
                             .onFailure(cause -> assertThat(cause).isInstanceOf(SplitError.UnterminatedString.class));
        }

        @Test
        void split_fails_forUnterminatedQuotedIdentifier() {
            StatementSplitter.split("SELECT 1 AS \"col", ORACLE)
                             .onSuccess(_ -> fail("expected failure for unterminated quoted identifier"))
                             .onFailure(cause -> assertThat(cause).isInstanceOf(SplitError.UnterminatedQuotedIdentifier.class));
        }

        @Test
        void split_fails_forUnterminatedBlockComment() {
            StatementSplitter.split("SELECT 1 /* a; b", ORACLE)
                             .onSuccess(_ -> fail("expected failure for unterminated block comment"))
                             .onFailure(cause -> assertThat(cause).isInstanceOf(SplitError.UnterminatedBlockComment.class));
        }
    }
}
