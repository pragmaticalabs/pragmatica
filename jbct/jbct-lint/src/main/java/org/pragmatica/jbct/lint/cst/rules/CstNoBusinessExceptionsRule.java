package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-EX-01: No business exceptions.
public class CstNoBusinessExceptionsRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-EX-01";
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("\\bclass\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("\\b([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\(");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }
        // Find classes extending Exception
        var exceptionClasses = findAllClasses(root).stream()
                                             .filter(this::extendsException)
                                             .map(cls -> createExceptionClassDiagnostic(cls, ctx));
        // Find throw statements
        var throwStatements = findAllStatements(root).stream()
                                               .filter(stmt -> text(stmt).trim()
                                                                   .startsWith("throw "))
                                               .map(stmt -> createThrowDiagnostic(stmt, ctx));
        // Find methods with throws clause
        var throwsClauses = findAllMethods(root).stream()
                                          .filter(this::hasThrowsClause)
                                          .map(method -> createThrowsClauseDiagnostic(method, ctx));

        return Stream.concat(Stream.concat(exceptionClasses, throwStatements), throwsClauses);
    }

    private boolean extendsException(Cursor cls) {
        var clsText = text(cls);

        return clsText.contains("extends Exception") || clsText.contains("extends RuntimeException") || clsText.contains("extends Throwable");
    }

    private boolean hasThrowsClause(Cursor method) {
        return contains(method, RuleKind.THROWS);
    }

    private Diagnostic createExceptionClassDiagnostic(Cursor cls, LintContext ctx) {
        var className = extractClassName(text(cls));

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(cls),
                                     startColumn(cls),
                                     "Exception class '" + className + "' - use Cause instead",
                                     "JBCT doesn't use exceptions for business errors. Define a Cause type.");
    }

    private Diagnostic createThrowDiagnostic(Cursor stmt, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(stmt),
                                     startColumn(stmt),
                                     "throw statement forbidden - return Result.failure() instead",
                                     "JBCT uses Result<T> for error handling, not exceptions.");
    }

    private Diagnostic createThrowsClauseDiagnostic(Cursor method, LintContext ctx) {
        var methodName = extractMethodName(text(method));

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Method '" + methodName + "' has throws clause - use Result<T> instead",
                                     "JBCT methods shouldn't declare checked exceptions.");
    }

    private static String extractClassName(String clsText) {
        var matcher = CLASS_NAME_PATTERN.matcher(clsText);

        return matcher.find()
               ? matcher.group(1)
               : "(unknown)";
    }

    private static String extractMethodName(String memberText) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);

        return matcher.find()
               ? matcher.group(1)
               : "(unknown)";
    }
}
