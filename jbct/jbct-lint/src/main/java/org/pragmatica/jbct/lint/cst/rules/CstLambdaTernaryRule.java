package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-LAM-03: No ternary in lambdas.
public class CstLambdaTernaryRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-LAM-03";

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
        if (!ctx.shouldLint(packageName)) {
            return Stream.empty();
        }
        return findAll(root, RuleId.Lambda.class).stream()
                      .filter(this::containsActualTernary)
                      .map(lambda -> createDiagnostic(lambda, ctx));
    }

    private boolean containsActualTernary(CstNode lambda) {
        return findAll(lambda, RuleId.Ternary.class).stream()
                      .anyMatch(CstLambdaTernaryRule::isActualTernary);
    }

    private static boolean isActualTernary(CstNode node) {
        return node instanceof CstNode.NonTerminal nt && nt.children().size() > 1;
    }

    private Diagnostic createDiagnostic(CstNode lambda, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(lambda),
                                     startColumn(lambda),
                                     "Lambda contains ternary operator - use filter() or extract",
                                     "Ternary in lambdas reduces readability. Use filter() or extract to method.");
    }
}
