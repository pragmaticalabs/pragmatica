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
 * Uses the generated Java25Parser for parsing and preserves trivia (whitespace/comments).
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
        return parse(source)
            .map(cst -> formatCst(cst, source.content()))
            .map(source::withContent);
    }

    public Result<Boolean> isFormatted(SourceFile source) {
        return format(source)
            .map(formatted -> formatted.content().equals(source.content()));
    }

    private Result<CstNode> parse(SourceFile source) {
        var result = parser.parse(source.content());
        if (result.isSuccess()) {
            return result;
        }
        return FormattingError.parseError(source.fileName(), 1, 1, "Parse error").result();
    }

    private String formatCst(CstNode root, String source) {
        var printer = new CstPrinter(config, source);
        return printer.print(root);
    }
}
