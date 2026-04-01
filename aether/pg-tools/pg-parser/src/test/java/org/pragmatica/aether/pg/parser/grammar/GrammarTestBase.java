package org.pragmatica.aether.pg.parser.grammar;

import org.pragmatica.aether.pg.parser.PostgresGrammar;
import org.pragmatica.aether.pg.parser.PostgresParser;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.parser.Parser;

import static org.assertj.core.api.Assertions.assertThat;

/// Uses generated parser for full statements, interpreted parser for rule-specific tests.
class GrammarTestBase {
    static final PostgresParser PARSER = PostgresParser.create();
    static final Parser INTERPRETED = PegParser.fromGrammar(PostgresGrammar.GRAMMAR).unwrap();

    static void assertParses(String sql) {
        var result = PARSER.parseCst(sql);
        assertThat(result.isSuccess()).as("Should parse: [%s] but got: %s", sql, result).isTrue();
    }

    static void assertParsesRule(String input, String startRule) {
        var result = INTERPRETED.parseCst(input, startRule);
        assertThat(result.isSuccess()).as("Should parse rule %s: [%s] but got: %s", startRule, input, result).isTrue();
    }
}
