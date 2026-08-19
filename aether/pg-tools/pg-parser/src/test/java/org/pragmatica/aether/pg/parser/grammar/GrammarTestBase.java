// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.parser.grammar;

import java.util.ArrayList;

import org.pragmatica.aether.pg.parser.PgSqlLexer;
import org.pragmatica.aether.pg.parser.PgSqlParser;
import org.pragmatica.aether.pg.parser.PostgresParser;

import static org.assertj.core.api.Assertions.assertThat;

/// Grammar-level assertions: whole statements through the facade, single rules through the
/// generated parser's rule entry point.
///
/// Rule tests previously ran on peglib's INTERPRETED parser (`PegParser.fromGrammar(...)`
/// + `parseCst(input, startRule)`). peglib 0.7.x has no rule-specific entry on the interpreted
/// parser, and the generated parser gained one (`parseRuleFrom` + `ruleKinds()`), so they now
/// run against the compiled artifact — the same code that ships, rather than a second
/// interpretation of the same grammar.
class GrammarTestBase {
    static final PostgresParser PARSER = PostgresParser.create();

    static void assertParses(String sql) {
        var result = PARSER.parseCst(sql);
        assertThat(result.isSuccess()).as("Should parse: [%s] but got: %s", sql, result).isTrue();
    }

    static void assertParsesRule(String input, String startRule) {
        var ruleKind = PgSqlParser.ruleKinds()
                                  .get(startRule);

        if (ruleKind == null) {
            assertLexesAsOneToken(input, startRule);
            return;
        }

        var result = PgSqlParser.parseRuleFrom(PgSqlLexer.lex(input), 0, ruleKind);

        assertThat(result.isSuccess() && !result.hasErrors())
            .as("Should parse rule %s: [%s] but got: %s", startRule, input, result.diagnostics())
            .isTrue();
    }

    /// Lex-level assertion for rules the classifier owns as LEXER rules, which therefore have no
    /// parser entry point — `ruleKinds()` lists PARSER rules only, so `ColId` / `NumericLiteral` /
    /// `BooleanLiteral` are absent by design under lex-then-parse, not by mistake.
    ///
    /// Asserts the property that survives the split: the input is exactly ONE lexeme covering the
    /// whole input. It deliberately does NOT assert which kind was produced — an identifier-fallback
    /// rule like `ColId` is never emitted as its own kind, it accepts a SET of kinds
    /// (`UnquotedIdentifier`, `QuotedIdentifier`, and every non-reserved keyword), so pinning the
    /// kind name here would assert the opposite of how the mechanism works.
    ///
    /// Weaker than the 0.6.0 parser-level check: it cannot see whether the rule is REACHABLE where
    /// the grammar expects it. That gap is covered by the statement-level tests and by the
    /// `ZCstDumpTest` differential against the 0.6.0 baseline, which is what caught the unreachable
    /// `ColId` in the first place.
    static void assertLexesAsOneToken(String input, String ruleName) {
        var tokens = PgSqlLexer.lex(input);
        var lexemes = new ArrayList<Integer>();

        for (var i = 0; i < tokens.count(); i++) {
            if (!tokens.isTrivia(i)) {
                lexemes.add(i);
            }
        }

        var kinds = lexemes.stream()
                           .map(tokens::kindName)
                           .toList();

        assertThat(lexemes)
            .as("Rule %s: [%s] should lex as exactly one token, got %s", ruleName, input, kinds)
            .hasSize(1);

        var only = lexemes.getFirst();

        assertThat(tokens.startAt(only))
            .as("Rule %s: [%s] token should start at 0", ruleName, input)
            .isZero();
        assertThat(tokens.endAt(only))
            .as("Rule %s: [%s] token should cover the whole input", ruleName, input)
            .isEqualTo(input.length());
    }
}
