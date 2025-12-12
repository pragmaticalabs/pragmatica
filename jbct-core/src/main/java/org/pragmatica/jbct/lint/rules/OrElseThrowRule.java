package org.pragmatica.jbct.lint.rules;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;

import java.util.stream.Stream;

/**
 * JBCT-EX-02: Don't use orElseThrow() for business logic.
 *
 * orElseThrow() converts Optional to exception-based flow.
 * JBCT uses Result/Option types instead.
 */
public class OrElseThrowRule implements LintRule {

    private static final String RULE_ID = "JBCT-EX-02";
    private static final String DOC_LINK = "https://github.com/siy/coding-technology/blob/main/skills/jbct/fundamentals/no-business-exceptions.md";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Don't use orElseThrow() - use Result/Option types instead";
    }

    @Override
    public Stream<Diagnostic> analyze(CompilationUnit cu, LintContext ctx) {
        var packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }

        return cu.findAll(MethodCallExpr.class).stream()
                .filter(call -> "orElseThrow".equals(call.getNameAsString()))
                .map(call -> createDiagnostic(call, ctx));
    }

    private Diagnostic createDiagnostic(MethodCallExpr call, LintContext ctx) {
        var line = call.getBegin().map(p -> p.line).orElse(1);
        var column = call.getBegin().map(p -> p.column).orElse(1);

        return Diagnostic.diagnostic(
                RULE_ID,
                ctx.severityFor(RULE_ID),
                ctx.fileName(),
                line,
                column,
                "orElseThrow() converts to exception-based flow",
                "JBCT uses Result/Option types instead of exceptions. " +
                        "Use Option.toResult() or Option.async() to convert to Result/Promise."
        ).withExample("""
                // Before (exception-based)
                public User findUser(UserId id) {
                    return userRepository.findById(id)
                        .orElseThrow(() -> new UserNotFoundException(id));
                }

                // After (using Option)
                public Option<User> findUser(UserId id) {
                    return Option.option(userRepository.findById(id));
                }

                // Or if absence is an error
                public Result<User> getUser(UserId id) {
                    return Option.option(userRepository.findById(id))
                        .toResult(UserError.NotFound.INSTANCE);
                }
                """)
                .withDocLink(DOC_LINK);
    }
}
