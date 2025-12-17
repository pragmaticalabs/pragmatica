package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-UC-01: Use case factories should return lambdas, not nested records.
 */
public class CstNestedRecordFactoryRule implements CstLintRule {

    private static final String RULE_ID = "JBCT-UC-01";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Use case factories should return lambdas, not nested record implementations";
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

        // Find static methods containing local record declarations
        return findAll(root, RuleId.MethodDecl.class).stream()
            .filter(method -> isStaticFactory(method, source))
            .filter(method -> containsLocalRecord(method, source))
            .map(method -> createDiagnostic(method, source, ctx));
    }

    private boolean isStaticFactory(CstNode method, String source) {
        var methodText = text(method, source);
        return methodText.contains("static ");
    }

    private boolean containsLocalRecord(CstNode method, String source) {
        // Look for local record pattern: record Name(...) inside method body
        var methodText = text(method, source);
        var bodyStart = methodText.indexOf("{");
        if (bodyStart < 0) return false;
        var body = methodText.substring(bodyStart);
        return body.contains("record ") && body.contains("implements ");
    }

    private Diagnostic createDiagnostic(CstNode method, String source, LintContext ctx) {
        var methodName = childByRule(method, RuleId.Identifier.class)
            .map(id -> text(id, source))
            .or("(unknown)");

        return Diagnostic.diagnostic(
            RULE_ID,
            ctx.severityFor(RULE_ID),
            ctx.fileName(),
            startLine(method),
            startColumn(method),
            "Factory method '" + methodName + "' uses nested record implementation",
            "Return lambdas directly instead of nested record implementations."
        ).withExample("""
            // Before (nested record)
            static UseCase useCase(Dep dep) {
                record Impl(Dep dep) implements UseCase { ... }
                return new Impl(dep);
            }

            // After (direct lambda)
            static UseCase useCase(Dep dep) {
                return request -> dep.process(request);
            }
            """);
    }
}
