// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.parser.grammar;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/// #408(a) regression: a dollar-quoted `CREATE FUNCTION`/`CREATE TRIGGER` body containing an
/// internal `;` must parse as ONE statement. The passthrough `RestOfStatement` now skips string and
/// dollar-quoted spans (so a body `;` is opaque), and `DollarString` matches tagged `$tag$…$tag$`
/// spans where the SAME tag closes (via peglib named-capture + back-reference).
class DollarQuotedFunctionTest extends GrammarTestBase {

    @Nested
    class DollarStringLexeme {
        @Test void untagged() { assertParsesRule("$$hello world$$", "StringLiteral"); }
        @Test void tagged() { assertParsesRule("$body$hello world$body$", "StringLiteral"); }
        @Test void taggedWithSemicolon() { assertParsesRule("$body$a; b; c$body$", "StringLiteral"); }

        // The inner `$other$` is NOT the matching close, so the whole span is one string.
        @Test void innerMismatchedTagIsNotAClose() {
            assertParsesRule("$a$ x $other$ y $a$", "StringLiteral");
        }
    }

    @Nested
    class CreateFunctionWithSemicolonBody {
        @Test void untaggedBody() {
            assertParses("CREATE FUNCTION add(a integer, b integer) RETURNS integer "
                         + "AS $$ BEGIN RETURN a + b; END; $$ LANGUAGE plpgsql");
        }

        @Test void taggedBody() {
            assertParses("CREATE FUNCTION add(a integer, b integer) RETURNS integer "
                         + "AS $func$ BEGIN RETURN a + b; END; $func$ LANGUAGE plpgsql");
        }

        @Test void orReplaceTaggedBody() {
            assertParses("CREATE OR REPLACE FUNCTION touch() RETURNS trigger "
                         + "AS $BODY$ BEGIN NEW.updated_at := now(); RETURN NEW; END; $BODY$ LANGUAGE plpgsql");
        }

        @Test void createTriggerWithFunctionCall() {
            assertParses("CREATE TRIGGER trg BEFORE UPDATE ON users "
                         + "FOR EACH ROW EXECUTE FUNCTION touch()");
        }
    }

    @Nested
    class MultiStatement {
        @Test void twoFunctionsWithDistinctTagsAndBodySemicolons() {
            assertParses("""
                CREATE FUNCTION f1() RETURNS integer AS $a$ BEGIN RETURN 1; END; $a$ LANGUAGE plpgsql;
                CREATE FUNCTION f2() RETURNS integer AS $b$ BEGIN RETURN 2; END; $b$ LANGUAGE plpgsql""");
        }

        // Same tag reused across statements — guards against packrat-memoization capture reuse.
        @Test void twoFunctionsReusingTheSameTag() {
            assertParses("""
                CREATE FUNCTION f1() RETURNS integer AS $body$ BEGIN RETURN 1; END; $body$ LANGUAGE plpgsql;
                CREATE FUNCTION f2() RETURNS integer AS $body$ BEGIN RETURN 2; END; $body$ LANGUAGE plpgsql""");
        }

        @Test void functionBodyFollowedByPlainStatement() {
            assertParses("""
                CREATE FUNCTION f1() RETURNS integer AS $$ BEGIN RETURN 1; END; $$ LANGUAGE plpgsql;
                CREATE TABLE t (id integer)""");
        }
    }
}
