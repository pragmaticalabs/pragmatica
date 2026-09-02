package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-STY-02: Prefer constructor references (X::new).
public class CstConstructorReferenceRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-STY-02";

    // Pattern to detect: v -> new Something(v) or (a,b) -> new Something(a,b)
    private static final Pattern CONSTRUCTOR_LAMBDA = Pattern.compile("\\w+\\s*->\\s*new\\s+\\w+\\s*\\(\\s*\\w+\\s*\\)");

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
                             .filter(this::isConstructorLambda)
                             .map(lambda -> createDiagnostic(lambda, ctx));
    }

    private boolean isConstructorLambda(Cursor lambda) {
        var lambdaText = text(lambda);

        return CONSTRUCTOR_LAMBDA.matcher(lambdaText).find();
    }

    private Diagnostic createDiagnostic(Cursor lambda, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(lambda),
                                     startColumn(lambda),
                                     "Use constructor reference X::new instead of v -> new X(v)",
                                     "Constructor references are more concise and readable.");
    }
}
