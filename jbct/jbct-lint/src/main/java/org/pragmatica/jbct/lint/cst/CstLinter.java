package org.pragmatica.jbct.lint.cst;

import java.util.List;
import java.util.stream.Collectors;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.rules.*;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Result;


/// CST-based JBCT linter.
///
///
/// Uses the generated Java25Parser and CST lint rules.
///
///
/// **Thread Safety:** Thread-safe for concurrent use. Each {@link #lint(SourceFile)}
/// call creates per-operation state for parsing. The lint rules are stateless and safe
/// for concurrent access. Instances can be safely shared across threads.
public class CstLinter {
    private final LintContext context;
    private final List<CstLintRule> rules;
    private final Java25Parser parser;

    private CstLinter(LintContext context, List<CstLintRule> rules) {
        this.context = context;
        this.rules = rules;
        this.parser = new Java25Parser();
    }

    /// Create linter with default rules.
    public static CstLinter cstLinter() {
        return new CstLinter(LintContext.defaultContext(), defaultRules());
    }

    /// Create linter with custom context.
    public static CstLinter cstLinter(LintContext context) {
        return new CstLinter(context, defaultRules());
    }

    /// Lint a source file.
    public Result<List<Diagnostic>> lint(SourceFile source) {
        return parser.parse(source.content())
                     .map(root -> analyzeWithRules(root, source));
    }

    /// Lint an already-parsed CST. Single-pass orchestrators (e.g. ProcessMojo) call
    /// this to avoid re-parsing when the parse tree is already in hand.
    public List<Diagnostic> lintParsed(Cursor root, SourceFile source) {
        return analyzeWithRules(root, source);
    }

    /// Check if source passes lint rules.
    public Result<Boolean> check(SourceFile source) {
        return lint(source).map(this::passesLintRules);
    }

    private boolean passesLintRules(List<Diagnostic> diagnostics) {
        var hasErrors = diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR);

        if (hasErrors) {
            return false;
        }

        var hasWarnings = diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.WARNING);

        return ! (context.config()
                         .failOnWarning() && hasWarnings);
    }

    private List<Diagnostic> analyzeWithRules(Cursor root, SourceFile source) {
        var contextWithFile = context.withFileName(source.fileName());
        // Extract @SuppressWarnings suppressions
        var suppressions = SuppressionExtractor.extractSuppressions(root, source.content());

        return rules.stream()
                    .filter(rule -> contextWithFile.isRuleEnabled(rule.ruleId()))
                    .flatMap(rule -> rule.analyze(root,
                                                  source.content(),
                                                  contextWithFile))
                    .filter(diagnostic -> !SuppressionExtractor.isSuppressed(suppressions,
                                                                             diagnostic.ruleId(),
                                                                             diagnostic.line()))
                    .collect(Collectors.toList());
    }

    /// Rule IDs emitted by the default rule set, in registration order.
    ///
    /// Derived from the actual rule instances so it cannot drift from the registry.
    /// Exposed so scoring reconciliation (see `RuleCategoryMapping`) can enumerate the
    /// live rules without duplicating the ID list.
    public static List<String> defaultRuleIds() {
        return defaultRules().stream()
                             .map(CstLintRule::ruleId)
                             .toList();
    }

    private static List<CstLintRule> defaultRules() {
        return List.of(
        // Return kinds (JBCT-RET-*)
        new CstReturnKindRule(),

        // JBCT-RET-01
        new CstNestedWrapperRule(),

        // JBCT-RET-02
        new CstNullReturnRule(),

        // JBCT-RET-03
        new CstVoidTypeRule(),

        // JBCT-RET-04
        new CstAlwaysSuccessResultRule(),

        // JBCT-RET-05
        new CstNullableParameterRule(),

        // JBCT-RET-06
        // Value objects (JBCT-VO-*)
        new CstValueObjectFactoryRule(),

        // JBCT-VO-01
        new CstConstructorBypassRule(),

        // JBCT-VO-02
        // Exceptions (JBCT-EX-*)
        new CstNoBusinessExceptionsRule(),

        // JBCT-EX-01
        new CstOrElseThrowRule(),

        // JBCT-EX-02
        // Naming (JBCT-NAM-*)
        new CstFactoryNamingRule(),

        // JBCT-NAM-01
        new CstValidatedNamingRule(),

        // JBCT-NAM-02
        // Lambda/composition (JBCT-LAM-*)
        new CstLambdaComplexityRule(),

        // JBCT-LAM-01
        new CstLambdaBracesRule(),

        // JBCT-LAM-02
        new CstLambdaTernaryRule(),

        // JBCT-LAM-03
        // Use case structure (JBCT-UC-*)
        new CstNestedRecordFactoryRule(),

        // JBCT-UC-01
        // Patterns (JBCT-PAT-*, JBCT-SEQ-*)
        new CstRawLoopRule(),

        // JBCT-PAT-01
        new CstChainLengthRule(),

        // JBCT-SEQ-01
        // Style (JBCT-STY-*)
        new CstFluentFailureRule(),

        // JBCT-STY-01
        new CstConstructorReferenceRule(),

        // JBCT-STY-02
        new CstFullyQualifiedNameRule(),

        // JBCT-STY-03
        new CstUtilityClassRule(),

        // JBCT-STY-04
        new CstMethodReferencePreferenceRule(),

        // JBCT-STY-05
        new CstImportOrderingRule(),

        // JBCT-STY-06
        // Logging (JBCT-LOG-*)
        new CstConditionalLoggingRule(),

        // JBCT-LOG-01
        new CstLoggerParameterRule(),

        // JBCT-LOG-02
        // Architecture (JBCT-MIX-*)
        new CstDomainIoRule(),

        // JBCT-MIX-01
        // Static imports (JBCT-STATIC-*)
        new CstStaticImportRule(),

        // JBCT-STATIC-01
        // Utilities (JBCT-UTIL-*)
        new CstParsingUtilitiesRule(),

        // JBCT-UTIL-01
        new CstVerifyPredicatesRule(),

        // JBCT-UTIL-02
        // Nesting (JBCT-NEST-*)
        new CstNestedOperationsRule(),

        // JBCT-NEST-01
        // Zones (JBCT-ZONE-*)
        new CstZoneTwoVerbsRule(),

        // JBCT-ZONE-01
        new CstZoneThreeVerbsRule(),

        // JBCT-ZONE-02
        new CstZoneMixingRule(),

        // JBCT-ZONE-03
        // Acronym naming (JBCT-ACR-*)
        new CstAcronymNamingRule(),

        // JBCT-ACR-01
        // Sealed interfaces (JBCT-SEAL-*)
        new CstSealedErrorRule(),

        // JBCT-SEAL-01
        // Pattern mixing (JBCT-PAT-02)
        new CstPatternMixingRule(),

        // Blocking await (JBCT-PAT-03)
        new CstAwaitRule(),

        // Discarded Result/Promise/Option (JBCT-RET-07)
        new CstDiscardedResultRule(),

        // Unnecessary var before return (JBCT-STY-07)
        new CstUnnecessaryVarReturnRule(),

        // If/else with return in both branches (JBCT-STY-08)
        new CstIfElseReturnRule(),

        // Mapper safety / totality (JBCT-TOT-*, #486)
        // Partial op in a mapper lambda (JBCT-TOT-01)
        new CstPartialOperationMapperRule(),

        // Partial method reference in a mapper (JBCT-TOT-02)
        new CstMapperMethodReferenceRule(),

        // Jackson wire-record accessor dereferences a possibly-null component (JBCT-TOT-03)
        new CstWireRecordTotalityRule());
    }
}
