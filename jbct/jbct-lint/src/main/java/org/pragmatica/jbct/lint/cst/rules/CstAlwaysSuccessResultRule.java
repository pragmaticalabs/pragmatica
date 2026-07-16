package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-RET-05: Avoid methods that always return Result.success().
public class CstAlwaysSuccessResultRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-RET-05";
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
                             .filter(this::returnsResult)
                             .filter(this::alwaysReturnsSuccess)
                             .map(method -> createDiagnostic(method, ctx));
    }

    private boolean returnsResult(Cursor method) {
        return methodReturnType(method).map(type -> text(type))
                               .filter(typeText -> typeText.trim()
                                                           .startsWith("Result<"))
                               .isPresent();
    }

    private boolean alwaysReturnsSuccess(Cursor method) {
        var methodText = text(method);
        // Check if only uses Result.success() and never failure
        boolean hasSuccess = methodText.contains("Result.success(");
        boolean hasFailure = methodText.contains("Result.failure(") || methodText.contains(".result()") ||
        // cause.result()
        methodText.contains("failure");

        return hasSuccess && !hasFailure;
    }

    private Diagnostic createDiagnostic(Cursor method, LintContext ctx) {
        var methodName = extractMethodName(text(method));

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Method '" + methodName + "' always returns Result.success(); return T directly",
                                     "If a method can never fail, wrapping in Result adds unnecessary complexity.")
                         .withExample("""
            // Before: Result that never fails
            public static Result<Config> config(String name) {
                return Result.success(new Config(name));
            }

            // After: return T directly
            public static Config config(String name) {
                return new Config(name);
            }
            """);
    }

    private static String extractMethodName(String memberText) {
        // v6: Identifier is a token, not a CST rule. Extract method name via regex.
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);

        return matcher.find()
               ? matcher.group(1)
               : "(unknown)";
    }
}
