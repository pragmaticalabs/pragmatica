package org.pragmatica.jbct.lint.cst.rules;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.shape.MethodShapeClassifier;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-NEST-01: No nested monadic operations in lambdas.
///
/// Detects nested .map(), .flatMap(), .fold() calls inside lambda bodies,
/// which indicate complexity that should be extracted to a named method.
///
/// Thin delegator (#448): detection is the [MethodShapeClassifier#nestedOperationLambdas] facet —
/// the same per-lambda body analysis (a re-chain regex plus a monadic-op count of 2+) run over
/// `MapperSafety.blankNonCode`-masked lambda text. This rule owns only the gate and the diagnostic.
public class CstNestedOperationsRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-NEST-01";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        return MethodShapeClassifier.nestedOperationLambdas(root)
                                    .stream()
                                    .map(lambda -> createDiagnostic(lambda, ctx));
    }

    private Diagnostic createDiagnostic(Cursor lambda, LintContext ctx) {
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
