package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-LAM-02: No braces in lambdas.
public class CstLambdaBracesRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-LAM-02";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }
        return findAllLambdas(root).stream()
                      .filter(this::hasBlockBody)
                      .map(lambda -> createDiagnostic(lambda, ctx));
    }

    private boolean hasBlockBody(Cursor lambda) {
        // Check if lambda has a block body (contains { after ->)
        var lambdaText = text(lambda);
        var arrowIndex = lambdaText.indexOf("->");
        if (arrowIndex < 0) return false;
        var afterArrow = lambdaText.substring(arrowIndex + 2)
                                   .trim();
        return afterArrow.startsWith("{");
    }

    private Diagnostic createDiagnostic(Cursor lambda, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(lambda),
                                     startColumn(lambda),
                                     "Lambda has block body - extract to a method reference",
                                     "Lambdas should be single expressions. Extract block bodies to methods.");
    }
}
