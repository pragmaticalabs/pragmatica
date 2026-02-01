package org.pragmatica.jbct.format.cst;

import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.format.FormattingError;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Result;

import java.util.List;

/**
 * CST-based JBCT code formatter.
 *
 * <p>Uses the generated Java25Parser for parsing and preserves trivia (whitespace/comments).
 *
 * <p><b>Thread Safety:</b> Thread-safe for concurrent use. While the underlying parser
 * and printer create per-operation state, instances of this class can be safely shared
 * across threads. Each {@link #format(SourceFile)} call creates its own parser and
 * printer instances internally.
 */
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
                         .toResult(FormattingError.parseFailed(source.fileName(), 1, 1, "Parse error"));
        }
        return result.diagnostics()
                     .stream()
                     .findFirst()
                     .map(d -> FormattingError.parseFailed(source.fileName(),
                                                          d.span()
                                                           .start()
                                                           .line(),
                                                          d.span()
                                                           .start()
                                                           .column(),
                                                          d.message()))
                     .orElse(FormattingError.parseFailed(source.fileName(), 1, 1, "Parse error"))
                     .result();
    }

    private String formatCst(CstNode root, String source) {
        var printer = new CstPrinter(config, source);
        return printer.print(root);
    }
}
