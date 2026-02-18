package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        var packageName = findFirst(root, RuleId.PackageDecl.class).flatMap(pd -> findFirst(pd,
                                                                                            RuleId.QualifiedName.class))
                                   .map(qn -> text(qn, source))
                                   .or("");
        if (!ctx.shouldLint(packageName)) {
            return Stream.empty();
        }
        return findAll(root, RuleId.MethodDecl.class).stream()
                      .flatMap(method -> analyzeMethod(method, source, ctx));
    }

    private Stream<Diagnostic> analyzeMethod(CstNode method, String source, LintContext ctx) {
        // Get parameter names
        var paramNames = extractParameterNames(method, source);
        if (paramNames.isEmpty()) {
            return Stream.empty();
        }
        // Get method body
        var bodyOpt = findFirst(method, RuleId.Block.class);
        if (bodyOpt.isEmpty()) {
            return Stream.empty();
        }
        var bodyText = text(bodyOpt.unwrap(), source);
        // Find parameters checked for null
        var nullCheckedParams = findNullCheckedParams(bodyText, paramNames);
        if (nullCheckedParams.isEmpty()) {
            return Stream.empty();
        }
        // Create diagnostic for first null-checked param (limit noise)
        var firstParam = nullCheckedParams.iterator()
                                          .next();
        return Stream.of(createDiagnostic(method, firstParam, source, ctx));
    }

    private Set<String> extractParameterNames(CstNode method, String source) {
        var names = new HashSet<String>();
        var params = findAll(method, RuleId.Param.class);
        for (var param : params) {
            // Get the identifier (parameter name) from the param
            findFirst(param, RuleId.Identifier.class).map(id -> text(id, source).trim())
                     .onPresent(names::add);
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

    private Diagnostic createDiagnostic(CstNode method, String paramName, String source, LintContext ctx) {
        var methodName = childByRule(method, RuleId.Identifier.class).map(id -> text(id, source))
                                    .or("(unknown)");
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Parameter '" + paramName + "' in method '" + methodName
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
}
