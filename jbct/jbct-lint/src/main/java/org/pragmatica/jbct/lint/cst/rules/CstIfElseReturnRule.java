package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.regex.Pattern;
import java.util.stream.Stream;

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
    private static final Pattern IF_ELSE_RETURN = Pattern.compile(
            "if\\s*\\([^)]+\\)\\s*\\{\\s*return\\s+[^;]+;\\s*}\\s*else\\s*\\{\\s*return\\s+[^;]+;\\s*}",
            Pattern.DOTALL);

    /// Detects else-if chains — these should NOT be flagged
    private static final Pattern ELSE_IF = Pattern.compile("else\\s+if\\s*\\(");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        var packageName = findFirst(root, RuleId.PackageDecl.class)
                .flatMap(pd -> findFirst(pd, RuleId.QualifiedName.class))
                .map(qn -> text(qn, source))
                .or("");
        if (!ctx.shouldLint(packageName)) {
            return Stream.empty();
        }
        return findAllStatements(root).stream()
                      .filter(stmt -> isSimpleIfElseReturn(stmt, source))
                      .map(stmt -> createDiagnostic(root, stmt, source, ctx));
    }

    private boolean isSimpleIfElseReturn(CstNode stmt, String source) {
        var stmtText = text(stmt, source).trim();
        if (!stmtText.startsWith("if ") && !stmtText.startsWith("if(")) {
            return false;
        }
        // Exclude else-if chains
        if (ELSE_IF.matcher(stmtText).find()) {
            return false;
        }
        return IF_ELSE_RETURN.matcher(stmtText).find();
    }

    private Diagnostic createDiagnostic(CstNode root, CstNode stmt, String source, LintContext ctx) {
        var methodName = findAncestor(root, stmt, RuleId.Member.class)
                .flatMap(md -> childByRule(md, RuleId.Identifier.class))
                .map(id -> text(id, source))
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
}
