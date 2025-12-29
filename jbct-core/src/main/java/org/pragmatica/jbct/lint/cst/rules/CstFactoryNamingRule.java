package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-NAM-01: Factory method naming convention.
 *
 * Factory methods should be named after the type: TypeName.typeName()
 */
public class CstFactoryNamingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-NAM-01";

    @Override
    public String ruleId() {
        return RULE_ID;
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
        return findAll(root, RuleId.RecordDecl.class)
               .stream()
               .flatMap(record -> checkFactoryMethods(record, source, ctx));
    }

    private Stream<Diagnostic> checkFactoryMethods(CstNode record, String source, LintContext ctx) {
        var typeName = childByRule(record, RuleId.Identifier.class)
                       .map(id -> text(id, source))
                       .or("");
        if (typeName.isEmpty()) return Stream.empty();
        var expectedName = camelCase(typeName);
        // Find ClassMember nodes containing static factory methods
        return findAll(record, RuleId.ClassMember.class)
               .stream()
               .filter(member -> isStaticFactoryMember(member, typeName, source))
               .flatMap(member -> findFirst(member, RuleId.MethodDecl.class)
                                  .stream())
               .filter(method -> !isCorrectlyNamed(method, expectedName, source))
               .map(method -> createDiagnostic(method, typeName, expectedName, source, ctx));
    }

    private boolean isStaticFactoryMember(CstNode member, String typeName, String source) {
        var memberText = text(member, source);
        return memberText.contains("static ") &&
        (memberText.contains("Result<" + typeName + ">") ||
        memberText.contains(" " + typeName + " "));
    }

    private boolean isCorrectlyNamed(CstNode method, String expectedName, String source) {
        var methodName = childByRule(method, RuleId.Identifier.class)
                         .map(id -> text(id, source))
                         .or("");
        return methodName.equals(expectedName);
    }

    private Diagnostic createDiagnostic(CstNode method,
                                        String typeName,
                                        String expectedName,
                                        String source,
                                        LintContext ctx) {
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
        "JBCT naming convention: " + typeName + "." + expectedName + "(...)");
    }

    private String camelCase(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
