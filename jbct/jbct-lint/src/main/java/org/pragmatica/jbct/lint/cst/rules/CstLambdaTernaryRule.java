package org.pragmatica.jbct.lint.cst.rules;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-LAM-03: No ternary in lambdas.
public class CstLambdaTernaryRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-LAM-03";

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
                             .filter(this::containsActualTernary)
                             .map(lambda -> createDiagnostic(lambda, ctx));
    }

    private boolean containsActualTernary(Cursor lambda) {
        return findAll(lambda, RuleKind.TERNARY).stream()
                      .anyMatch(CstLambdaTernaryRule::isActualTernary);
    }

    private static boolean isActualTernary(Cursor node) {
        return node instanceof Cursor.Branch b && b.children()
                                                   .count() > 1;
    }

    private Diagnostic createDiagnostic(Cursor lambda, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(lambda),
                                     startColumn(lambda),
                                     "Lambda contains ternary operator - use filter() or extract",
                                     "Ternary in lambdas reduces readability. Use filter() or extract to method.");
    }
}
