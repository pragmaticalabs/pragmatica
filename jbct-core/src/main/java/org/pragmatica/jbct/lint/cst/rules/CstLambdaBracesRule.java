package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-LAM-02: No braces in lambdas.
 */
public class CstLambdaBracesRule implements CstLintRule {

    private static final String RULE_ID = "JBCT-LAM-02";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "No braces in lambda bodies - extract complex logic to methods";
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        var packageName = findFirst(root, RuleId.PackageDecl.class)
            .flatMap(pd -> findFirst(pd, RuleId.QualifiedName.class))
            .map(qn -> text(qn, source))
            .or("");

        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }

        return findAll(root, RuleId.Lambda.class).stream()
            .filter(lambda -> hasBlockBody(lambda, source))
            .map(lambda -> createDiagnostic(lambda, ctx));
    }

    private boolean hasBlockBody(CstNode lambda, String source) {
        // Check if lambda has a block body (contains { after ->)
        var lambdaText = text(lambda, source);
        var arrowIndex = lambdaText.indexOf("->");
        if (arrowIndex < 0) return false;
        var afterArrow = lambdaText.substring(arrowIndex + 2).trim();
        return afterArrow.startsWith("{");
    }

    private Diagnostic createDiagnostic(CstNode lambda, LintContext ctx) {
        return Diagnostic.diagnostic(
            RULE_ID,
            ctx.severityFor(RULE_ID),
            ctx.fileName(),
            startLine(lambda),
            startColumn(lambda),
            "Lambda has block body - extract to a method reference",
            "Lambdas should be single expressions. Extract block bodies to methods."
        );
    }
}
