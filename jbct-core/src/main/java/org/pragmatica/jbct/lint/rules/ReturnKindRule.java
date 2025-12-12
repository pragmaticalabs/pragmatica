package org.pragmatica.jbct.lint.rules;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VoidType;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintContext;

import java.util.Set;
import java.util.stream.Stream;

/**
 * JBCT-RET-01: Business methods must use only four return kinds.
 *
 * In JBCT, every function returns exactly one of:
 * - T (pure computation, cannot fail, always present)
 * - Option<T> (may be absent, cannot fail)
 * - Result<T> (can fail with typed Cause)
 * - Promise<T> (asynchronous, can fail)
 *
 * This rule detects:
 * - void return type (should be Result<Unit> or Promise<Unit>)
 * - CompletableFuture, Future (should be Promise)
 * - Optional (should be Option)
 * - Other wrapper types not in the allowed set
 */
public class ReturnKindRule implements LintRule {

    private static final String RULE_ID = "JBCT-RET-01";
    private static final String DOC_LINK = "https://github.com/siy/coding-technology/blob/main/series/part-2-four-return-types.md";

    private static final Set<String> ALLOWED_WRAPPERS = Set.of(
            "Option", "org.pragmatica.lang.Option",
            "Result", "org.pragmatica.lang.Result",
            "Promise", "org.pragmatica.lang.Promise"
    );

    private static final Set<String> FORBIDDEN_TYPES = Set.of(
            "Optional", "java.util.Optional",
            "CompletableFuture", "java.util.concurrent.CompletableFuture",
            "Future", "java.util.concurrent.Future",
            "CompletionStage", "java.util.concurrent.CompletionStage"
    );

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Business methods must use only four return kinds: T, Option<T>, Result<T>, Promise<T>";
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
                .filter(method -> !method.isPrivate()) // Only check public/protected/package methods
                .flatMap(method -> checkMethod(method, fileName, ctx));
    }

    private Stream<Diagnostic> checkMethod(MethodDeclaration method, String fileName, LintContext ctx) {
        var returnType = method.getType();
        var line = method.getBegin().map(p -> p.line).orElse(1);
        var column = method.getBegin().map(p -> p.column).orElse(1);

        // Check for void return type
        if (returnType instanceof VoidType) {
            return Stream.of(createVoidDiagnostic(fileName, line, column, method.getNameAsString(), ctx));
        }

        // Check for forbidden types
        var typeName = getTypeName(returnType);
        if (isForbiddenType(typeName)) {
            return Stream.of(createForbiddenTypeDiagnostic(fileName, line, column, method.getNameAsString(), typeName, ctx));
        }

        return Stream.empty();
    }

    private String getTypeName(Type type) {
        return type.asString();
    }

    private boolean isForbiddenType(String typeName) {
        // Check if the type starts with any forbidden type
        for (var forbidden : FORBIDDEN_TYPES) {
            if (typeName.startsWith(forbidden + "<") || typeName.equals(forbidden)) {
                return true;
            }
        }
        return false;
    }

    private Diagnostic createVoidDiagnostic(String file, int line, int column, String methodName, LintContext ctx) {
        return Diagnostic.diagnostic(
                RULE_ID,
                ctx.severityFor(RULE_ID),
                file,
                line,
                column,
                "Method '" + methodName + "' returns void; JBCT requires Result<Unit> or Promise<Unit>",
                "In JBCT, void methods should return Result<Unit> (sync) or Promise<Unit> (async) " +
                        "to maintain consistent error handling through the type system."
        ).withExample("""
                // Before (void)
                public void saveUser(User user) { ... }

                // After (Result<Unit>)
                public Result<Unit> saveUser(User user) { ... }
                """)
                .withDocLink(DOC_LINK);
    }

    private Diagnostic createForbiddenTypeDiagnostic(String file, int line, int column, String methodName, String typeName, LintContext ctx) {
        var replacement = suggestReplacement(typeName);

        return Diagnostic.diagnostic(
                RULE_ID,
                ctx.severityFor(RULE_ID),
                file,
                line,
                column,
                "Method '" + methodName + "' returns " + typeName + "; use " + replacement + " instead",
                "JBCT uses its own monadic types for consistency. " + typeName + " should be replaced with " + replacement + "."
        ).withExample("""
                // Before
                public %s process() { ... }

                // After
                public %s process() { ... }
                """.formatted(typeName, replacement))
                .withDocLink(DOC_LINK);
    }

    private String suggestReplacement(String typeName) {
        if (typeName.startsWith("Optional")) {
            return typeName.replace("Optional", "Option");
        }
        if (typeName.startsWith("CompletableFuture") || typeName.startsWith("Future") || typeName.startsWith("CompletionStage")) {
            return "Promise<...>";
        }
        return "Result<...> or Promise<...>";
    }
}
