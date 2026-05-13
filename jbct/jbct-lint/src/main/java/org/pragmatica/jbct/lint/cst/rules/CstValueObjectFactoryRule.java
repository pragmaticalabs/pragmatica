package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.CstNodes;
import org.pragmatica.jbct.parser.RuleKind;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-VO-01: Value objects need a factory method (returning T, Option<T>, or Result<T>).
public class CstValueObjectFactoryRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-VO-01";

    private static final Pattern RECORD_NAME_PATTERN = Pattern.compile("\\brecord\\s+(\\w+)");
    private static final Pattern INTERFACE_NAME_PATTERN = Pattern.compile("\\binterface\\s+(\\w+)");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\b");
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
        var sealedInterfaceNames = collectSealedInterfaceNames(root);
        // Check records
        return findAllRecords(root).stream()
                      .filter(record -> needsFactoryMethod(root, record, sealedInterfaceNames))
                      .map(record -> createDiagnostic(record, ctx));
    }

    private boolean needsFactoryMethod(Cursor root, Cursor record, Set<String> sealedInterfaceNames) {
        var recordName = extractRecordName(record);
        if (recordName.isEmpty()) return false;
        // Skip 'unused' records (sealed interface utility pattern marker)
        if ("unused".equals(recordName)) return false;
        // Skip zero-component records — nothing to validate
        if (hasNoComponents(record)) return false;
        // Skip records implementing a sealed interface declared in the same file
        if (implementsSealedInterface(record, sealedInterfaceNames)) return false;
        // Skip records implementing their enclosing interface (implementation records, not value objects)
        if (implementsEnclosingInterface(root, record)) return false;
        // Skip builder-pattern records (have with*() methods returning Self)
        if (hasBuilderMethods(record, recordName)) return false;
        // Skip local records defined inside methods (implementation records, not value objects)
        if (isLocalRecord(root, record)) return false;
        // Check for factory returning Result<T>, Option<T>, or T
        var recordText = text(record);
        var hasResultFactory = recordText.contains("Result<" + recordName + ">")
                               || recordText.contains("Result<" + recordName + " ")
                               || recordText.contains("Result<" + recordName + "<");
        var hasOptionFactory = recordText.contains("Option<" + recordName + ">")
                               || recordText.contains("Option<" + recordName + " ")
                               || recordText.contains("Option<" + recordName + "<");
        var hasPlainFactory = recordText.contains("static " + recordName + " ");
        return !hasResultFactory && !hasOptionFactory && !hasPlainFactory;
    }

    private boolean implementsEnclosingInterface(Cursor root, Cursor record) {
        var implementedNames = childByRule(record, RuleKind.IMPLEMENTS_CLAUSE)
                                          .map(this::extractImplementedNames)
                                          .or(Set.of());
        if (implementedNames.isEmpty()) return false;
        // Find the nearest enclosing TypeKind that is an interface
        return findAncestor(root, record, RuleKind.TYPE_KIND)
                          .filter(tk -> hasChildOfRule(tk, RuleKind.INTERFACE_DECL))
                          .map(iface -> extractInterfaceNameFromTypeKind(iface))
                          .filter(name -> !name.isEmpty())
                          .map(implementedNames::contains)
                          .or(false);
    }

    private boolean isLocalRecord(Cursor root, Cursor record) {
        return findAncestor(root, record, RuleKind.MEMBER)
                          .filter(CstNodes::isMethodMember)
                          .isPresent();
    }

    private boolean hasNoComponents(Cursor record) {
        return childByRule(record, RuleKind.RECORD_COMPONENTS)
                          .map(rc -> childrenByRule(rc, RuleKind.RECORD_COMP).isEmpty())
                          .or(true);
    }

    private boolean hasBuilderMethods(Cursor record, String recordName) {
        return findAllMethods(record).stream()
                      .anyMatch(method -> isWithMethodReturningSelf(method, recordName));
    }

    private boolean isWithMethodReturningSelf(Cursor method, String recordName) {
        var methodText = text(method);
        var nameMatcher = METHOD_NAME_PATTERN.matcher(methodText);
        if (!nameMatcher.find()) return false;
        var methodName = nameMatcher.group(1);
        if (!methodName.startsWith("with")) return false;
        return childByRule(method, RuleKind.TYPE).map(type -> text(type).trim())
                          .map(recordName::equals)
                          .or(false);
    }

    private boolean implementsSealedInterface(Cursor record, Set<String> sealedInterfaceNames) {
        return childByRule(record, RuleKind.IMPLEMENTS_CLAUSE)
                          .map(this::extractImplementedNames)
                          .or(Set.of())
                          .stream()
                          .anyMatch(sealedInterfaceNames::contains);
    }

    private Set<String> extractImplementedNames(Cursor implementsClause) {
        var matcher = IDENTIFIER_PATTERN.matcher(text(implementsClause));
        var names = new java.util.HashSet<String>();
        while (matcher.find()) {
            var token = matcher.group(1);
            if (!"implements".equals(token)) {
                names.add(token);
            }
        }
        return names;
    }

    private Set<String> collectSealedInterfaceNames(Cursor root) {
        // Check both top-level TypeDecl and nested ClassMember nodes for sealed interfaces.
        // In v6 modifiers are tokens (not CST children), so we detect 'sealed' textually
        // restricted to the head of the declaration (before the first '{').
        var fromTypeDecls = findAll(root, RuleKind.TYPE_DECL).stream()
                                   .filter(this::hasSealedModifier);
        var fromClassMembers = findAll(root, RuleKind.CLASS_MEMBER).stream()
                                      .filter(this::hasSealedModifier);
        return Stream.concat(fromTypeDecls, fromClassMembers)
                     .filter(node -> containsInterface(node))
                     .map(this::extractInterfaceName)
                     .flatMap(Optional::stream)
                     .collect(Collectors.toSet());
    }

    private boolean hasSealedModifier(Cursor node) {
        var declText = text(node);
        var headEnd = declText.indexOf('{');
        var head = headEnd >= 0 ? declText.substring(0, headEnd) : declText;
        // Match 'sealed' as a whole-word modifier in the declaration head.
        return Pattern.compile("\\bsealed\\b").matcher(head).find();
    }

    private Optional<String> extractInterfaceName(Cursor node) {
        var matcher = INTERFACE_NAME_PATTERN.matcher(text(node));
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private String extractRecordName(Cursor record) {
        var matcher = RECORD_NAME_PATTERN.matcher(text(record));
        return matcher.find() ? matcher.group(1) : "";
    }

    private String extractInterfaceNameFromTypeKind(Cursor typeKind) {
        var matcher = INTERFACE_NAME_PATTERN.matcher(text(typeKind));
        return matcher.find() ? matcher.group(1) : "";
    }

    private Diagnostic createDiagnostic(Cursor record, LintContext ctx) {
        var name = extractRecordName(record);
        if (name.isEmpty()) {
            name = "(unknown)";
        }
        var camelName = camelCase(name);
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(record),
                                     startColumn(record),
                                     "Record '" + name + "' should have a factory method (returning " + name
                                     + ", Option<" + name + ">, or Result<" + name + ">)",
                                     "JBCT value objects use factory methods for construction and validation.")
                         .withExample("""
            // Plain factory (no validation needed):
            public record %1$s(...) {
                public static %1$s %2$s(...) { return new %1$s(...); }
            }
            // Option factory (value may be absent):
            public record %1$s(...) {
                public static Option<%1$s> %2$s(...) { return Option.option(...).map(%1$s::new); }
            }
            // Result factory (validation required):
            public record %1$s(...) {
                public static Result<%1$s> %2$s(...) { return Result.success(new %1$s(...)); }
            }
            """.formatted(name, camelName));
    }

    private String camelCase(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
