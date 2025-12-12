package org.pragmatica.jbct.lint.rules;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.stmt.LocalRecordDeclarationStmt;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;

import java.util.stream.Stream;

/**
 * JBCT-UC-01: Use case factories should return lambdas, not nested records.
 *
 * Factory methods should return lambdas directly, not create nested record
 * implementations. Records are for data, lambdas are for behavior.
 */
public class NestedRecordFactoryRule implements LintRule {

    private static final String RULE_ID = "JBCT-UC-01";
    private static final String DOC_LINK = "https://github.com/siy/coding-technology/blob/main/skills/jbct/use-cases/structure.md";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Use case factories should return lambdas, not nested record implementations";
    }

    @Override
    public Stream<Diagnostic> analyze(CompilationUnit cu, LintContext ctx) {
        var packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }

        // Find static factory methods with local record declarations
        return cu.findAll(MethodDeclaration.class).stream()
                .filter(MethodDeclaration::isStatic)
                .filter(this::containsLocalRecordDeclaration)
                .map(method -> createDiagnostic(method, ctx));
    }

    private boolean containsLocalRecordDeclaration(MethodDeclaration method) {
        return !method.findAll(LocalRecordDeclarationStmt.class).isEmpty();
    }

    private Diagnostic createDiagnostic(MethodDeclaration method, LintContext ctx) {
        var line = method.getBegin().map(p -> p.line).orElse(1);
        var column = method.getBegin().map(p -> p.column).orElse(1);
        var name = method.getNameAsString();

        // Find the local record name
        var recordName = method.findFirst(LocalRecordDeclarationStmt.class)
                .map(stmt -> stmt.getRecordDeclaration().getNameAsString())
                .orElse("impl");

        return Diagnostic.diagnostic(
                RULE_ID,
                ctx.severityFor(RULE_ID),
                ctx.fileName(),
                line,
                column,
                "Factory method '" + name + "' uses nested record implementation",
                "JBCT factories should return lambdas directly, not nested record implementations. " +
                        "Records are for data, lambdas are for behavior."
        ).withExample("""
                // Before (nested record)
                static UseCase useCase(Dep1 dep1, Dep2 dep2) {
                    record %s(Dep1 dep1, Dep2 dep2) implements UseCase {
                        @Override
                        public Promise<Response> execute(Request request) {
                            return ValidRequest.validRequest(request)
                                .async()
                                .flatMap(dep1::apply)
                                .flatMap(dep2::apply);
                        }
                    }
                    return new %s(dep1, dep2);
                }

                // After (direct lambda)
                static UseCase useCase(Dep1 dep1, Dep2 dep2) {
                    return request -> ValidRequest.validRequest(request)
                        .async()
                        .flatMap(dep1::apply)
                        .flatMap(dep2::apply);
                }
                """.formatted(recordName, recordName))
                .withDocLink(DOC_LINK);
    }
}
