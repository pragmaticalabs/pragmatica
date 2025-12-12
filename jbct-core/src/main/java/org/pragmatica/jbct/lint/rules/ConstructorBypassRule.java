package org.pragmatica.jbct.lint.rules;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * JBCT-VO-02: Direct constructor calls bypass factory validation.
 *
 * Value objects should only be created through factory methods.
 * Direct constructor calls (new Email(...)) bypass validation.
 *
 * Exception: Constructor references are allowed inside factory methods
 * or in .map() chains after validation.
 */
public class ConstructorBypassRule implements LintRule {

    private static final String RULE_ID = "JBCT-VO-02";
    private static final String DOC_LINK = "https://github.com/siy/coding-technology/blob/main/skills/jbct/fundamentals/parse-dont-validate.md";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Use factory methods instead of direct constructor calls";
    }

    @Override
    public Stream<Diagnostic> analyze(CompilationUnit cu, LintContext ctx) {
        var packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }

        // Collect types that have Result-returning factories (value objects)
        var valueObjectTypes = collectValueObjectTypes(cu);

        if (valueObjectTypes.isEmpty()) {
            return Stream.empty();
        }

        // Find direct constructor calls to value objects outside of allowed contexts
        return cu.findAll(ObjectCreationExpr.class).stream()
                .filter(expr -> isValueObjectConstruction(expr, valueObjectTypes))
                .filter(expr -> !isInAllowedContext(expr))
                .map(expr -> createDiagnostic(expr, ctx));
    }

    private Set<String> collectValueObjectTypes(CompilationUnit cu) {
        var types = new HashSet<String>();

        // Check records with Result-returning static methods
        cu.findAll(RecordDeclaration.class).forEach(record -> {
            if (hasResultFactory(record.getMethods(), record.getNameAsString())) {
                types.add(record.getNameAsString());
            }
        });

        // Check classes with Result-returning static methods
        cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> !c.isInterface())
                .forEach(clazz -> {
                    if (hasResultFactory(clazz.getMethods(), clazz.getNameAsString())) {
                        types.add(clazz.getNameAsString());
                    }
                });

        return types;
    }

    private boolean hasResultFactory(java.util.List<MethodDeclaration> methods, String typeName) {
        return methods.stream()
                .filter(MethodDeclaration::isStatic)
                .filter(m -> !m.isPrivate())
                .anyMatch(m -> returnsResultOf(m, typeName));
    }

    private boolean returnsResultOf(MethodDeclaration method, String typeName) {
        var returnType = method.getType();
        if (!(returnType instanceof ClassOrInterfaceType classType)) {
            return false;
        }

        if (!classType.getNameAsString().equals("Result")) {
            return false;
        }

        var typeArgs = classType.getTypeArguments();
        return typeArgs.isPresent() && typeArgs.get().stream()
                .anyMatch(arg -> arg.asString().contains(typeName));
    }

    private boolean isValueObjectConstruction(ObjectCreationExpr expr, Set<String> valueObjectTypes) {
        var typeName = expr.getType().getNameAsString();
        return valueObjectTypes.contains(typeName);
    }

    private boolean isInAllowedContext(ObjectCreationExpr expr) {
        // Allowed: inside .map() call (after validation)
        var inMap = expr.findAncestor(MethodCallExpr.class)
                .filter(call -> "map".equals(call.getNameAsString()))
                .isPresent();
        if (inMap) {
            return true;
        }

        // Allowed: inside factory method of the same type
        var enclosingMethod = expr.findAncestor(MethodDeclaration.class);
        if (enclosingMethod.isPresent()) {
            var method = enclosingMethod.get();
            if (method.isStatic()) {
                var returnType = method.getType();
                if (returnType instanceof ClassOrInterfaceType classType) {
                    if ("Result".equals(classType.getNameAsString())) {
                        // This is a factory method, allow construction
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private Diagnostic createDiagnostic(ObjectCreationExpr expr, LintContext ctx) {
        var line = expr.getBegin().map(p -> p.line).orElse(1);
        var column = expr.getBegin().map(p -> p.column).orElse(1);
        var typeName = expr.getType().getNameAsString();
        var factoryName = Character.toLowerCase(typeName.charAt(0)) + typeName.substring(1);

        return Diagnostic.diagnostic(
                RULE_ID,
                ctx.severityFor(RULE_ID),
                ctx.fileName(),
                line,
                column,
                "Direct 'new " + typeName + "(...)' bypasses factory validation",
                "Value objects should be created through factory methods that validate input. " +
                        "Direct constructor calls skip validation and may create invalid objects."
        ).withExample("""
                // Before (bypasses validation)
                var value = new %s(rawInput);

                // After (uses factory with validation)
                var result = %s.%s(rawInput);
                result.onSuccess(value -> {
                    // value is guaranteed valid
                });
                """.formatted(typeName, typeName, factoryName))
                .withDocLink(DOC_LINK);
    }
}
