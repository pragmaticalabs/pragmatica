package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-NEST-01: No nested monadic operations in lambdas.
 *
 * Detects nested .map(), .flatMap(), .fold() calls inside lambda bodies,
 * which indicate complexity that should be extracted to a named method.
 */
public class CstNestedOperationsRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-NEST-01";

    // Monadic operations that shouldn't be nested
    private static final Set<String> MONADIC_OPS = Set.of("map",
                                                          "flatMap",
                                                          "fold",
                                                          "recover",
                                                          "filter",
                                                          "mapFailure",
                                                          "onSuccess",
                                                          "onFailure");

    // Pattern to find lambda with nested operations
    private static final Pattern LAMBDA_PATTERN = Pattern.compile("->\\s*\\{?[^}]*\\.(map|flatMap|fold|recover|filter|mapFailure)\\s*\\(");

    // Pattern to find nested chain inside lambda
    private static final Pattern NESTED_CHAIN_PATTERN = Pattern.compile("\\)\\s*\\.(map|flatMap|fold|recover|filter)\\s*\\(");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        var packageName = findFirst(root, RuleId.PackageDecl.class).flatMap(pd -> findFirst(pd,
                                                                                            RuleId.QualifiedName.class))
                                   .map(qn -> text(qn, source))
                                   .or("");
        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }
        // Find lambdas with nested operations
        return findAll(root, RuleId.Lambda.class).stream()
                      .filter(lambda -> hasNestedOperations(lambda, source))
                      .map(lambda -> createDiagnostic(lambda, source, ctx));
    }

    private boolean hasNestedOperations(CstNode lambda, String source) {
        var lambdaText = text(lambda, source);
        // Skip simple lambdas (single expression without chains)
        if (!lambdaText.contains("->")) {
            return false;
        }
        // Get the body part after ->
        var arrowIndex = lambdaText.indexOf("->");
        if (arrowIndex < 0) {
            return false;
        }
        var body = lambdaText.substring(arrowIndex + 2);
        // Check for nested monadic operations in the body
        var matcher = NESTED_CHAIN_PATTERN.matcher(body);
        if (matcher.find()) {
            return true;
        }
        // Count monadic operations - more than 1 indicates nesting
        var opCount = 0;
        for (var op : MONADIC_OPS) {
            var opPattern = Pattern.compile("\\." + op + "\\s*\\(");
            var opMatcher = opPattern.matcher(body);
            while (opMatcher.find()) {
                opCount++;
                if (opCount > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private Diagnostic createDiagnostic(CstNode lambda, String source, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(lambda),
                                     startColumn(lambda),
                                     "Nested monadic operations in lambda - extract to named method",
                                     "Lambda bodies should be simple. Extract complex chains to a named method "
                                     + "for better readability and testability.");
    }
}
