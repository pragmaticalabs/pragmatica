package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-STY-08: If/else with return in both branches.
///
/// Detects simple if/else where both branches consist of a single return statement.
/// Suggests using a conditional expression instead.
public class CstIfElseReturnRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-STY-08";

    /// Matches `if (...) { return ...; } else { return ...; }`
    /// Only single-statement branches (one return per branch, no side effects).
    /// Does NOT match else-if chains.
    private static final Pattern IF_ELSE_RETURN = Pattern.compile("if\\s*\\([^)]+\\)\\s*\\{\\s*return\\s+[^;]+;\\s*}\\s*else\\s*\\{\\s*return\\s+[^;]+;\\s*}",
                                                                  Pattern.DOTALL);

    /// Detects else-if chains — these should NOT be flagged
    private static final Pattern ELSE_IF = Pattern.compile("else\\s+if\\s*\\(");
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("\\b([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\(");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        return findAllStatements(root).stream()
                                .filter(this::isSimpleIfElseReturn)
                                .map(stmt -> createDiagnostic(root, stmt, ctx));
    }

    private boolean isSimpleIfElseReturn(Cursor stmt) {
        var stmtText = text(stmt).trim();

        if (!stmtText.startsWith("if ") && !stmtText.startsWith("if(")) {
            return false;
        }
        // Exclude else-if chains
        if (ELSE_IF.matcher(stmtText).find()) {
            return false;
        }

        return IF_ELSE_RETURN.matcher(stmtText).find();
    }

    private Diagnostic createDiagnostic(Cursor root, Cursor stmt, LintContext ctx) {
        var methodName = enclosingMethodMember(root, stmt).map(member -> extractMethodName(memberDeclText(member)))
                                     .or("(unknown)");

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(stmt),
                                     startColumn(stmt),
                                     "If/else with return in both branches in method '" + methodName
                                    + "' — simplify to conditional expression",
                                     "Both branches return immediately with no side effects. "
                                    + "Use a ternary or switch expression for cleaner code.")
                         .withExample("""
            // Before: if/else with return
            if (amount > 0) {
                return Result.success(amount);
            } else {
                return ValidationError.NEGATIVE.result();
            }

            // After: conditional expression
            return amount > 0
                ? Result.success(amount)
                : ValidationError.NEGATIVE.result();
            """);
    }

    private static String extractMethodName(String memberText) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);

        return matcher.find()
               ? matcher.group(1)
               : "(unknown)";
    }
}
