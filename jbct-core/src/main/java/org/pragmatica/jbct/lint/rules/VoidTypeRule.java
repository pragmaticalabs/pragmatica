package org.pragmatica.jbct.lint.rules;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.VoidType;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;

import java.util.stream.Stream;

/**
 * JBCT-RET-04: Use Unit instead of Void.
 *
 * JBCT never uses Void type. For operations without meaningful return value,
 * use Unit (Result<Unit>, Promise<Unit>).
 */
public class VoidTypeRule implements LintRule {

    private static final String RULE_ID = "JBCT-RET-04";
    private static final String DOC_LINK = "https://github.com/siy/coding-technology/blob/main/skills/jbct/fundamentals/four-return-kinds.md";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Use Unit instead of Void for no-value results";
    }

    @Override
    public Stream<Diagnostic> analyze(CompilationUnit cu, LintContext ctx) {
        var packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }

        // Check for Result<Void> or Promise<Void> return types
        var wrapperDiagnostics = cu.findAll(MethodDeclaration.class).stream()
                .filter(this::returnsVoidWrapper)
                .map(method -> createWrapperDiagnostic(method, ctx));

        // Check for void return type on public methods (except overrides)
        var voidDiagnostics = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getType() instanceof VoidType)
                .filter(m -> !m.isPrivate())
                .filter(m -> !isOverride(m))
                .map(method -> createVoidDiagnostic(method, ctx));

        return Stream.concat(wrapperDiagnostics, voidDiagnostics);
    }

    private boolean returnsVoidWrapper(MethodDeclaration method) {
        var returnType = method.getType();
        if (!(returnType instanceof ClassOrInterfaceType classType)) {
            return false;
        }

        var wrapper = classType.getNameAsString();
        if (!("Result".equals(wrapper) || "Promise".equals(wrapper))) {
            return false;
        }

        var typeArgs = classType.getTypeArguments();
        if (typeArgs.isEmpty()) {
            return false;
        }

        return typeArgs.get().stream()
                .anyMatch(arg -> arg.asString().equals("Void"));
    }

    private boolean isOverride(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals("Override"));
    }

    private Diagnostic createWrapperDiagnostic(MethodDeclaration method, LintContext ctx) {
        var line = method.getBegin().map(p -> p.line).orElse(1);
        var column = method.getBegin().map(p -> p.column).orElse(1);
        var name = method.getNameAsString();
        var returnType = method.getType().asString();

        var fixedType = returnType.replace("Void", "Unit");

        return Diagnostic.diagnostic(
                RULE_ID,
                ctx.severityFor(RULE_ID),
                ctx.fileName(),
                line,
                column,
                "Method '" + name + "' returns " + returnType + "; use " + fixedType + " instead",
                "JBCT uses Unit instead of Void for no-value results. " +
                        "Use Result.unitResult() for successful Result<Unit>."
        ).withExample("""
                // Before
                public %s %s(...) {
                    // ...
                    return Result.success(null);  // Wrong
                }

                // After
                public %s %s(...) {
                    // ...
                    return Result.unitResult();  // Correct
                }
                """.formatted(returnType, name, fixedType, name))
                .withDocLink(DOC_LINK);
    }

    private Diagnostic createVoidDiagnostic(MethodDeclaration method, LintContext ctx) {
        var line = method.getBegin().map(p -> p.line).orElse(1);
        var column = method.getBegin().map(p -> p.column).orElse(1);
        var name = method.getNameAsString();

        return Diagnostic.diagnostic(
                RULE_ID,
                ctx.severityFor(RULE_ID),
                ctx.fileName(),
                line,
                column,
                "Method '" + name + "' returns void; use Result<Unit> or Promise<Unit>",
                "JBCT uses Result<Unit> or Promise<Unit> for operations without meaningful return value. " +
                        "This allows proper error handling and composition."
        ).withExample("""
                // Before
                public void %s(...) {
                    doSomething();
                }

                // After (if can fail)
                public Result<Unit> %s(...) {
                    doSomething();
                    return Result.unitResult();
                }

                // After (if async)
                public Promise<Unit> %s(...) {
                    return Promise.lift(() -> {
                        doSomething();
                        return Unit.unit();
                    });
                }
                """.formatted(name, name, name))
                .withDocLink(DOC_LINK);
    }
}
