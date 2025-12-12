package org.pragmatica.jbct.lint.rules;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;

import java.util.stream.Stream;

/**
 * JBCT-NAM-02: Use Valid prefix, not Validated.
 *
 * JBCT naming convention uses "Valid" prefix for validated input types:
 * - ValidRequest ✅
 * - ValidCredentials ✅
 * - ValidatedRequest ❌
 * - ValidatedCredentials ❌
 */
public class ValidatedNamingRule implements LintRule {

    private static final String RULE_ID = "JBCT-NAM-02";
    private static final String DOC_LINK = "https://github.com/siy/coding-technology/blob/main/skills/jbct/use-cases/structure.md";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Use 'Valid' prefix, not 'Validated'";
    }

    @Override
    public Stream<Diagnostic> analyze(CompilationUnit cu, LintContext ctx) {
        var packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }

        var classDiagnostics = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> !c.isInterface())
                .filter(c -> c.getNameAsString().startsWith("Validated"))
                .map(c -> createDiagnostic(c.getNameAsString(),
                        c.getBegin().map(p -> p.line).orElse(1),
                        c.getBegin().map(p -> p.column).orElse(1),
                        ctx));

        var recordDiagnostics = cu.findAll(RecordDeclaration.class).stream()
                .filter(r -> r.getNameAsString().startsWith("Validated"))
                .map(r -> createDiagnostic(r.getNameAsString(),
                        r.getBegin().map(p -> p.line).orElse(1),
                        r.getBegin().map(p -> p.column).orElse(1),
                        ctx));

        return Stream.concat(classDiagnostics, recordDiagnostics);
    }

    private Diagnostic createDiagnostic(String name, int line, int column, LintContext ctx) {
        var suggestedName = name.replaceFirst("Validated", "Valid");

        return Diagnostic.diagnostic(
                RULE_ID,
                ctx.severityFor(RULE_ID),
                ctx.fileName(),
                line,
                column,
                "Type '" + name + "' should be named '" + suggestedName + "'",
                "JBCT uses 'Valid' prefix, not 'Validated'. This is shorter and consistent."
        ).withExample("""
                // Before
                record %s(...) { ... }

                // After
                record %s(...) { ... }
                """.formatted(name, suggestedName))
                .withDocLink(DOC_LINK);
    }
}
