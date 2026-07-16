package org.pragmatica.jbct.lint.cst.rules;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-EX-02: Don't use orElseThrow().
///
/// Exception-based control flow is forbidden. Use Result/Option composition.
public class CstOrElseThrowRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-EX-02";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }
        // Find Primary nodes containing orElseThrow
        return findAll(root, RuleKind.PRIMARY).stream()
                      .filter(this::isOrElseThrow)
                      .map(node -> createDiagnostic(node, ctx));
    }

    private boolean isOrElseThrow(Cursor node) {
        return text(node).contains(".orElseThrow");
    }

    private Diagnostic createDiagnostic(Cursor node, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(node),
                                     startColumn(node),
                                     "orElseThrow() bypasses JBCT error handling",
                                     "Use Result/Option composition instead of throwing exceptions. "
                                    + "Exceptions break the functional pipeline.")
                         .withExample("""
            // Before: using orElseThrow
            User user = findUser(id).orElseThrow();

            // After: using composition
            return findUser(id)
                .map(this::processUser)
                .orElse(defaultUser);
            """);
    }
}
