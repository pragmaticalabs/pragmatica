package org.pragmatica.jbct.lint.cst;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.rules.CstNullReturnRule;
import org.pragmatica.jbct.lint.cst.rules.CstOrElseThrowRule;
import org.pragmatica.jbct.lint.cst.rules.CstRawLoopRule;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.stream.Collectors;

/**
 * CST-based JBCT linter.
 *
 * Uses the generated Java25Parser and CST lint rules.
 */
public class CstLinter {

    private final LintContext context;
    private final List<CstLintRule> rules;
    private final Java25Parser parser;

    private CstLinter(LintContext context, List<CstLintRule> rules) {
        this.context = context;
        this.rules = rules;
        this.parser = new Java25Parser();
    }

    /**
     * Create linter with default rules.
     */
    public static CstLinter cstLinter() {
        return new CstLinter(LintContext.defaultContext(), defaultRules());
    }

    /**
     * Create linter with custom context.
     */
    public static CstLinter cstLinter(LintContext context) {
        return new CstLinter(context, defaultRules());
    }

    /**
     * Lint a source file.
     */
    public Result<List<Diagnostic>> lint(SourceFile source) {
        return parse(source)
            .map(cst -> analyzeWithRules(cst, source));
    }

    /**
     * Check if source passes lint rules.
     */
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

    private Result<CstNode> parse(SourceFile source) {
        var result = parser.parse(source.content());
        if (result.isSuccess()) {
            return result;
        }
        return Causes.cause("Parse error in " + source.fileName() + ": " + result).result();
    }

    private List<Diagnostic> analyzeWithRules(CstNode cst, SourceFile source) {
        var contextWithFile = context.withFileName(source.fileName());
        return rules.stream()
            .filter(rule -> contextWithFile.isRuleEnabled(rule.ruleId()))
            .flatMap(rule -> rule.analyze(cst, source.content(), contextWithFile))
            .collect(Collectors.toList());
    }

    private static List<CstLintRule> defaultRules() {
        return List.of(
            new CstNullReturnRule(),
            new CstOrElseThrowRule(),
            new CstRawLoopRule()
            // More rules will be added here
        );
    }
}
