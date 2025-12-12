package org.pragmatica.jbct.lint;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import org.pragmatica.jbct.lint.rules.LintRule;
import org.pragmatica.jbct.lint.rules.NestedWrapperRule;
import org.pragmatica.jbct.lint.rules.ReturnKindRule;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.stream.Collectors;

/**
 * JBCT linter implementation.
 *
 * Analyzes Java source code for JBCT compliance using a set of configurable rules.
 */
public class JbctLinter implements Linter {

    private final LintContext context;
    private final List<LintRule> rules;
    private final JavaParser parser;

    private JbctLinter(LintContext context, List<LintRule> rules) {
        this.context = context;
        this.rules = rules;
        this.parser = createParser();
    }

    /**
     * Factory method with default context and all rules.
     */
    public static JbctLinter jbctLinter() {
        return new JbctLinter(LintContext.defaultContext(), defaultRules());
    }

    /**
     * Factory method with custom context.
     */
    public static JbctLinter jbctLinter(LintContext context) {
        return new JbctLinter(context, defaultRules());
    }

    /**
     * Factory method with custom context and rules.
     */
    public static JbctLinter jbctLinter(LintContext context, List<LintRule> rules) {
        return new JbctLinter(context, rules);
    }

    @Override
    public Result<List<Diagnostic>> lint(SourceFile source) {
        return parse(source)
                .map(cu -> analyzeWithRules(cu, source.fileName()));
    }

    @Override
    public Result<Boolean> check(SourceFile source) {
        return lint(source)
                .map(diagnostics -> {
                    var hasErrors = diagnostics.stream()
                            .anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR);
                    var hasWarnings = diagnostics.stream()
                            .anyMatch(d -> d.severity() == DiagnosticSeverity.WARNING);

                    if (hasErrors) {
                        return false;
                    }
                    if (context.config().failOnWarning() && hasWarnings) {
                        return false;
                    }
                    return true;
                });
    }

    @Override
    public LintContext context() {
        return context;
    }

    private Result<CompilationUnit> parse(SourceFile source) {
        var result = parser.parse(source.content());

        if (result.isSuccessful() && result.getResult().isPresent()) {
            return Result.success(result.getResult().get());
        }

        var message = result.getProblems().stream()
                .findFirst()
                .map(p -> p.getMessage())
                .orElse("Unknown parse error");

        return Causes.cause("Parse error in " + source.fileName() + ": " + message).result();
    }

    private List<Diagnostic> analyzeWithRules(CompilationUnit cu, String fileName) {
        return rules.stream()
                .filter(rule -> context.isRuleEnabled(rule.ruleId()))
                .flatMap(rule -> rule.analyze(cu, context))
                .collect(Collectors.toList());
    }

    private JavaParser createParser() {
        var configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        return new JavaParser(configuration);
    }

    private static List<LintRule> defaultRules() {
        return List.of(
                new ReturnKindRule(),
                new NestedWrapperRule()
        );
    }
}
