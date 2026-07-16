package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-RET-04: Use Unit instead of Void.
public class CstVoidTypeRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-RET-04";
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
        // Find methods returning Void (boxed)
        return findAllMethods(root).stream()
                             .filter(this::returnsBoxedVoid)
                             .map(method -> createDiagnostic(method, ctx));
    }

    private boolean returnsBoxedVoid(Cursor method) {
        return methodReturnType(method).map(type -> text(type).trim())
                               .filter(typeText -> typeText.equals("Void") || typeText.contains("<Void>"))
                               .isPresent();
    }

    private Diagnostic createDiagnostic(Cursor method, LintContext ctx) {
        var methodName = extractMethodName(text(method));

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Method '" + methodName + "' uses Void; use Unit instead",
                                     "JBCT uses Unit instead of Void for side-effect returns.");
    }

    private static String extractMethodName(String memberText) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);

        return matcher.find()
               ? matcher.group(1)
               : "(unknown)";
    }
}
