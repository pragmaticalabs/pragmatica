package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-EX-01: No business exceptions.
 */
public class CstNoBusinessExceptionsRule implements CstLintRule {

    private static final String RULE_ID = "JBCT-EX-01";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "No business exceptions - use Result<T> for errors";
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

        // Find classes extending Exception
        var exceptionClasses = findAll(root, RuleId.ClassDecl.class).stream()
            .filter(cls -> extendsException(cls, source))
            .map(cls -> createExceptionClassDiagnostic(cls, source, ctx));

        // Find throw statements
        var throwStatements = findAll(root, RuleId.Stmt.class).stream()
            .filter(stmt -> text(stmt, source).trim().startsWith("throw "))
            .map(stmt -> createThrowDiagnostic(stmt, ctx));

        // Find methods with throws clause
        var throwsClauses = findAll(root, RuleId.MethodDecl.class).stream()
            .filter(method -> hasThrowsClause(method, source))
            .map(method -> createThrowsClauseDiagnostic(method, source, ctx));

        return Stream.concat(Stream.concat(exceptionClasses, throwStatements), throwsClauses);
    }

    private boolean extendsException(CstNode cls, String source) {
        var clsText = text(cls, source);
        return clsText.contains("extends Exception") ||
               clsText.contains("extends RuntimeException") ||
               clsText.contains("extends Throwable");
    }

    private boolean hasThrowsClause(CstNode method, String source) {
        return contains(method, RuleId.Throws.class);
    }

    private Diagnostic createExceptionClassDiagnostic(CstNode cls, String source, LintContext ctx) {
        var className = childByRule(cls, RuleId.Identifier.class)
            .map(id -> text(id, source))
            .or("(unknown)");

        return Diagnostic.diagnostic(
            RULE_ID,
            ctx.severityFor(RULE_ID),
            ctx.fileName(),
            startLine(cls),
            startColumn(cls),
            "Exception class '" + className + "' - use Cause instead",
            "JBCT doesn't use exceptions for business errors. Define a Cause type."
        );
    }

    private Diagnostic createThrowDiagnostic(CstNode stmt, LintContext ctx) {
        return Diagnostic.diagnostic(
            RULE_ID,
            ctx.severityFor(RULE_ID),
            ctx.fileName(),
            startLine(stmt),
            startColumn(stmt),
            "throw statement forbidden - return Result.failure() instead",
            "JBCT uses Result<T> for error handling, not exceptions."
        );
    }

    private Diagnostic createThrowsClauseDiagnostic(CstNode method, String source, LintContext ctx) {
        var methodName = childByRule(method, RuleId.Identifier.class)
            .map(id -> text(id, source))
            .or("(unknown)");

        return Diagnostic.diagnostic(
            RULE_ID,
            ctx.severityFor(RULE_ID),
            ctx.fileName(),
            startLine(method),
            startColumn(method),
            "Method '" + methodName + "' has throws clause - use Result<T> instead",
            "JBCT methods shouldn't declare checked exceptions."
        );
    }
}
