// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.parser.grammar;

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

        assertThat(ruleKind).as("Unknown start rule: %s", startRule).isNotNull();

        var result = PgSqlParser.parseRuleFrom(PgSqlLexer.lex(input), 0, ruleKind);

        assertThat(result.isSuccess() && !result.hasErrors())
            .as("Should parse rule %s: [%s] but got: %s", startRule, input, result.diagnostics())
            .isTrue();
    }
}
