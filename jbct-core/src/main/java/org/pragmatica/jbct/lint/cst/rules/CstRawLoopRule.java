package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-PAT-01: Use functional iteration instead of raw loops.
 *
 * Raw for/while/do loops should be replaced with stream operations.
 */
public class CstRawLoopRule implements CstLintRule {

    private static final String RULE_ID = "JBCT-PAT-01";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Use functional iteration instead of raw loops";
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

        // Find all loop statements
        return findAll(root, "Stmt").stream()
            .filter(stmt -> isLoopStatement(stmt, source))
            .map(stmt -> createDiagnostic(stmt, source, ctx));
    }

    private boolean isLoopStatement(CstNode stmt, String source) {
        var stmtText = text(stmt, source).trim();
        return stmtText.startsWith("for ") || stmtText.startsWith("for(") ||
               stmtText.startsWith("while ") || stmtText.startsWith("while(") ||
               stmtText.startsWith("do ");
    }

    private Diagnostic createDiagnostic(CstNode stmt, String source, LintContext ctx) {
        var stmtText = text(stmt, source).trim();
        var loopType = stmtText.startsWith("for") ? "for" :
                       stmtText.startsWith("while") ? "while" : "do-while";

        return Diagnostic.diagnostic(
            RULE_ID,
            ctx.severityFor(RULE_ID),
            ctx.fileName(),
            startLine(stmt),
            startColumn(stmt),
            "Raw " + loopType + " loop - prefer functional iteration",
            "JBCT prefers stream operations over imperative loops. " +
                "Use .stream().map/filter/forEach instead."
        ).withExample("""
            // Before: raw loop
            List<String> results = new ArrayList<>();
            for (User user : users) {
                results.add(user.getName());
            }

            // After: functional
            List<String> results = users.stream()
                .map(User::getName)
                .toList();
            """);
    }
}
