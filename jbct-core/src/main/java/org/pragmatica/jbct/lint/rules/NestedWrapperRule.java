package org.pragmatica.jbct.lint.rules;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintContext;

import java.util.Set;
import java.util.stream.Stream;

/**
 * JBCT-RET-02: No nested wrappers.
 *
 * JBCT forbids redundant nesting of monadic types:
 * - Promise<Result<T>> is forbidden (Promise already carries failures)
 * - Result<Option<T>> is allowed only for optional fields during validation
 * - Option<Option<T>> is forbidden
 * - Promise<Option<T>> is allowed (optional async result)
 *
 * This rule detects forbidden nesting patterns.
 */
public class NestedWrapperRule implements LintRule {

    private static final String RULE_ID = "JBCT-RET-02";
    private static final String DOC_LINK = "https://github.com/siy/coding-technology/blob/main/series/part-2-four-return-types.md";

    private static final Set<String> WRAPPER_TYPES = Set.of(
            "Option", "Result", "Promise"
    );

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "No nested wrappers like Promise<Result<T>> or Option<Option<T>>";
    }

    @Override
    public Stream<Diagnostic> analyze(CompilationUnit cu, LintContext ctx) {
        var packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        // Only check business packages
        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }

        var fileName = cu.getStorage()
                .map(s -> s.getFileName())
                .orElse("Unknown.java");

        return cu.findAll(MethodDeclaration.class).stream()
                .flatMap(method -> checkMethod(method, fileName, ctx));
    }

    private Stream<Diagnostic> checkMethod(MethodDeclaration method, String fileName, LintContext ctx) {
        var returnType = method.getType();
        var line = method.getBegin().map(p -> p.line).orElse(1);
        var column = method.getBegin().map(p -> p.column).orElse(1);

        var nestedPattern = detectNestedWrapper(returnType);
        if (nestedPattern != null) {
            return Stream.of(createDiagnostic(fileName, line, column, method.getNameAsString(), nestedPattern, ctx));
        }

        return Stream.empty();
    }

    /**
     * Detect forbidden nested wrapper patterns.
     * Returns a description of the pattern if found, null otherwise.
     */
    private String detectNestedWrapper(Type type) {
        if (!(type instanceof ClassOrInterfaceType classType)) {
            return null;
        }

        var outerName = classType.getNameAsString();
        if (!WRAPPER_TYPES.contains(outerName)) {
            return null;
        }

        // Check type arguments
        var typeArgs = classType.getTypeArguments();
        if (typeArgs.isEmpty()) {
            return null;
        }

        for (var typeArg : typeArgs.get()) {
            if (typeArg instanceof ClassOrInterfaceType innerType) {
                var innerName = innerType.getNameAsString();

                // Forbidden patterns
                if ("Promise".equals(outerName) && "Result".equals(innerName)) {
                    return "Promise<Result<T>>";
                }
                if ("Option".equals(outerName) && "Option".equals(innerName)) {
                    return "Option<Option<T>>";
                }
                if ("Result".equals(outerName) && "Result".equals(innerName)) {
                    return "Result<Result<T>>";
                }
                if ("Promise".equals(outerName) && "Promise".equals(innerName)) {
                    return "Promise<Promise<T>>";
                }
            }
        }

        return null;
    }

    private Diagnostic createDiagnostic(String file, int line, int column, String methodName, String pattern, LintContext ctx) {
        var suggestion = getSuggestion(pattern);

        return Diagnostic.diagnostic(
                RULE_ID,
                ctx.severityFor(RULE_ID),
                file,
                line,
                column,
                "Method '" + methodName + "' uses forbidden nested wrapper " + pattern,
                "JBCT prohibits redundant nesting. " + suggestion
        ).withExample(getExample(pattern))
                .withDocLink(DOC_LINK);
    }

    private String getSuggestion(String pattern) {
        return switch (pattern) {
            case "Promise<Result<T>>" -> "Promise already carries failures via Cause. Use Promise<T> directly.";
            case "Option<Option<T>>" -> "Double-wrapping Options is confusing. Flatten to Option<T>.";
            case "Result<Result<T>>" -> "Double-wrapping Results is confusing. Flatten to Result<T> or use flatMap.";
            case "Promise<Promise<T>>" -> "Double-wrapping Promises is confusing. Use flatMap to chain.";
            default -> "Avoid redundant nesting of monadic types.";
        };
    }

    private String getExample(String pattern) {
        return switch (pattern) {
            case "Promise<Result<T>>" -> """
                    // Before (forbidden)
                    public Promise<Result<User>> loadUser(UserId id) { ... }

                    // After
                    public Promise<User> loadUser(UserId id) { ... }
                    """;
            case "Option<Option<T>>" -> """
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
