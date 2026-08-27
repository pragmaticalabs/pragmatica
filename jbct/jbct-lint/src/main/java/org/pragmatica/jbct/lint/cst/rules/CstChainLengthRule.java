package org.pragmatica.jbct.lint.cst.rules;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-SEQ-01: Chain length limit (2-5 steps).
///
/// A chain is counted as the depth-0 `.name(` occurrences in ONE statement, so a nested lambda's
/// dots — which sit inside the enclosing call's parentheses — are correctly ignored. A LOCAL TYPE
/// DECLARATION is a statement too, and its text is an entire type body with no enclosing
/// parentheses: counting it summed every chain of every method it declares into one phantom chain
/// reported on the declaration line (a slice implementation record read as an 80-step chain, #645).
/// Local type declarations are therefore excluded outright — the statements inside their method
/// bodies are visited in their own right, so nothing real is lost.
///
/// Known limit (tracked separately): the count is a statement TOTAL, not the longest single chain
/// in it, so a `switch` or ternary with several short chains in its arms sums them and can report a
/// chain nobody wrote. Fixing that is a change to the measurement itself, not a scoping filter.
public class CstChainLengthRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-SEQ-01";
    private static final int MAX_CHAIN_LENGTH = 5;

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }
        // Find statements with long method chains
        return findAllStatements(root).stream()
                                .filter(stmt -> !isLocalTypeDeclaration(stmt))
                                .filter(stmt -> countChainedCalls(stmt) > MAX_CHAIN_LENGTH)
                                .map(stmt -> createDiagnostic(stmt, ctx));
    }

    /// A statement that declares a local class/record/interface/enum — its text is a type body, not
    /// an expression, so it is not a chain at any length.
    private boolean isLocalTypeDeclaration(Cursor stmt) {
        return hasChildOfRule(stmt, RuleKind.LOCAL_TYPE_DECL);
    }

    private int countChainedCalls(Cursor stmt) {
        var stmtText = text(stmt);
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

    private Diagnostic createDiagnostic(Cursor stmt, LintContext ctx) {
        var chainLength = countChainedCalls(stmt);

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(stmt),
                                     startColumn(stmt),
                                     "Method chain has " + chainLength + " steps (max " + MAX_CHAIN_LENGTH + ")",
                                     "Long chains reduce readability. Split into intermediate variables or extract methods.");
    }
}
