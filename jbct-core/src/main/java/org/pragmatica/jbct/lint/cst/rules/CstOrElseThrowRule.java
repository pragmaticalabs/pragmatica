package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-EX-02: Don't use orElseThrow().
 *
 * Exception-based control flow is forbidden. Use Result/Option composition.
 */
public class CstOrElseThrowRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-EX-02";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Don't use orElseThrow() - use Result/Option composition";
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
        // Find Primary nodes containing orElseThrow
        return findAll(root, RuleId.Primary.class)
               .stream()
               .filter(node -> isOrElseThrow(node, source))
               .map(node -> createDiagnostic(node, ctx));
    }

    private boolean isOrElseThrow(CstNode node, String source) {
        var nodeText = text(node, source);
        return nodeText.contains(".orElseThrow");
    }

    private Diagnostic createDiagnostic(CstNode node, LintContext ctx) {
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
