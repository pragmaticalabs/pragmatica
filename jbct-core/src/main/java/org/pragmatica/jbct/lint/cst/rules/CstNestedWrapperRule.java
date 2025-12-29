package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-RET-02: No nested wrappers.
 *
 * Forbids Promise<Result<T>>, Option<Option<T>>, etc.
 */
public class CstNestedWrapperRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-RET-02";
    private static final String DOC_LINK = "https://github.com/siy/coding-technology/blob/main/series/part-2-four-return-types.md";

    private static final Set<String>WRAPPER_TYPES = Set.of("Option", "Result", "Promise");

    // Pattern to detect nested wrappers like Promise<Result<...>>
    private static final Pattern NESTED_PATTERN = Pattern.compile(
    "(Promise|Result|Option)<\\s*(Promise|Result|Option)<");

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
        return findAll(root, RuleId.MethodDecl.class)
               .stream()
               .flatMap(method -> checkMethod(method, source, ctx));
    }

    private Stream<Diagnostic> checkMethod(CstNode method, String source, LintContext ctx) {
        var returnType = childByRule(method, RuleId.Type.class);
        if (returnType.isEmpty()) {
            return Stream.empty();
        }
        var typeText = text(returnType.getOrThrow("Return type expected"),
                            source)
                       .trim();
        var nestedPattern = detectNestedWrapper(typeText);
        if (nestedPattern != null) {
            var methodName = childByRule(method, RuleId.Identifier.class)
                             .map(id -> text(id, source))
                             .or("(unknown)");
            return Stream.of(createDiagnostic(method, methodName, nestedPattern, ctx));
        }
        return Stream.empty();
    }

    private String detectNestedWrapper(String typeText) {
        var matcher = NESTED_PATTERN.matcher(typeText);
        if (!matcher.find()) {
            return null;
        }
        var outer = matcher.group(1);
        var inner = matcher.group(2);
        // Forbidden patterns
        if ("Promise".equals(outer) && "Result".equals(inner)) {
            return "Promise<Result<T>>";
        }
        if ("Option".equals(outer) && "Option".equals(inner)) {
            return "Option<Option<T>>";
        }
        if ("Result".equals(outer) && "Result".equals(inner)) {
            return "Result<Result<T>>";
        }
        if ("Promise".equals(outer) && "Promise".equals(inner)) {
            return "Promise<Promise<T>>";
        }
        // Result<Option<T>> is allowed for optional validation
        // Promise<Option<T>> is allowed for optional async results
        return null;
    }

    private Diagnostic createDiagnostic(CstNode method, String methodName, String pattern, LintContext ctx) {
        var suggestion = getSuggestion(pattern);
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Method '" + methodName + "' uses forbidden nested wrapper " + pattern,
                                     "JBCT prohibits redundant nesting. " + suggestion)
                         .withExample(getExample(pattern))
                         .withDocLink(DOC_LINK);
    }

    private String getSuggestion(String pattern) {
        return switch (pattern) {
            case"Promise<Result<T>>" -> "Promise already carries failures via Cause. Use Promise<T> directly.";
            case"Option<Option<T>>" -> "Double-wrapping Options is confusing. Flatten to Option<T>.";
            case"Result<Result<T>>" -> "Double-wrapping Results is confusing. Flatten to Result<T> or use flatMap.";
            case"Promise<Promise<T>>" -> "Double-wrapping Promises is confusing. Use flatMap to chain.";
            default -> "Avoid redundant nesting of monadic types.";
        };
    }

    private String getExample(String pattern) {
        return switch (pattern) {
            case"Promise<Result<T>>" -> """
                // Before (forbidden)
                public Promise<Result<User>> loadUser(UserId id) { ... }

                // After
                public Promise<User> loadUser(UserId id) { ... }
                """;
            case"Option<Option<T>>" -> """
                // Before (forbidden)
                public Option<Option<String>> findValue() { ... }

                // After
                public Option<String> findValue() { ... }
                """;
            default -> """
                // Avoid nested wrappers - flatten the type hierarchy
                """;
        };
    }
}
