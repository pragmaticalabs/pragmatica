package org.pragmatica.aether.pg.parser.ast.common;

import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;


/// A SQL identifier that tracks quoting style.
/// Unquoted identifiers are case-folded to lowercase per PostgreSQL rules.
/// Quoted identifiers preserve their original case.
public record Identifier(SourceSpan span, String value, QuoteStyle style) {
    public enum QuoteStyle {
        UNQUOTED,
        DOUBLE_QUOTED,
        UNICODE_QUOTED
    }

    public String normalized() {
        return style == QuoteStyle.UNQUOTED
              ? value.toLowerCase()
              : value;
    }

    public static Identifier unquoted(SourceSpan span, String value) {
        return new Identifier(span, value, QuoteStyle.UNQUOTED);
    }

    public static Identifier quoted(SourceSpan span, String value) {
        return new Identifier(span, value, QuoteStyle.DOUBLE_QUOTED);
    }

    @Override public String toString() {
        return style == QuoteStyle.UNQUOTED
              ? normalized()
              : "\"" + value + "\"";
    }
}
