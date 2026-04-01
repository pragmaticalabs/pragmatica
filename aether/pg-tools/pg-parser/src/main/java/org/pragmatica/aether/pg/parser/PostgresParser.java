package org.pragmatica.aether.pg.parser;

import org.pragmatica.lang.Result;

import java.util.List;

/// PostgreSQL SQL parser using the generated standalone PEG parser.
/// Peglib is NOT a runtime dependency — only pragmatica-lite:core.
public final class PostgresParser {

    /// Unified CST node types — insulate downstream code from generated parser internals.
    public sealed interface CstNode permits
            CstNode.Terminal, CstNode.NonTerminal, CstNode.Token, CstNode.Error {

        SourceSpan span();
        String ruleName();

        record Terminal(SourceSpan span, String ruleName, String text) implements CstNode {}
        record NonTerminal(SourceSpan span, String ruleName, List<CstNode> children) implements CstNode {}
        record Token(SourceSpan span, String ruleName, String text) implements CstNode {}
        record Error(SourceSpan span, String skippedText, String expected) implements CstNode {
            @Override public String ruleName() { return "<error>"; }
        }
    }

    public record SourceLocation(int line, int column, int offset) {
        public static final SourceLocation START = new SourceLocation(1, 1, 0);
    }

    public record SourceSpan(SourceLocation start, SourceLocation end) {
        public static SourceSpan of(SourceLocation start, SourceLocation end) {
            return new SourceSpan(start, end);
        }

        @Override
        public String toString() {
            return start.line() + ":" + start.column() + "-" + end.line() + ":" + end.column();
        }
    }

    private final PgSqlParser parser;

    private PostgresParser() {
        this.parser = new PgSqlParser();
    }

    public static PostgresParser create() {
        return new PostgresParser();
    }

    public Result<CstNode> parseCst(String sql) {
        return parser.parse(sql).map(PostgresParser::convert);
    }

    public Result<List<CstNode>> parseScript(String sql) {
        return parseCst(sql).map(PostgresParser::extractStatements);
    }

    private static CstNode convert(PgSqlParser.CstNode node) {
        return switch (node) {
            case PgSqlParser.CstNode.NonTerminal nt -> new CstNode.NonTerminal(
                convertSpan(nt.span()),
                ruleName(nt.rule()),
                nt.children().stream().map(PostgresParser::convert).toList()
            );
            case PgSqlParser.CstNode.Token tok -> new CstNode.Token(
                convertSpan(tok.span()),
                ruleName(tok.rule()),
                tok.text()
            );
            case PgSqlParser.CstNode.Terminal term -> new CstNode.Terminal(
                convertSpan(term.span()),
                ruleName(term.rule()),
                term.text()
            );
            case PgSqlParser.CstNode.Error err -> new CstNode.Error(
                convertSpan(err.span()),
                err.skippedText(),
                err.expected()
            );
        };
    }

    private static SourceSpan convertSpan(PgSqlParser.SourceSpan span) {
        return new SourceSpan(
            new SourceLocation(span.start().line(), span.start().column(), span.start().offset()),
            new SourceLocation(span.end().line(), span.end().column(), span.end().offset())
        );
    }

    private static String ruleName(PgSqlParser.RuleId rule) {
        return rule != null ? rule.name() : "";
    }

    private static List<CstNode> extractStatements(CstNode root) {
        return switch (root) {
            case CstNode.NonTerminal nt -> nt.children().stream()
                .filter(child -> switch (child) {
                    case CstNode.NonTerminal c -> !c.ruleName().equals("EmptyStatement");
                    default -> false;
                })
                .toList();
            default -> List.of(root);
        };
    }
}
