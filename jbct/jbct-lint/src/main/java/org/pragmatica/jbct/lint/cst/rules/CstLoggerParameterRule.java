package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-LOG-02: No logger as method parameter.
public class CstLoggerParameterRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-LOG-02";
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
        return findAllMethods(root).stream()
                      .filter(this::hasLoggerParameter)
                      .map(method -> createDiagnostic(method, ctx));
    }

    private boolean hasLoggerParameter(Cursor method) {
        var methodText = text(method);
        return methodText.contains("Logger ") && methodText.contains("(") &&
        methodText.indexOf("Logger ") < methodText.indexOf(")");
    }

    private Diagnostic createDiagnostic(Cursor method, LintContext ctx) {
        var methodName = extractMethodName(text(method));
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Method '" + methodName + "' has Logger parameter - use class-level logger",
                                     "Each component should own its logger as a final field.");
    }

    private static String extractMethodName(String memberText) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);
        return matcher.find() ? matcher.group(1) : "(unknown)";
    }
}
