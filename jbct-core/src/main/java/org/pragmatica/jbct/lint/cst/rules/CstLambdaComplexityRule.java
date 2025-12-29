package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-LAM-01: No complex logic in lambdas.
 */
public class CstLambdaComplexityRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-LAM-01";

    @Override
    public String ruleId() {
        return RULE_ID;
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
        return findAll(root, RuleId.Lambda.class)
               .stream()
               .filter(lambda -> hasComplexLogic(lambda, source))
               .map(lambda -> createDiagnostic(lambda, ctx));
    }

    private boolean hasComplexLogic(CstNode lambda, String source) {
        var lambdaText = text(lambda, source);
        return lambdaText.contains("if ") || lambdaText.contains("if(") ||
        lambdaText.contains("switch ") || lambdaText.contains("switch(") ||
        lambdaText.contains("try ") || lambdaText.contains("try{");
    }

    private Diagnostic createDiagnostic(CstNode lambda, LintContext ctx) {
        return Diagnostic.diagnostic(
        RULE_ID,
        ctx.severityFor(RULE_ID),
        ctx.fileName(),
        startLine(lambda),
        startColumn(lambda),
        "Lambda contains complex logic - extract to a method",
        "Lambdas should be simple expressions. Extract complex logic to named methods.");
    }
}
