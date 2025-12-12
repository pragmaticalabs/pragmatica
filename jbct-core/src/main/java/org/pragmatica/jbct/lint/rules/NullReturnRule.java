package org.pragmatica.jbct.lint.rules;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;

import java.util.stream.Stream;

/**
 * JBCT-RET-03: Never return null.
 *
 * JBCT code never returns null. Use Option<T> for optional values.
 * Null is only acceptable at adapter boundaries (wrapping external APIs).
 */
public class NullReturnRule implements LintRule {

    private static final String RULE_ID = "JBCT-RET-03";
    private static final String DOC_LINK = "https://github.com/siy/coding-technology/blob/main/skills/jbct/fundamentals/four-return-kinds.md";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Never return null - use Option<T> for optional values";
    }

    @Override
    public Stream<Diagnostic> analyze(CompilationUnit cu, LintContext ctx) {
        var packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }

        return cu.findAll(ReturnStmt.class).stream()
                .filter(this::returnsNull)
                .map(stmt -> createDiagnostic(stmt, ctx));
    }

    private boolean returnsNull(ReturnStmt stmt) {
        return stmt.getExpression()
                .filter(expr -> expr instanceof NullLiteralExpr)
                .isPresent();
    }

    private Diagnostic createDiagnostic(ReturnStmt stmt, LintContext ctx) {
        var line = stmt.getBegin().map(p -> p.line).orElse(1);
        var column = stmt.getBegin().map(p -> p.column).orElse(1);

        // Try to find the enclosing method name
        var methodName = stmt.findAncestor(MethodDeclaration.class)
                .map(MethodDeclaration::getNameAsString)
                .orElse("(unknown)");

        return Diagnostic.diagnostic(
                RULE_ID,
                ctx.severityFor(RULE_ID),
                ctx.fileName(),
                line,
                column,
                "Method '" + methodName + "' returns null; use Option<T> instead",
                "JBCT code never returns null. Use Option.none() for absent values, " +
                        "Option.some(value) for present values."
        ).withExample("""
                // Before: returning null
                public User findUser(UserId id) {
                    User user = repository.findById(id);
                    if (user == null) return null;  // Don't return null
                    return user;
                }

                // After: using Option
                public Option<User> findUser(UserId id) {
                    return Option.option(repository.findById(id));
                }
                """)
                .withDocLink(DOC_LINK);
    }
}
