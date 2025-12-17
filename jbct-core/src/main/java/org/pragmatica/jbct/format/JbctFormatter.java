package org.pragmatica.jbct.format;

import org.pragmatica.jbct.format.cst.CstFormatter;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Result;

/**
 * JBCT code formatter implementation.
 *
 * Formats Java source code according to JBCT style rules:
 * - Method chains align to receiver end
 * - Multi-line arguments align to opening paren
 * - 120 character max line length
 * - 4 space indentation
 *
 * Uses CST-based formatter for trivia (whitespace/comments) preservation.
 */
public class JbctFormatter implements Formatter {

    private final FormatterConfig config;
    private final CstFormatter delegate;

    private JbctFormatter(FormatterConfig config) {
        this.config = config;
        this.delegate = CstFormatter.cstFormatter(config);
    }

    /**
     * Factory method for creating a formatter with default config.
     */
    public static JbctFormatter jbctFormatter() {
        return new JbctFormatter(FormatterConfig.defaultConfig());
    }

    /**
     * Factory method for creating a formatter with custom config.
     */
    public static JbctFormatter jbctFormatter(FormatterConfig config) {
        return new JbctFormatter(config);
    }

    @Override
    public Result<SourceFile> format(SourceFile source) {
        return delegate.format(source);
    }

    @Override
    public Result<Boolean> isFormatted(SourceFile source) {
        return delegate.isFormatted(source);
    }

    @Override
    public FormatterConfig config() {
        return config;
    }
}
