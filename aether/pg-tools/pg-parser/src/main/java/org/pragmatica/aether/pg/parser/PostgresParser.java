// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.cst.CstArray;
import org.pragmatica.peg.diagnostic.Diagnostic;
import org.pragmatica.peg.token.TokenArray;


/// Facade over the generated PostgreSQL parser.
///
/// The [CstNode] tree below is the ONLY parser shape the rest of pg-tools sees — 22 files across
/// `pg-parser`, `pg-schema` and `pg-codegen` read it and nothing else. It is deliberately
/// independent of peglib's own types so a generator change stays contained here, which is what
/// let the 0.5.x-era self-contained parser be replaced by peglib 0.7.x's tokens-first
/// lexer + parser without touching a single consumer.
///
/// **Shape mapping.** 0.7.x keeps only RULE nodes in the CST and holds tokens in a separate
/// [TokenArray], where the old generator inlined tokens as tree nodes. The converter therefore
/// re-interleaves them: for each rule node it walks that node's token range and emits every
/// token not covered by a child node, in source order, so the reconstructed tree carries the
/// same token children in the same positions as before. Trivia (whitespace and comments) is
/// skipped, as it was previously.
///
/// [CstNode.Terminal] vs [CstNode.Token] preserves the old distinction: an anonymous inline
/// literal from the grammar (`'('`, `','`) is a `Terminal` named `literal`, while a named token
/// rule (`CreateKW`, `UnquotedIdentifier`) is a `Token` named after the rule. 0.7.x spells the
/// former as an `INLINE_…` token kind, which is what [#isInlineLiteral] keys on.
public final class PostgresParser {
    public sealed interface CstNode permits CstNode.Terminal, CstNode.NonTerminal, CstNode.Token, CstNode.Error {
        SourceSpan span();
        String ruleName();

        record Terminal(SourceSpan span, String ruleName, String text) implements CstNode {}

        record NonTerminal(SourceSpan span, String ruleName, List<CstNode> children) implements CstNode {}

        record Token(SourceSpan span, String ruleName, String text) implements CstNode {}

        record Error(SourceSpan span, String skippedText, String expected) implements CstNode {
            @Override
            public String ruleName() {
                return "<error>";
            }
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

    /// Prefix the generator gives an anonymous inline literal's token kind.
    private static final String INLINE_PREFIX = "INLINE_";
    private static final String LITERAL_RULE = "literal";

    private PostgresParser() {}

    public static PostgresParser create() {
        return new PostgresParser();
    }

    public Result<CstNode> parseCst(String sql) {
        var tokens = PgSqlLexer.lex(sql);
        var result = PgSqlParser.parse(tokens);

        if (!result.isSuccess() || result.hasErrors()) {
            return new ParseError(result.diagnostics(), sql).result();
        }

        var cst = result.cst();

        return Result.success(new Converter(cst, new LineMap(sql)).convert(cst.rootIndex()));
    }

    public Result<List<CstNode>> parseScript(String sql) {
        return parseCst(sql).map(PostgresParser::extractStatements);
    }

    /// Parse failure carrying peglib's diagnostics, rendered with the `expected … at line:column`
    /// shape the previous generator produced.
    public record ParseError(List<Diagnostic> diagnostics, String input) implements Cause {
        @Override
        public String message() {
            if (diagnostics.isEmpty()) {
                return "Parse failed (no diagnostics)";
            }

            var first = diagnostics.getFirst();
            var at = new LineMap(input).locationAt(first.offset());
            var expected = first.expected() == null || first.expected().isEmpty()
                           ? first.message()
                           : "expected " + first.expected();
            var more = diagnostics.size() > 1
                       ? " (+%d more)".formatted(diagnostics.size() - 1)
                       : "";

            return "%s at %d:%d%s".formatted(expected, at.line(), at.column(), more);
        }

        public <T> Result<T> result() {
            return Result.failure(this);
        }
    }

    /// Rebuilds the facade tree from a [CstArray], re-interleaving the tokens that 0.7.x keeps
    /// outside the tree.
    private record Converter(CstArray cst, LineMap lines) {
        CstNode convert(int nodeIdx) {
            var span = spanOf(cst.spanStart(nodeIdx), cst.spanEnd(nodeIdx));

            if (cst.isError(nodeIdx)) {
                return new CstNode.Error(span, cst.textAt(nodeIdx).toString(), "");
            }

            return new CstNode.NonTerminal(span, cst.kindNameAt(nodeIdx), childrenOf(nodeIdx));
        }

        /// Child rule nodes and the tokens between them, in source order.
        private List<CstNode> childrenOf(int nodeIdx) {
            var children = new ArrayList<CstNode>();
            var tokens = cst.tokens();
            var cursor = cst.firstTokenAt(nodeIdx);
            var lastToken = cst.lastTokenAt(nodeIdx);

            for (int child = cst.firstChildAt(nodeIdx); child != CstArray.NO_NODE; child = cst.nextSiblingAt(child)) {
                appendTokens(children, tokens, cursor, cst.firstTokenAt(child) - 1);
                children.add(convert(child));
                cursor = cst.lastTokenAt(child) + 1;
            }

            appendTokens(children, tokens, cursor, lastToken);

            return List.copyOf(children);
        }

        private void appendTokens(List<CstNode> out, TokenArray tokens, int from, int to) {
            for (int t = Math.max(from, 0); t <= to && t < tokens.count(); t++) {
                if (tokens.isTrivia(t)) {
                    continue;
                }

                var span = spanOf(tokens.startAt(t), tokens.endAt(t));
                var text = tokens.textAt(t).toString();
                var kind = tokens.kindName(t);

                out.add(isInlineLiteral(kind)
                        ? new CstNode.Terminal(span, LITERAL_RULE, text)
                        : new CstNode.Token(span, kind, text));
            }
        }

        private SourceSpan spanOf(int start, int end) {
            return new SourceSpan(lines.locationAt(start), lines.locationAt(end));
        }
    }

    private static boolean isInlineLiteral(String tokenKind) {
        return tokenKind.startsWith(INLINE_PREFIX);
    }

    /// Offset → line/column, which the facade's spans carry and the token array does not.
    private record LineMap(int[] lineStarts) {
        LineMap(String input) {
            this(startsOf(input));
        }

        private static int[] startsOf(String input) {
            var starts = new ArrayList<Integer>();

            starts.add(0);
            for (int i = 0; i < input.length(); i++) {
                if (input.charAt(i) == '\n') {
                    starts.add(i + 1);
                }
            }

            return starts.stream()
                         .mapToInt(Integer::intValue)
                         .toArray();
        }

        SourceLocation locationAt(int offset) {
            var found = Arrays.binarySearch(lineStarts, offset);
            var index = found >= 0
                        ? found
                        : -found - 2;

            return new SourceLocation(index + 1, offset - lineStarts[index] + 1, offset);
        }
    }

    private static List<CstNode> extractStatements(CstNode root) {
        return switch (unwrapRoot(root)) {
            case CstNode.NonTerminal nt -> nt.children().stream().filter(child -> switch (child) {
                case CstNode.NonTerminal c -> !c.ruleName().equals("EmptyStatement");
                default -> false;
            }).toList();
            default -> List.of(root);
        };
    }

    /// peglib 0.7.x wraps the whole parse in a synthetic `_ROOT` node above the grammar's start
    /// rule; 0.6.0 handed back `Input` directly. Without unwrapping, every script reports exactly
    /// one statement — the `Input` node itself — instead of the statements inside it.
    private static CstNode unwrapRoot(CstNode node) {
        return node instanceof CstNode.NonTerminal nt && nt.ruleName().equals("_ROOT")
               ? nt.children()
                   .stream()
                   .filter(CstNode.NonTerminal.class::isInstance)
                   .findFirst()
                   .orElse(node)
               : node;
    }
}
