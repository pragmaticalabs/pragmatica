package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-RET-04: Use Unit instead of Void.
 */
public class CstVoidTypeRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-RET-04";

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
        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }
        // Find methods returning Void (boxed)
        return findAll(root, RuleId.MethodDecl.class).stream()
                      .filter(method -> returnsBoxedVoid(method, source))
                      .map(method -> createDiagnostic(method, source, ctx));
    }

    private boolean returnsBoxedVoid(CstNode method, String source) {
        return childByRule(method, RuleId.Type.class).map(type -> text(type, source).trim())
                          .filter(typeText -> typeText.equals("Void") || typeText.contains("<Void>"))
                          .isPresent();
    }

    private Diagnostic createDiagnostic(CstNode method, String source, LintContext ctx) {
        var methodName = childByRule(method, RuleId.Identifier.class).map(id -> text(id, source))
                                    .or("(unknown)");
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Method '" + methodName + "' uses Void; use Unit instead",
                                     "JBCT uses Unit instead of Void for side-effect returns.");
    }
}
