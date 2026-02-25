package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-SEQ-01: Chain length limit (2-5 steps).
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
        if (!ctx.shouldLint(packageName)) {
            return Stream.empty();
        }
        // Find statements with long method chains
        return findAll(root, RuleId.Stmt.class).stream()
                      .filter(stmt -> countChainedCalls(stmt, source) > MAX_CHAIN_LENGTH)
                      .map(stmt -> createDiagnostic(stmt, source, ctx));
    }

    private int countChainedCalls(CstNode stmt, String source) {
        var stmtText = text(stmt, source);
        int count = 0;
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        for (int i = 0; i < stmtText.length(); i++) {
            char c = stmtText.charAt(i);
            if (c == '"' && !inChar && (i == 0 || stmtText.charAt(i - 1) != '\\')) {
                inString = !inString;
                continue;
            }
            if (c == '\'' && !inString && (i == 0 || stmtText.charAt(i - 1) != '\\')) {
                inChar = !inChar;
                continue;
            }
            if (inString || inChar) continue;
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == '.' && depth == 0) {
                if (isFollowedByMethodCall(stmtText, i)) {
                    count++;
                }
            }
        }
        return count;
    }

    private boolean isFollowedByMethodCall(String text, int dotIndex) {
        int j = dotIndex + 1;
        while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
        if (j >= text.length() || !Character.isJavaIdentifierStart(text.charAt(j))) return false;
        while (j < text.length() && Character.isJavaIdentifierPart(text.charAt(j))) j++;
        while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
        return j < text.length() && text.charAt(j) == '(';
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
