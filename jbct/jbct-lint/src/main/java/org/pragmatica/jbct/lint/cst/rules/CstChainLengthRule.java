package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-SEQ-01: Chain length limit (2-5 steps).
 */
public class CstChainLengthRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-SEQ-01";
    private static final int MAX_CHAIN_LENGTH = 5;

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
        // Find statements with long method chains
        return findAll(root, RuleId.Stmt.class).stream()
                      .filter(stmt -> countChainedCalls(stmt, source) > MAX_CHAIN_LENGTH)
                      .map(stmt -> createDiagnostic(stmt, source, ctx));
    }

    private int countChainedCalls(CstNode stmt, String source) {
        var stmtText = text(stmt, source);
        // Count occurrences of .methodName( pattern
        int count = 0;
        int idx = 0;
        while ((idx = stmtText.indexOf(".", idx)) != - 1) {
            // Check if followed by method call pattern
            int nextParen = stmtText.indexOf("(", idx);
            if (nextParen > idx && nextParen - idx < 50) {
                count++;
            }
            idx++;
        }
        return count;
    }

    private Diagnostic createDiagnostic(CstNode stmt, String source, LintContext ctx) {
        var chainLength = countChainedCalls(stmt, source);
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(stmt),
                                     startColumn(stmt),
                                     "Method chain has " + chainLength + " steps (max " + MAX_CHAIN_LENGTH + ")",
                                     "Long chains reduce readability. Split into intermediate variables or extract methods.");
    }
}
