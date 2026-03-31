package org.pragmatica.jbct.format.cst;

import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.format.FormattingError;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.List;

/// CST-based JBCT code formatter.
///
///
/// Uses the generated Java25Parser for parsing and preserves trivia (whitespace/comments).
///
///
/// **Thread Safety:** Thread-safe for concurrent use. While the underlying parser
/// and printer create per-operation state, instances of this class can be safely shared
/// across threads. Each {@link #format(SourceFile)} call creates its own parser and
/// printer instances internally.
public class CstFormatter {
    private final FormatterConfig config;
    private final Java25Parser parser;

    private CstFormatter(FormatterConfig config) {
        this.config = config;
        this.parser = new Java25Parser();
    }

    public static CstFormatter cstFormatter() {
        return new CstFormatter(FormatterConfig.defaultConfig());
    }

    public static CstFormatter cstFormatter(FormatterConfig config) {
        return new CstFormatter(config);
    }

    public Result<SourceFile> format(SourceFile source) {
        return parse(source).map(cst -> formatCst(cst,
                                                  source.content()))
                    .map(source::withContent);
    }

    public Result<Boolean> isFormatted(SourceFile source) {
        return format(source).map(formatted -> formatted.content()
                                                        .equals(source.content()));
    }

    private Result<CstNode> parse(SourceFile source) {
        var result = parser.parseWithDiagnostics(source.content());
        if (result.isSuccess()) {
            return result.node()
                         .toResult(FormattingError.parseFailed(source.fileName(),
                                                               1,
                                                               1,
                                                               "Parse error"));
        }
        return Option.option(result.diagnostics())
                     .filter(list -> !list.isEmpty())
                     .map(List::getFirst)
                     .map(d -> FormattingError.parseFailed(source.fileName(),
                                                           d.span()
                                                            .start()
                                                            .line(),
                                                           d.span()
                                                            .start()
                                                            .column(),
                                                           d.message()))
                     .or(FormattingError.parseFailed(source.fileName(),
                                                     1,
                                                     1,
                                                     "Parse error"))
                     .result();
    }

    private String formatCst(CstNode root, String source) {
        var flattened = flattenZomWrappers(root);
        var printer = new CstPrinter(config, source);
        return printer.print(flattened);
    }

    /// Flatten nested zero-or-more (zom) wrapper nodes in the CST.
    ///
    /// The PEG parser wraps 2+ matches of a zero-or-more production in a nested
    /// NonTerminal with the same rule as the parent. This breaks the printer which
    /// expects members/statements as direct children. This pass inlines such nested
    /// containers to produce a flat child list.
    private static CstNode flattenZomWrappers(CstNode node) {
        return switch (node) {
            case CstNode.NonTerminal nt -> {
                var flatChildren = new ArrayList<CstNode>();
                var changed = false;
                for (var child : nt.children()) {
                    var flattened = flattenZomWrappers(child);
                    if (flattened != child) {
                        changed = true;
                    }
                    // Inline children of nested NonTerminals with the same rule
                    if (flattened instanceof CstNode.NonTerminal nested
                        && nested.rule() != null
                        && nt.rule() != null
                        && nested.rule().getClass() == nt.rule().getClass()) {
                        flatChildren.addAll(nested.children());
                        changed = true;
                    } else {
                        flatChildren.add(flattened);
                    }
                }
                yield changed
                      ? new CstNode.NonTerminal(nt.span(), nt.rule(), flatChildren,
                                                nt.leadingTrivia(), nt.trailingTrivia())
                      : nt;
            }
            default -> node;
        };
    }
}
