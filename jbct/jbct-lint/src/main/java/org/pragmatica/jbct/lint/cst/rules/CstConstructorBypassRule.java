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

/// JBCT-VO-02: Direct constructor calls bypass factory validation.
///
/// A type is considered a value object if any of its records contains a `Result<T>`
/// factory. `new T(...)` outside a factory method is flagged unless one of:
/// - The enclosing static method itself returns `Result<...>` (parse factory).
/// - The enclosing type is itself a value object (parse + construct factory pattern):
///   the inner factory builds T from already-validated typed arguments and does not
///   need to re-wrap in Result.
public class CstConstructorBypassRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-VO-02";
    private static final Pattern NEW_PATTERN = Pattern.compile("new\\s+(\\w+)\\s*\\(");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        var packageName = findFirst(root, RuleId.PackageDecl.class).flatMap(pd -> findFirst(pd,
                                                                                            RuleId.QualifiedName.class))
                                   .map(qn -> text(qn, source))
                                   .or("");
        if (!ctx.shouldLint(packageName)) {
            return Stream.empty();
        }
        // Collect value object types (records with Result factories)
        var valueObjectTypes = collectValueObjectTypes(root, source);
        if (valueObjectTypes.isEmpty()) {
            return Stream.empty();
        }
        // Find direct constructor calls outside factory methods
        return findAll(root, RuleId.Primary.class).stream()
                      .filter(node -> isDirectConstruction(node, source, valueObjectTypes))
                      .filter(node -> !isInAllowedContext(root, node, source, valueObjectTypes))
                      .map(node -> createDiagnostic(node, source, ctx));
    }

    private Set<String> collectValueObjectTypes(CstNode root, String source) {
        var types = new HashSet<String>();
        findAllRecords(root)
        .forEach(record -> {
                     var name = childByRule(record, RuleId.Identifier.class).map(id -> text(id, source))
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

    private boolean isInAllowedContext(CstNode root, CstNode node, String source, Set<String> valueObjectTypes) {
        // Two acceptance criteria:
        //   (a) The construction happens inside the value-object type's own scope.
        //       Anything inside T's body is trusted: the parse factory has validated
        //       inputs, and any internal members (construct factories, with-builders,
        //       static constants) operate on already-validated state.
        //   (b) The construction happens inside a static method on another type that
        //       returns Result<...>. This covers cross-type parse factories.
        if (enclosingTypeIsValueObject(root, node, source, valueObjectTypes)) {
            return true;
        }
        return findAncestor(root, node, RuleId.ClassMember.class)
                          .orElse(() -> findAncestor(root, node, RuleId.RecordMember.class))
                          .map(member -> {
                                   var memberText = text(member, source);
                                   return memberText.contains("static ") && memberText.contains("Result<");
                               })
                          .or(false);
    }

    private boolean enclosingTypeIsValueObject(CstNode root, CstNode node, String source, Set<String> valueObjectTypes) {
        return findAncestor(root, node, RuleId.TypeKind.class)
                          .flatMap(typeKind -> childByRule(typeKind, RuleId.Identifier.class))
                          .map(id -> text(id, source))
                          .map(valueObjectTypes::contains)
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
