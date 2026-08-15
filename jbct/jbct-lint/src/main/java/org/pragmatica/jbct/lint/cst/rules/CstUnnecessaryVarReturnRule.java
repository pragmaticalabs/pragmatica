package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.CstNodes;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-STY-07: Unnecessary intermediate variable before return.
///
/// Detects `var x = expr; return x;` where `x` is not referenced elsewhere.
/// Suggests returning the expression directly.
public class CstUnnecessaryVarReturnRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-STY-07";
    private static final Pattern VAR_DECL_PATTERN = Pattern.compile("^\\s*(?:var|final\\s+var)\\s+(\\w+)\\s*=");
    private static final Pattern RETURN_VAR_PATTERN = Pattern.compile("^\\s*return\\s+(\\w+)\\s*;\\s*$");
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

        return findAll(root, CstNodes::isMemberDecl).stream()
                      .flatMap(method -> analyzeMethod(root, method, ctx));
    }

    private Stream<Diagnostic> analyzeMethod(Cursor root, Cursor method, LintContext ctx) {
        var statements = findAllStatements(method);
        var diagnostics = Stream.<Diagnostic> builder();

        for (int i = 0; i < statements.size() - 1; i++) {
            var current = statements.get(i);
            var next = statements.get(i + 1);
            var currentText = text(current).trim();
            var nextText = text(next).trim();
            var varMatcher = VAR_DECL_PATTERN.matcher(currentText);
            var returnMatcher = RETURN_VAR_PATTERN.matcher(nextText);

            if (!varMatcher.find() || !returnMatcher.find()) {
                continue;
            }

            var varName = varMatcher.group(1);
            var returnedName = returnMatcher.group(1);

            if (!varName.equals(returnedName)) {
                continue;
            }
            // Check that the variable is not referenced elsewhere in the method
            var methodText = text(method);
            var varRefPattern = Pattern.compile("\\b" + Pattern.quote(varName) + "\\b");
            var varRefMatcher = varRefPattern.matcher(methodText);
            var refCount = 0;

            while (varRefMatcher.find()) {
                refCount++;
            }
            // Expect exactly 2 references: the declaration and the return
            if (refCount <= 2) {
                diagnostics.add(createDiagnostic(root, current, varName, ctx));
            }
        }

        return diagnostics.build();
    }

    private Diagnostic createDiagnostic(Cursor root, Cursor stmt, String varName, LintContext ctx) {
        var methodName = enclosingMethodMember(root, stmt).map(member -> extractMethodName(memberDeclText(member)))
                                     .or("(unknown)");

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(stmt),
                                     startColumn(stmt),
                                     "Unnecessary variable '" + varName
                                    + "' in method '" + methodName
                                    + "' — return the expression directly",
                                     "The variable is only used to hold a value that is immediately returned. "
                                    + "Remove the intermediate variable and return the expression directly.")
                         .withExample("""
            // Before: unnecessary intermediate variable
            var result = computeValue();
            return result;

            // After: return directly
            return computeValue();
            """);
    }

    private static String extractMethodName(String memberText) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);

        return matcher.find()
               ? matcher.group(1)
               : "(unknown)";
    }
}
