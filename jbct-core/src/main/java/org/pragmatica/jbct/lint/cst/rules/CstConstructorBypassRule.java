package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-VO-02: Direct constructor calls bypass factory validation.
 */
public class CstConstructorBypassRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-VO-02";
    private static final Pattern NEW_PATTERN = Pattern.compile("new\\s+(\\w+)\\s*\\(");

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
        // Collect value object types (records with Result factories)
        var valueObjectTypes = collectValueObjectTypes(root, source);
        if (valueObjectTypes.isEmpty()) {
            return Stream.empty();
        }
        // Find direct constructor calls outside factory methods
        return findAll(root, RuleId.Primary.class)
               .stream()
               .filter(node -> isDirectConstruction(node, source, valueObjectTypes))
               .filter(node -> !isInAllowedContext(root, node, source))
               .map(node -> createDiagnostic(node, source, ctx));
    }

    private Set<String> collectValueObjectTypes(CstNode root, String source) {
        var types = new HashSet<String>();
        findAll(root, RuleId.RecordDecl.class)
        .forEach(record -> {
                     var name = childByRule(record, RuleId.Identifier.class)
                                .map(id -> text(id, source))
                                .or("");
                     var recordText = text(record, source);
                     if (recordText.contains("Result<" + name + ">")) {
                     types.add(name);
                 }
                 });
        return types;
    }

    private boolean isDirectConstruction(CstNode node, String source, Set<String> valueObjectTypes) {
        var nodeText = text(node, source);
        var matcher = NEW_PATTERN.matcher(nodeText);
        if (matcher.find()) {
            var typeName = matcher.group(1);
            return valueObjectTypes.contains(typeName);
        }
        return false;
    }

    private boolean isInAllowedContext(CstNode root, CstNode node, String source) {
        // Check if inside factory method (static method returning Result)
        // Note: "static" keyword is in ClassMember, not MethodDecl
        return findAncestor(root, node, RuleId.ClassMember.class)
               .map(member -> {
                        var memberText = text(member, source);
                        // Allow in factory methods (static methods returning Result)
        return memberText.contains("static ") && memberText.contains("Result<");
                    })
               .or(false);
    }

    private Diagnostic createDiagnostic(CstNode node, String source, LintContext ctx) {
        var nodeText = text(node, source);
        var matcher = NEW_PATTERN.matcher(nodeText);
        var typeName = matcher.find()
                       ? matcher.group(1)
                       : "ValueObject";
        var factoryName = camelCase(typeName);
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(node),
                                     startColumn(node),
                                     "Direct 'new " + typeName + "(...)' bypasses factory validation",
                                     "Value objects should be created through factory methods.")
                         .withExample("""
            // Before
            var value = new %s(rawInput);

            // After
            var result = %s.%s(rawInput);
            """.formatted(typeName, typeName, factoryName));
    }

    private String camelCase(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
