package org.pragmatica.jbct.format.flow;

import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.format.FormattingError;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.List;

/// Flow-based JBCT formatter.
///
/// Single-pass approach: format purely from code structure + width measurement.
/// Comments are emitted inline alongside their associated tokens but never
/// influence layout decisions (breaks, alignment, width).
///
/// This eliminates TriviaMode, hasNewlinesInTrivia checks, and trivia stabilization bugs.
///
/// **Thread Safety:** Each format call creates its own parser instance, so concurrent
/// use from multiple threads is safe.
public class FlowFormatter {
    private final FormatterConfig config;

    private FlowFormatter(FormatterConfig config) {
        this.config = config;
    }

    /// Factory method with custom config.
    public static FlowFormatter flowFormatter(FormatterConfig config) {
        return new FlowFormatter(config);
    }

    /// Factory method with default config.
    public static FlowFormatter flowFormatter() {
        return new FlowFormatter(FormatterConfig.defaultConfig());
    }

    private static final int MAX_SOURCE_SIZE = 1_024 * 1_024;

    /// Format a source file using the flow-based approach.
    /// Files exceeding 1MB are returned unchanged (generated files like parsers).
    public Result<SourceFile> format(SourceFile source) {
        if (source.content().length() > MAX_SOURCE_SIZE) {
            return Result.success(source);
        }
        return parse(source)
            .map(cst -> formatCst(cst, source.content()))
            .map(source::withContent);
    }

    /// Check if a source file is already formatted.
    public Result<Boolean> isFormatted(SourceFile source) {
        return format(source).map(formatted -> formatted.content().equals(source.content()));
    }

    private Result<CstNode> parse(SourceFile source) {
        var parser = new Java25Parser();
        var result = parser.parseWithDiagnostics(source.content());
        if (result.isSuccess()) {
            return result.node()
                .toResult(FormattingError.parseFailed(source.fileName(), 1, 1, "Parse error"));
        }
        return Option.option(result.diagnostics())
            .filter(list -> !list.isEmpty())
            .map(List::getFirst)
            .map(d -> FormattingError.parseFailed(source.fileName(),
                d.span().start().line(),
                d.span().start().column(),
                d.message()))
            .or(FormattingError.parseFailed(source.fileName(), 1, 1, "Parse error"))
            .result();
    }

    private String formatCst(CstNode root, String source) {
        var flattened = flattenZomWrappers(root);

        // Single pass: format structure with inline comment emission.
        // Comments are emitted alongside their associated tokens but never
        // influence layout decisions (breaks, alignment, width measurement).
        var printer = new FlowPrinter(config, source);
        var flowResult = printer.print(flattened);

        return flowResult.formatted();
    }

    /// Flatten nested zero-or-more (zom) wrapper nodes in the CST.
    ///
    /// The PEG parser wraps 2+ matches of a zero-or-more production in a nested
    /// NonTerminal with the same rule as the parent. This breaks the printer which
    /// expects members/statements as direct children. This pass inlines such nested
    /// containers to produce a flat child list.
    private static CstNode flattenZomWrappers(CstNode node) {
        return switch (node) {
            case CstNode.NonTerminal nt -> flattenNonTerminal(nt);
            default -> node;
        };
    }

    private static CstNode flattenNonTerminal(CstNode.NonTerminal nt) {
        var flatChildren = new ArrayList<CstNode>();
        var changed = false;
        for (var child : nt.children()) {
            var flattened = flattenZomWrappers(child);
            if (flattened != child) {
                changed = true;
            }
            if (shouldInlineChild(flattened, nt)) {
                flatChildren.addAll(((CstNode.NonTerminal) flattened).children());
                changed = true;
            } else {
                flatChildren.add(flattened);
            }
        }
        return changed
               ? new CstNode.NonTerminal(nt.span(), nt.rule(), flatChildren,
                                         nt.leadingTrivia(), nt.trailingTrivia())
               : nt;
    }

    private static boolean shouldInlineChild(CstNode flattened, CstNode.NonTerminal parent) {
        return flattened instanceof CstNode.NonTerminal nested
            && nested.rule() != null
            && parent.rule() != null
            && (nested.rule().getClass() == parent.rule().getClass()
                || (parent.rule() instanceof RuleId.CompilationUnit
                    && nested.rule() instanceof RuleId.OrdinaryUnit));
    }
}
