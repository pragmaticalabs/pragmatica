package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-STY-05: Prefer method references over equivalent lambdas.
 *
 * Detects lambdas that could be simplified to method references:
 * - x -> new Type(x) → Type::new
 * - x -> x.method() → Type::method
 * - x -> Type.method(x) → Type::method
 * - x -> obj.method(x) → obj::method
 */
public class CstMethodReferencePreferenceRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-STY-05";

    // Pattern: x -> new Type(x) or (x) -> new Type(x)
    private static final Pattern CONSTRUCTOR_LAMBDA = Pattern.compile(
        "\\(?\\s*(\\w+)\\s*\\)?\\s*->\\s*new\\s+(\\w+)\\s*\\(\\s*\\1\\s*\\)");

    // Pattern: x -> x.method() or (x) -> x.method()
    private static final Pattern INSTANCE_METHOD_LAMBDA = Pattern.compile(
        "\\(?\\s*(\\w+)\\s*\\)?\\s*->\\s*\\1\\s*\\.\\s*(\\w+)\\s*\\(\\s*\\)");

    // Pattern: x -> Type.method(x) or (x) -> Type.method(x)
    private static final Pattern STATIC_METHOD_LAMBDA = Pattern.compile(
        "\\(?\\s*(\\w+)\\s*\\)?\\s*->\\s*([A-Z]\\w*)\\s*\\.\\s*(\\w+)\\s*\\(\\s*\\1\\s*\\)");

    // Pattern: (a, b) -> new Type(a, b)
    private static final Pattern MULTI_ARG_CONSTRUCTOR = Pattern.compile(
        "\\(\\s*(\\w+)\\s*,\\s*(\\w+)\\s*\\)\\s*->\\s*new\\s+(\\w+)\\s*\\(\\s*\\1\\s*,\\s*\\2\\s*\\)");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Prefer method references over equivalent lambdas";
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

        return findAll(root, RuleId.Lambda.class)
               .stream()
               .map(lambda -> checkLambda(lambda, source, ctx))
               .filter(d -> d != null);
    }

    private Diagnostic checkLambda(CstNode lambda, String source, LintContext ctx) {
        var lambdaText = text(lambda, source).trim();

        // Check for constructor lambda: x -> new Type(x)
        var constructorMatch = CONSTRUCTOR_LAMBDA.matcher(lambdaText);
        if (constructorMatch.matches()) {
            var typeName = constructorMatch.group(2);
            return createDiagnostic(lambda, lambdaText, typeName + "::new", ctx);
        }

        // Check for multi-arg constructor: (a, b) -> new Type(a, b)
        var multiArgMatch = MULTI_ARG_CONSTRUCTOR.matcher(lambdaText);
        if (multiArgMatch.matches()) {
            var typeName = multiArgMatch.group(3);
            return createDiagnostic(lambda, lambdaText, typeName + "::new", ctx);
        }

        // Check for instance method: x -> x.method()
        var instanceMatch = INSTANCE_METHOD_LAMBDA.matcher(lambdaText);
        if (instanceMatch.matches()) {
            var methodName = instanceMatch.group(2);
            // For instance methods, we need the type, but we can suggest the pattern
            return createDiagnostic(lambda, lambdaText, "Type::" + methodName, ctx);
        }

        // Check for static method: x -> Type.method(x)
        var staticMatch = STATIC_METHOD_LAMBDA.matcher(lambdaText);
        if (staticMatch.matches()) {
            var typeName = staticMatch.group(2);
            var methodName = staticMatch.group(3);
            return createDiagnostic(lambda, lambdaText, typeName + "::" + methodName, ctx);
        }

        return null;
    }

    private Diagnostic createDiagnostic(CstNode lambda, String lambdaText, String suggestion, LintContext ctx) {
        return Diagnostic.diagnostic(
            RULE_ID,
            ctx.severityFor(RULE_ID),
            ctx.fileName(),
            startLine(lambda),
            startColumn(lambda),
            "Lambda can be simplified to method reference: " + suggestion,
            "Method references are more concise and readable.")
            .withExample("""
                // Before: lambda
                .map(%s)

                // After: method reference
                .map(%s)
                """.formatted(lambdaText, suggestion));
    }
}
