package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

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

    // v6: Identifier is a token, not a CST rule. Extract record name from `record <Name>`.
    private static final Pattern RECORD_NAME_PATTERN =
        Pattern.compile("\\brecord\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    // Extract type name (class/interface/enum/record) from a TypeKind subtree.
    private static final Pattern TYPE_NAME_PATTERN =
        Pattern.compile("\\b(?:class|interface|enum|record|@interface)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }
        // Collect value object types (records with Result factories)
        var valueObjectTypes = collectValueObjectTypes(root);
        if (valueObjectTypes.isEmpty()) {
            return Stream.empty();
        }
        // Find direct constructor calls outside factory methods
        return findAll(root, RuleKind.PRIMARY).stream()
                      .filter(node -> isDirectConstruction(node, valueObjectTypes))
                      .filter(node -> !isInAllowedContext(root, node, valueObjectTypes))
                      .map(node -> createDiagnostic(node, ctx));
    }

    private Set<String> collectValueObjectTypes(Cursor root) {
        var types = new HashSet<String>();
        findAllRecords(root)
        .forEach(record -> {
                     var recordText = text(record);
                     var nameMatcher = RECORD_NAME_PATTERN.matcher(recordText);
                     if (!nameMatcher.find()) {
                         return;
                     }
                     var name = nameMatcher.group(1);
                     if (recordText.contains("Result<" + name + ">")) {
                         types.add(name);
                     }
                 });
        return types;
    }

    private boolean isDirectConstruction(Cursor node, Set<String> valueObjectTypes) {
        var nodeText = text(node);
        var matcher = NEW_PATTERN.matcher(nodeText);
        if (matcher.find()) {
            var typeName = matcher.group(1);
            return valueObjectTypes.contains(typeName);
        }
        return false;
    }

    private boolean isInAllowedContext(Cursor root, Cursor node, Set<String> valueObjectTypes) {
        // Two acceptance criteria:
        //   (a) The construction happens inside the value-object type's own scope.
        //       Anything inside T's body is trusted: the parse factory has validated
        //       inputs, and any internal members (construct factories, with-builders,
        //       static constants) operate on already-validated state.
        //   (b) The construction happens inside a static method on another type that
        //       returns Result<...>. This covers cross-type parse factories.
        if (enclosingTypeIsValueObject(root, node, valueObjectTypes)) {
            return true;
        }
        return findAncestor(root, node, RuleKind.CLASS_MEMBER)
                          .orElse(() -> findAncestor(root, node, RuleKind.RECORD_MEMBER))
                          .map(member -> {
                                   var memberText = text(member);
                                   return memberText.contains("static ") && memberText.contains("Result<");
                               })
                          .or(false);
    }

    private boolean enclosingTypeIsValueObject(Cursor root, Cursor node, Set<String> valueObjectTypes) {
        return findAncestor(root, node, RuleKind.TYPE_KIND)
                          .map(typeKind -> {
                                   var matcher = TYPE_NAME_PATTERN.matcher(text(typeKind));
                                   return matcher.find() ? matcher.group(1) : "";
                               })
                          .map(valueObjectTypes::contains)
                          .or(false);
    }

    private Diagnostic createDiagnostic(Cursor node, LintContext ctx) {
        var nodeText = text(node);
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
