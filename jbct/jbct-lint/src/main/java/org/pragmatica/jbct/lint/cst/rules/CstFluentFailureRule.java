package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-STY-01: Prefer fluent failure style (cause.result()).
public class CstFluentFailureRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-STY-01";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }
        // Find Result.failure patterns (Primary doesn't include the parenthesis)
        return findAll(root, RuleKind.PRIMARY).stream()
                      .filter(node -> text(node).equals("Result.failure"))
                      .map(node -> createDiagnostic(node, ctx));
    }

    private Diagnostic createDiagnostic(Cursor node, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(node),
                                     startColumn(node),
                                     "Use cause.result() instead of Result.failure(cause)",
                                     "Fluent style improves readability: cause.result() reads naturally.");
    }
}
