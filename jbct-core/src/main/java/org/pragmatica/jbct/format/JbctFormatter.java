package org.pragmatica.jbct.format;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import org.pragmatica.jbct.format.printer.JbctPrinter;
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
 */
public class JbctFormatter implements Formatter {

    private final FormatterConfig config;
    private final JavaParser parser;

    private JbctFormatter(FormatterConfig config) {
        this.config = config;
        this.parser = createParser();
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
        return parse(source)
                .map(this::formatAst)
                .map(source::withContent);
    }

    @Override
    public Result<Boolean> isFormatted(SourceFile source) {
        return format(source)
                .map(formatted -> formatted.content().equals(source.content()));
    }

    @Override
    public FormatterConfig config() {
        return config;
    }

    private Result<CompilationUnit> parse(SourceFile source) {
        var result = parser.parse(source.content());

        if (result.isSuccessful() && result.getResult().isPresent()) {
            return Result.success(result.getResult().get());
        }

        var problem = result.getProblems().stream()
                .findFirst()
                .orElse(null);

        if (problem != null) {
            var location = problem.getLocation()
                    .map(l -> l.getBegin())
                    .orElse(null);

            int line = location != null ? location.getRange().map(r -> r.begin.line).orElse(1) : 1;
            int column = location != null ? location.getRange().map(r -> r.begin.column).orElse(1) : 1;

            return FormattingError.parseError(
                    source.fileName(),
                    line,
                    column,
                    problem.getMessage()
            ).result();
        }

        return FormattingError.parseError(source.fileName(), 1, 1, "Unknown parse error").result();
    }

    private String formatAst(CompilationUnit cu) {
        var printer = JbctPrinter.jbctPrinter(config);
        return printer.print(cu);
    }

    private JavaParser createParser() {
        var configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        return new JavaParser(configuration);
    }
}
