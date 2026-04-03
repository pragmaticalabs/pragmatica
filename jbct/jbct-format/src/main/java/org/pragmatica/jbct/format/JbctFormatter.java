package org.pragmatica.jbct.format;

import org.pragmatica.jbct.format.flow.FlowFormatter;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Result;

/// JBCT code formatter implementation.
///
/// Formats Java source code according to JBCT style rules:
/// - Method chains align to receiver end
/// - Multi-line arguments align to opening paren
/// - 120 character max line length
/// - 4 space indentation
///
/// Uses flow-based formatter that makes all layout decisions from code structure and width
/// measurement only. Comments are emitted inline but never influence layout decisions.
public class JbctFormatter {
    private final FlowFormatter delegate;

    private JbctFormatter(FormatterConfig config) {
        this.delegate = FlowFormatter.flowFormatter(config);
    }

    /// Factory method for creating a formatter with default config.
    public static JbctFormatter jbctFormatter() {
        return new JbctFormatter(FormatterConfig.defaultConfig());
    }

    /// Factory method for creating a formatter with custom config.
    public static JbctFormatter jbctFormatter(FormatterConfig config) {
        return new JbctFormatter(config);
    }

    public Result<SourceFile> format(SourceFile source) {
        return delegate.format(source);
    }

    public Result<Boolean> isFormatted(SourceFile source) {
        return delegate.isFormatted(source);
    }
}
