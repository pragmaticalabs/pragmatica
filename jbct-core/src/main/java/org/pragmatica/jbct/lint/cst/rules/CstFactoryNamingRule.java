package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.Set;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-NAM-01: Factory method naming convention.
 */
public class CstFactoryNamingRule implements CstLintRule {

    private static final String RULE_ID = "JBCT-NAM-01";
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
        "create", "of", "from", "parse"
    );

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Factory methods should be named after the type: TypeName.typeName()";
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

        // Check records for factory methods
        return findAll(root, RuleId.RecordDecl.class).stream()
            .flatMap(record -> checkFactoryMethods(record, source, ctx));
    }

    private Stream<Diagnostic> checkFactoryMethods(CstNode record, String source, LintContext ctx) {
        var typeName = childByRule(record, RuleId.Identifier.class)
            .map(id -> text(id, source))
            .or("");

        if (typeName.isEmpty()) return Stream.empty();

        var expectedName = camelCase(typeName);

        // Find static methods returning Result<TypeName> or TypeName
        return findAll(record, RuleId.MethodDecl.class).stream()
            .filter(method -> isFactoryMethod(method, typeName, source))
            .filter(method -> !isCorrectlyNamed(method, expectedName, source))
            .map(method -> createDiagnostic(method, typeName, expectedName, source, ctx));
    }

    private boolean isFactoryMethod(CstNode method, String typeName, String source) {
        var methodText = text(method, source);
        return methodText.contains("static") &&
               (methodText.contains("Result<" + typeName + ">") ||
                methodText.contains(" " + typeName + " "));
    }

    private boolean isCorrectlyNamed(CstNode method, String expectedName, String source) {
        var methodName = childByRule(method, RuleId.Identifier.class)
            .map(id -> text(id, source))
            .or("");

        if (methodName.equals(expectedName)) return true;

        for (var prefix : ALLOWED_PREFIXES) {
            if (methodName.equals(prefix) || methodName.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    private Diagnostic createDiagnostic(CstNode method, String typeName, String expectedName,
                                        String source, LintContext ctx) {
        var actualName = childByRule(method, RuleId.Identifier.class)
            .map(id -> text(id, source))
            .or("(unknown)");

        return Diagnostic.diagnostic(
            RULE_ID,
            ctx.severityFor(RULE_ID),
            ctx.fileName(),
            startLine(method),
            startColumn(method),
            "Factory method '" + actualName + "' should be named '" + expectedName + "'",
            "JBCT naming convention: " + typeName + "." + expectedName + "(...)"
        );
    }

    private String camelCase(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
