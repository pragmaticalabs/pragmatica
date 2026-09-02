package org.pragmatica.jbct.lint.cst.rules;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-PAT-01: Use functional iteration instead of raw loops.
///
/// Raw for/while/do loops should be replaced with stream operations.
public class CstRawLoopRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-PAT-01";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }
        // Find all loop statements
        return findAllStatements(root).stream()
                                .filter(this::isLoopStatement)
                                .map(stmt -> createDiagnostic(stmt, ctx));
    }

    private boolean isLoopStatement(Cursor stmt) {
        var stmtText = text(stmt).trim();
        // Check for traditional for loop (exclude enhanced for-each which contains ":")
        if (stmtText.startsWith("for ") || stmtText.startsWith("for(")) {
            // Enhanced for-each has colon before the closing paren
            var parenClose = stmtText.indexOf(')');

            if (parenClose > 0) {
                var header = stmtText.substring(0, parenClose);

                if (header.contains(":")) {
                    return false;
                }
            }

            return true;
        }

        return stmtText.startsWith("while ") || stmtText.startsWith("while(") || stmtText.startsWith("do ");
    }

    private Diagnostic createDiagnostic(Cursor stmt, LintContext ctx) {
        var stmtText = text(stmt).trim();
        var loopType = stmtText.startsWith("for")
                       ? "for"
                       : stmtText.startsWith("while")
                         ? "while"
                         : "do-while";

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(stmt),
                                     startColumn(stmt),
                                     "Raw " + loopType + " loop - prefer functional iteration",
                                     "JBCT prefers stream operations over imperative loops. "
                                    + "Use .stream().map/filter/forEach instead.")
                         .withExample("""
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
