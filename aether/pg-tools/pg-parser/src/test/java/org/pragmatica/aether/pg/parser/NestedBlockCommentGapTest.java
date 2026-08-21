// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Tripwire for the nested-block-comment gap (#619, upstream `siy/java-peglib#45`).
///
/// PostgreSQL nests `/* ... */` per the SQL standard. `BlockComment` is `'/*' (!'*/' .)* '*/'`, which
/// closes at the FIRST `*/`, and it cannot be repaired here: the rule is reachable from
/// `%whitespace` and so compiles into peglib's DFA lexer, while the recursive formulation is refused
/// by peglib's analyzer as `grammar.whitespace-cycle`.
///
/// These assertions deliberately pin the WRONG behaviour, because the wrong behaviour is what ships
/// and because the gap's danger is that it is quiet. The remainder after the inner `*/` leaks in as
/// live SQL, so when it happens to compose into something valid the parser accepts a DIFFERENT
/// statement with no diagnostic at all — the ticket's original "fails to parse" framing held only
/// for the inputs it happened to try.
///
/// When peglib grows a counting scanner and this gap closes, these tests go RED. That is the point:
/// they are the signal to delete this file, re-add the two excluded statements to
/// `corpus/dml-select.sql`, and update the note at the rule in `postgres.peg` plus
/// `pg-persistence-spec.md` and the feature catalog. A disabled test would document the intent but
/// would never fire; only an assertion on current behaviour can tell you the day it changes.
class NestedBlockCommentGapTest {
    private static final PostgresParser PARSER = PostgresParser.create();

    /// The control: one level of nesting is handled correctly, so anything below is about nesting
    /// and not about block comments in general.
    @Test
    void singleLevelBlockComment_parses() {
        assertThat(PARSER.parseCst("SELECT 1 /* plain */ AS c;").isSuccess())
                  .as("single-level block comments are unaffected by the gap")
                  .isTrue();
    }

    @Test
    void nestedBlockComment_silentlyYieldsADifferentStatement_untilPeglib45() {
        var sql = """
                  SELECT 1 /* /* */ , 999 -- */
                   FROM t;
                  """;

        assertThat(PARSER.parseCst(sql).isSuccess())
                  .as("correct nesting makes the whole /* /* */ , 999 -- */ run a comment, so this "
                     + "SHOULD be SELECT 1 FROM t; it parses instead as SELECT 1, 999 FROM t — the "
                     + "leaked `, 999` becomes a second select-list item and the trailing `-- */` "
                     + "swallows the orphaned outer `*/`, leaving nothing to fail on")
                  .isTrue();
    }

    @Test
    void nestedBlockComment_failsLate_whenTheLeakedTextIsNotValidSql() {
        assertThat(PARSER.parseCst("SELECT 1 /* outer /* inner */ still a comment */ AS c;").isFailure())
                  .as("here the leak does not compose into valid SQL, so it does fail — but late: the "
                     + "parser first accepts the truncated `SELECT 1 still` (implicit column alias) "
                     + "and reports `expected end of input` well past the real cause, never "
                     + "mentioning comments")
                  .isTrue();
    }
}
