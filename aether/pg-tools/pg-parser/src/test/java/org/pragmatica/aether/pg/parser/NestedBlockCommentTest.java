// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.parser;

import java.util.List;

import org.pragmatica.aether.pg.parser.PostgresParser.CstNode;
import org.pragmatica.aether.pg.parser.transform.CstNavigator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Nested block comments (#619, upstream `siy/java-peglib#45`, fixed by `%nest` in peglib 0.7.3).
///
/// This replaces `NestedBlockCommentGapTest`, which pinned the BROKEN behaviour on purpose so that
/// closing the gap would turn it red. It did, and this is the other side of that trade.
///
/// The assertion that carries the weight is [#nestedComment_containingASelectItem_swallowsIt]. Before
/// `%nest`, the lexer closed at the FIRST `*/` and the remainder leaked into the statement as live
/// SQL — which was NOT reliably a parse error. Where the leak composed into valid SQL the parser
/// accepted a **different statement with no diagnostic at all**, and a select-list count is what
/// makes that visible; a boolean parses/does-not-parse assertion cannot see it.
///
/// One trap, learned by getting it wrong: `/* a /* b */ -- */` is BALANCED (two opens, two closes),
/// so anything after it is legitimately outside the comment and the old lexer happened to agree.
/// Only a leak that sits INSIDE the balanced span diverges. When adding a case here, count the
/// delimiters before deciding what the right answer is.
class NestedBlockCommentTest {
    private static final PostgresParser PARSER = PostgresParser.create();

    @Test
    void singleLevelComment_parses() {
        assertThat(PARSER.parseCst("SELECT 1 /* plain */ AS c;").isSuccess())
                  .as("the ordinary case still works after moving block comments off the DFA path")
                  .isTrue();
    }

    @Test
    void nestedComment_parses() {
        assertThat(PARSER.parseCst("SELECT 1 /* outer /* inner */ still a comment */ AS c;").isSuccess())
                  .as("the whole span is one comment; the inner */ must not close it")
                  .isTrue();
    }

    /// The regression sensor. `, 999` sits inside the balanced span, so the statement selects ONE
    /// item; the pre-`%nest` lexer read it as two and said nothing.
    @Test
    void nestedComment_containingASelectItem_swallowsIt() {
        assertThat(selectItems("""
                              SELECT 1 /* /* */ , 999 -- */
                               FROM t;
                              """))
                  .as("`, 999` is inside the comment: SELECT 1 FROM t, not SELECT 1, 999 FROM t")
                  .hasSize(1);
    }

    /// A `;` inside a nested comment must not terminate the statement — the property the corpus
    /// entry in `dml-select.sql` pins at script level, asserted here directly.
    @Test
    void nestedComment_containingASemicolon_doesNotSplitTheStatement() {
        assertThat(PARSER.parseScript("SELECT 1 /* outer /* inner */ still-comment ; */ AS c;")
                         .map(java.util.List::size)
                         .or(-1))
                  .as("the ; is commented out, so this is one statement")
                  .isEqualTo(1);
    }

    /// An unterminated block falls through to the ordinary DFA path, so malformed input reads as it
    /// always did. Verified identical with and without a `BlockComment` alternative present.
    @Test
    void unterminatedComment_isStillAnError() {
        assertThat(PARSER.parseCst("SELECT 1 /* unterminated AS c;").isFailure())
                  .as("%nest only changes the reading of comments that actually balance")
                  .isTrue();
    }

    /// Select-list items, or an empty list when the SQL does not parse. A parse failure surfacing
    /// as a size mismatch is fine here: [#nestedComment_parses] already asserts parseability
    /// separately, so the two failures cannot be confused.
    private static List<CstNavigator> selectItems(String sql) {
        return PARSER.parseCst(sql)
                     .map(node -> CstNavigator.of((CstNode.NonTerminal) node)
                                              .findAll("TargetElem"))
                     .or(List.of());
    }
}
