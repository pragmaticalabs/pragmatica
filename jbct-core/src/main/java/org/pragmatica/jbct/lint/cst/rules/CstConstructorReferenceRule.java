package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-STY-02: Prefer constructor references (X::new).
 */
public class CstConstructorReferenceRule implements CstLintRule {

    private static final String RULE_ID = "JBCT-STY-02";
    // Pattern to detect: v -> new Something(v) or (a,b) -> new Something(a,b)
    private static final Pattern CONSTRUCTOR_LAMBDA = Pattern.compile(
        "\\w+\\s*->\\s*new\\s+\\w+\\s*\\(\\s*\\w+\\s*\\)"
    );

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Prefer X::new over v -> new X(v)";
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
            .filter(lambda -> isConstructorLambda(lambda, source))
            .map(lambda -> createDiagnostic(lambda, ctx));
    }

    private boolean isConstructorLambda(CstNode lambda, String source) {
        var lambdaText = text(lambda, source);
        return CONSTRUCTOR_LAMBDA.matcher(lambdaText).find();
    }

    private Diagnostic createDiagnostic(CstNode lambda, LintContext ctx) {
        return Diagnostic.diagnostic(
            RULE_ID,
            ctx.severityFor(RULE_ID),
            ctx.fileName(),
            startLine(lambda),
            startColumn(lambda),
            "Use constructor reference X::new instead of v -> new X(v)",
            "Constructor references are more concise and readable."
        );
    }
}
