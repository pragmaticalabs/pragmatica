package org.pragmatica.jbct.parser;

import org.pragmatica.jbct.parser.v6.Java25Lexer;
import org.pragmatica.jbct.parser.v6.Java25ParserV6;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.diagnostic.Diagnostic;

import java.util.List;

/// Consumer-facing entry point for the v6 Java 25 parser. Wraps the
/// `Java25Lexer.lex` + `Java25ParserV6.parse` pipeline and exposes a `Cursor` rooted at
/// the parse tree's root. On error, returns a `ParseError` cause aggregating the v6
/// diagnostics.
public final class Java25Parser {

    public Result<Cursor> parse(String input) {
        var tokens = Java25Lexer.lex(input);
        var result = Java25ParserV6.parse(tokens);
        if (result.hasErrors() || !result.isSuccess()) {
            return Result.failure(new ParseError(result.diagnostics()));
        }
        return Result.success(Cursor.root(result.cst()));
    }

    public record ParseError(List<Diagnostic> diagnostics) implements Cause {
        @Override
        public String message() {
            if (diagnostics.isEmpty()) {
                return "Parse failed (no diagnostics)";
            }
            var first = diagnostics.get(0);
            var more = diagnostics.size() > 1
                ? " (+%d more)".formatted(diagnostics.size() - 1)
                : "";
            return "Parse failed: %s%s".formatted(first, more);
        }
    }
}
