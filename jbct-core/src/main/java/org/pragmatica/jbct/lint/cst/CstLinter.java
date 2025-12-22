package org.pragmatica.jbct.lint.cst;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.rules.*;
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
            // Return kinds (JBCT-RET-*)
            new CstReturnKindRule(),         // JBCT-RET-01
            new CstNestedWrapperRule(),      // JBCT-RET-02
            new CstNullReturnRule(),         // JBCT-RET-03
            new CstVoidTypeRule(),           // JBCT-RET-04
            new CstAlwaysSuccessResultRule(), // JBCT-RET-05
            // Value objects (JBCT-VO-*)
            new CstValueObjectFactoryRule(), // JBCT-VO-01
            new CstConstructorBypassRule(),  // JBCT-VO-02
            // Exceptions (JBCT-EX-*)
            new CstNoBusinessExceptionsRule(), // JBCT-EX-01
            new CstOrElseThrowRule(),          // JBCT-EX-02
            // Naming (JBCT-NAM-*)
            new CstFactoryNamingRule(),      // JBCT-NAM-01
            new CstValidatedNamingRule(),    // JBCT-NAM-02
            // Lambda/composition (JBCT-LAM-*)
            new CstLambdaComplexityRule(),   // JBCT-LAM-01
            new CstLambdaBracesRule(),       // JBCT-LAM-02
            new CstLambdaTernaryRule(),      // JBCT-LAM-03
            // Use case structure (JBCT-UC-*)
            new CstNestedRecordFactoryRule(), // JBCT-UC-01
            // Patterns (JBCT-PAT-*, JBCT-SEQ-*)
            new CstRawLoopRule(),            // JBCT-PAT-01
            new CstChainLengthRule(),        // JBCT-SEQ-01
            // Style (JBCT-STY-*)
            new CstFluentFailureRule(),        // JBCT-STY-01
            new CstConstructorReferenceRule(), // JBCT-STY-02
            new CstFullyQualifiedNameRule(),   // JBCT-STY-03
            new CstUtilityClassRule(),         // JBCT-STY-04
            new CstMethodReferencePreferenceRule(), // JBCT-STY-05
            new CstImportOrderingRule(),       // JBCT-STY-06
            // Logging (JBCT-LOG-*)
            new CstConditionalLoggingRule(),   // JBCT-LOG-01
            new CstLoggerParameterRule(),      // JBCT-LOG-02
            // Architecture (JBCT-MIX-*)
            new CstDomainIoRule(),             // JBCT-MIX-01
            // Static imports (JBCT-STATIC-*)
            new CstStaticImportRule(),         // JBCT-STATIC-01
            // Utilities (JBCT-UTIL-*)
            new CstParsingUtilitiesRule(),     // JBCT-UTIL-01
            new CstVerifyPredicatesRule(),     // JBCT-UTIL-02
            // Nesting (JBCT-NEST-*)
            new CstNestedOperationsRule(),     // JBCT-NEST-01
            // Zones (JBCT-ZONE-*)
            new CstZoneTwoVerbsRule(),         // JBCT-ZONE-01
            new CstZoneThreeVerbsRule(),       // JBCT-ZONE-02
            new CstZoneMixingRule()            // JBCT-ZONE-03
        );
    }
}
