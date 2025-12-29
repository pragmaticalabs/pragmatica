package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-LOG-01: No conditional logging.
 */
public class CstConditionalLoggingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-LOG-01";

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
        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }
        // Find if statements wrapping log calls
        return findAll(root, RuleId.Stmt.class)
               .stream()
               .filter(stmt -> isConditionalLogging(stmt, source))
               .map(stmt -> createDiagnostic(stmt, ctx));
    }

    private boolean isConditionalLogging(CstNode stmt, String source) {
        var stmtText = text(stmt, source);
        if (!stmtText.startsWith("if ") && !stmtText.startsWith("if(")) {
            return false;
        }
        // Check for log level checks and logging calls
        return (stmtText.contains("isDebugEnabled") ||
        stmtText.contains("isTraceEnabled") ||
        stmtText.contains("isInfoEnabled")) &&
        (stmtText.contains(".debug(") ||
        stmtText.contains(".trace(") ||
        stmtText.contains(".info("));
    }

    private Diagnostic createDiagnostic(CstNode stmt, LintContext ctx) {
        return Diagnostic.diagnostic(
        RULE_ID,
        ctx.severityFor(RULE_ID),
        ctx.fileName(),
        startLine(stmt),
        startColumn(stmt),
        "Conditional logging detected - let log level filter instead",
        "Modern loggers handle level filtering efficiently. Remove the if check.");
    }
}
