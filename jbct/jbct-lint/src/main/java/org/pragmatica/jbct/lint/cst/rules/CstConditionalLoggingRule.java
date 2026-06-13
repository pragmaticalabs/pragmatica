package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-LOG-01: No conditional logging.
public class CstConditionalLoggingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-LOG-01";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }
        // Find if statements wrapping log calls
        return findAllStatements(root).stream()
                      .filter(this::isConditionalLogging)
                      .map(stmt -> createDiagnostic(stmt, ctx));
    }

    private boolean isConditionalLogging(Cursor stmt) {
        var stmtText = text(stmt);
        if (!stmtText.startsWith("if ") && !stmtText.startsWith("if(")) {
            return false;
        }
        // Check for log level checks and logging calls
        return ( stmtText.contains("isDebugEnabled") ||
        stmtText.contains("isTraceEnabled") ||
        stmtText.contains("isInfoEnabled")) &&
        (stmtText.contains(".debug(") ||
        stmtText.contains(".trace(") ||
        stmtText.contains(".info("));
    }

    private Diagnostic createDiagnostic(Cursor stmt, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(stmt),
                                     startColumn(stmt),
                                     "Conditional logging detected - let log level filter instead",
                                     "Modern loggers handle level filtering efficiently. Remove the if check.");
    }
}
