package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-STY-03: No fully qualified class names in code.
 */
public class CstFullyQualifiedNameRule implements CstLintRule {

    private static final String RULE_ID = "JBCT-STY-03";
    // Pattern to detect FQCN like java.util.List or com.example.Foo
    private static final Pattern FQCN_PATTERN = Pattern.compile(
        "\\b([a-z][a-z0-9]*\\.)+[A-Z][a-zA-Z0-9]*\\b"
    );

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Use imports instead of fully qualified class names";
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

        // Find FQCN in method bodies (not imports or package declaration)
        return findAll(root, RuleId.MethodDecl.class).stream()
            .flatMap(method -> findFqcnInMethod(method, source, ctx));
    }

    private Stream<Diagnostic> findFqcnInMethod(CstNode method, String source, LintContext ctx) {
        var methodText = text(method, source);
        var matcher = FQCN_PATTERN.matcher(methodText);

        return Stream.iterate(matcher.find(), found -> found, found -> matcher.find())
            .map(_ -> matcher.group())
            .filter(this::isNotAnnotation)
            .map(fqcn -> createDiagnostic(method, fqcn, ctx))
            .limit(1); // One diagnostic per method
    }

    private boolean isNotAnnotation(String fqcn) {
        // Skip common annotation packages
        return !fqcn.startsWith("java.lang.") &&
               !fqcn.startsWith("javax.annotation.");
    }

    private Diagnostic createDiagnostic(CstNode method, String fqcn, LintContext ctx) {
        var methodName = childByRule(method, RuleId.Identifier.class)
            .map(id -> text(id, method.span().extract(fqcn)))
            .or("(unknown)");

        return Diagnostic.diagnostic(
            RULE_ID,
            ctx.severityFor(RULE_ID),
            ctx.fileName(),
            startLine(method),
            startColumn(method),
            "Fully qualified name '" + fqcn + "' - use import instead",
            "FQCNs reduce readability. Add an import and use the simple name."
        );
    }
}
