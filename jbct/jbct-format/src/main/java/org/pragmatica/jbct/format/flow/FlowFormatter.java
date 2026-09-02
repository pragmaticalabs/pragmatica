package org.pragmatica.jbct.format.flow;

import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.format.FormattingError;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Result;


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
    public SourceFile formatParsed(Cursor tree, SourceFile source) {
        return source.withContent(formatCst(tree, source.content()));
    }

    /// Check if a source file is already formatted.
    public Result<Boolean> isFormatted(SourceFile source) {
        return format(source).map(formatted -> formatted.content()
                                                        .equals(source.content()));
    }

    private Result<Cursor> parse(SourceFile source) {
        var parser = new Java25Parser();

        return parser.parse(source.content())
                     .mapError(cause -> FormattingError.parseFailed(source.fileName(),
                                                                    1,
                                                                    1,
                                                                    cause.message()));
    }

    private String formatCst(Cursor root, String source) {
        // Under v6 the CST is already flat — no nested same-rule wrappers, no need for
        // `flattenZomWrappers`. Each node carries its own leadingTrivia natively.
        var printer = new FlowPrinter(config, source);
        var flowResult = printer.print(root);

        return flowResult.formatted();
    }
}
