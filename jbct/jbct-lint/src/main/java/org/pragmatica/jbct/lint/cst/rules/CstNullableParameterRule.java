package org.pragmatica.jbct.lint.cst.rules;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-RET-06: No nullable parameters - use Option instead.
///
/// Detects method parameters that are checked for null inside the method body,
/// indicating they should be `Option<T>` instead.
///
/// Detected patterns:
///
///   - `if (param == null)`
///   - `if (null == param)`
///   - `param == null`
///   - `param != null`
///
public class CstNullableParameterRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-RET-06";

    // Pattern to find null checks: "paramName == null" or "null == paramName" or "paramName != null"
    private static final Pattern NULL_CHECK_PATTERN = Pattern.compile("\\b(\\w+)\\s*[!=]=\\s*null\\b|\\bnull\\s*[!=]=\\s*(\\w+)\\b");

    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("\\b([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\(");
    // Param shape: optional modifiers/annotations + type tokens + identifier (last word before , or end)
    private static final Pattern PARAM_NAME_PATTERN = Pattern.compile("\\b([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*(?:,|$)");

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
                             .flatMap(method -> analyzeMethod(method, ctx));
    }

    private Stream<Diagnostic> analyzeMethod(Cursor method, LintContext ctx) {
        // Get parameter names
        var paramNames = extractParameterNames(method);

        if (paramNames.isEmpty()) {
            return Stream.empty();
        }
        // Get method body
        var bodyOpt = findFirst(method, RuleKind.BLOCK);

        if (bodyOpt.isEmpty()) {
            return Stream.empty();
        }

        var bodyText = text(bodyOpt.unwrap());
        // Find parameters checked for null
        var nullCheckedParams = findNullCheckedParams(bodyText, paramNames);

        if (nullCheckedParams.isEmpty()) {
            return Stream.empty();
        }
        // Create diagnostic for first null-checked param (limit noise)
        var firstParam = nullCheckedParams.iterator().next();

        return Stream.of(createDiagnostic(method, firstParam, ctx));
    }

    private Set<String> extractParameterNames(Cursor method) {
        var names = new HashSet<String>();
        var params = findAll(method, RuleKind.PARAM);

        for (var param : params) {
            // Parameter name is a token (Identifier) in v6; recover it from param text:
            // last identifier (the trailing one) in the param declaration.
            var paramText = text(param).trim();
            var matcher = PARAM_NAME_PATTERN.matcher(paramText);
            String last = null;

            while (matcher.find()) {
                last = matcher.group(1);
            }

            if (last != null && !last.isEmpty()) {
                names.add(last);
            }
        }

        return names;
    }

    private Set<String> findNullCheckedParams(String bodyText, Set<String> paramNames) {
        var nullChecked = new HashSet<String>();
        var matcher = NULL_CHECK_PATTERN.matcher(bodyText);

        while (matcher.find()) {
            // Group 1 is "param == null", Group 2 is "null == param"
            var name = matcher.group(1) != null
                       ? matcher.group(1)
                       : matcher.group(2);

            if (name != null && paramNames.contains(name)) {
                nullChecked.add(name);
            }
        }

        return nullChecked;
    }

    private Diagnostic createDiagnostic(Cursor method, String paramName, LintContext ctx) {
        var methodName = extractMethodName(text(method));

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Parameter '" + paramName
                                    + "' in method '" + methodName
                                    + "' is checked for null - use Option<T> instead",
                                     "JBCT code uses Option<T> for optional values, not nullable parameters.")
                         .withExample("""
            // Before: nullable parameter
            public Config merge(Config other) {
                if (other == null) {
                    return this;
                }
                // ...
            }

            // After: Option parameter
            public Config merge(Option<Config> other) {
                return other.map(o -> /* merge logic */)
                            .or(this);
            }
            """);
    }

    private static String extractMethodName(String memberText) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);

        return matcher.find()
               ? matcher.group(1)
               : "(unknown)";
    }
}
