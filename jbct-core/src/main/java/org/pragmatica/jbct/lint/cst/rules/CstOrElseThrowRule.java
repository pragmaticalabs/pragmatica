package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;

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
        var packageName = findFirst(root, "PackageDecl")
            .flatMap(pd -> findFirst(pd, "QualifiedName"))
            .map(qn -> text(qn, source))
            .or("");

        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }

        // Find all method calls
        return findAll(root, "PostOp").stream()
            .filter(op -> isOrElseThrow(op, source))
            .map(op -> createDiagnostic(op, ctx));
    }

    private boolean isOrElseThrow(CstNode op, String source) {
        var opText = text(op, source);
        return opText.contains(".orElseThrow") || opText.startsWith(".orElseThrow");
    }

    private Diagnostic createDiagnostic(CstNode node, LintContext ctx) {
        return Diagnostic.diagnostic(
            RULE_ID,
            ctx.severityFor(RULE_ID),
            ctx.fileName(),
            startLine(node),
            startColumn(node),
            "orElseThrow() bypasses JBCT error handling",
            "Use Result/Option composition instead of throwing exceptions. " +
                "Exceptions break the functional pipeline."
        ).withExample("""
            // Before: using orElseThrow
            User user = findUser(id).orElseThrow();

            // After: using composition
            return findUser(id)
                .map(this::processUser)
                .orElse(defaultUser);
            """);
    }
}
