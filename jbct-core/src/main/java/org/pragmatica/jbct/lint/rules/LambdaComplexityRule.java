package org.pragmatica.jbct.lint.rules;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;

import java.util.Set;
import java.util.stream.Stream;

/**
 * JBCT-LAM-01: No complex logic in lambdas passed to monadic operations.
 *
 * Lambdas passed to map, flatMap, filter, recover, etc. should be minimal:
 * - Method references: Email::new, this::process
 * - Parameter forwarding: user -> validate(role, user)
 *
 * Forbidden:
 * - Conditionals (if, ternary, switch)
 * - Try-catch blocks
 * - Multi-statement blocks (except simple transformation)
 */
public class LambdaComplexityRule implements LintRule {

    private static final String RULE_ID = "JBCT-LAM-01";
    private static final String DOC_LINK = "https://github.com/siy/coding-technology/blob/main/skills/jbct/SKILL.md";

    private static final Set<String> MONADIC_METHODS = Set.of(
            "map", "flatMap", "filter", "recover", "onSuccess", "onFailure", "fold"
    );

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "No complex logic in lambdas - extract to named methods";
    }

    @Override
    public Stream<Diagnostic> analyze(CompilationUnit cu, LintContext ctx) {
        var packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }

        return cu.findAll(LambdaExpr.class).stream()
                .filter(this::isInMonadicOperation)
                .filter(this::hasComplexLogic)
                .map(lambda -> createDiagnostic(lambda, ctx));
    }

    private boolean isInMonadicOperation(LambdaExpr lambda) {
        return lambda.findAncestor(MethodCallExpr.class)
                .filter(call -> MONADIC_METHODS.contains(call.getNameAsString()))
                .isPresent();
    }

    private boolean hasComplexLogic(LambdaExpr lambda) {
        var body = lambda.getBody();

        // Check for conditionals
        if (!body.findAll(IfStmt.class).isEmpty()) {
            return true;
        }
        if (!body.findAll(ConditionalExpr.class).isEmpty()) {
            return true;
        }
        if (!body.findAll(SwitchStmt.class).isEmpty()) {
            return true;
        }

        // Check for try-catch
        if (!body.findAll(TryStmt.class).isEmpty()) {
            return true;
        }

        return false;
    }

    private Diagnostic createDiagnostic(LambdaExpr lambda, LintContext ctx) {
        var line = lambda.getBegin().map(p -> p.line).orElse(1);
        var column = lambda.getBegin().map(p -> p.column).orElse(1);

        var problemType = detectProblemType(lambda);

        return Diagnostic.diagnostic(
                RULE_ID,
                ctx.severityFor(RULE_ID),
                ctx.fileName(),
                line,
                column,
                "Lambda contains " + problemType + "; extract to named method",
                "Lambdas in monadic operations should be minimal. " +
                        "Extract complex logic to named methods for clarity and testability."
        ).withExample("""
                // Before: complex lambda
                .map(value -> {
                    if (value.isValid()) {
                        return process(value);
                    }
                    return fallback();
                })

                // After: named method
                .map(this::processOrFallback)

                private Result<T> processOrFallback(Value value) {
                    return value.isValid()
                        ? process(value)
                        : fallback();
                }
                """)
                .withDocLink(DOC_LINK);
    }

    private String detectProblemType(LambdaExpr lambda) {
        var body = lambda.getBody();

        if (!body.findAll(IfStmt.class).isEmpty()) {
            return "if statement";
        }
        if (!body.findAll(ConditionalExpr.class).isEmpty()) {
            return "ternary expression";
        }
        if (!body.findAll(SwitchStmt.class).isEmpty()) {
            return "switch statement";
        }
        if (!body.findAll(TryStmt.class).isEmpty()) {
            return "try-catch block";
        }

        return "complex logic";
    }
}
