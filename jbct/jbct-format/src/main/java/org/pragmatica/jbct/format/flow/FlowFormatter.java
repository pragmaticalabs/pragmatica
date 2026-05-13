package org.pragmatica.jbct.format.flow;

import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.format.FormattingError;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;
import org.pragmatica.jbct.parser.Java25Parser.Trivia;
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

    /// Format a source file using the flow-based approach.
    ///
    /// Note: callers are responsible for size and exclude filtering. The Mojo path uses
    /// `FileCollector` (driven by `[files] maxFileSize` and `[files] excludes` in
    /// `jbct.toml`); direct library callers (e.g. `jbct-cli`) decide their own policy.
    /// This method has no internal size cap — passing a multi-MB source will parse and
    /// format that source.
    public Result<SourceFile> format(SourceFile source) {
        return parse(source).map(cst -> formatParsed(cst, source));
    }

    /// Format an already-parsed CST. Single-pass orchestrators (e.g. ProcessMojo) call
    /// this to avoid re-parsing when the parse tree is already in hand.
    /// Caller is responsible for size limits — this entry point performs no size check.
    public SourceFile formatParsed(CstNode tree, SourceFile source) {
        return source.withContent(formatCst(tree, source.content()));
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
                var inner = (CstNode.NonTerminal) flattened;
                var innerKids = inner.children();
                // Preserve inner's leadingTrivia: it represents trivia attached to the
                // inlined wrapper (e.g., first-member docs in a nested ClassBody) — without
                // forwarding, those comments would be lost when the wrapper is dropped.
                if (!inner.leadingTrivia().isEmpty() && !innerKids.isEmpty()) {
                    var firstKid = innerKids.get(0);
                    var merged = new ArrayList<>(inner.leadingTrivia());
                    merged.addAll(firstKid.leadingTrivia());
                    innerKids = new ArrayList<>(innerKids);
                    innerKids.set(0, attachLeadingTrivia(firstKid, List.copyOf(merged)));
                }
                flatChildren.addAll(innerKids);
                changed = true;
            } else {
                flatChildren.add(flattened);
            }
        }
        return changed
               ? new CstNode.NonTerminal(nt.id(), nt.span(), nt.rule(), flatChildren,
                                         nt.leadingTrivia(), nt.trailingTrivia())
               : nt;
    }

    private static CstNode attachLeadingTrivia(CstNode node, List<Trivia> leading) {
        return switch (node) {
            case CstNode.NonTerminal n -> new CstNode.NonTerminal(n.id(), n.span(), n.rule(), n.children(), leading, n.trailingTrivia());
            case CstNode.Terminal t -> new CstNode.Terminal(t.id(), t.span(), t.rule(), t.textSpan(), leading, t.trailingTrivia());
            case CstNode.Token tok -> new CstNode.Token(tok.id(), tok.span(), tok.rule(), tok.textSpan(), leading, tok.trailingTrivia());
            case CstNode.Error e -> new CstNode.Error(e.id(), e.span(), e.skippedText(), e.expected(), leading, e.trailingTrivia());
        };
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
