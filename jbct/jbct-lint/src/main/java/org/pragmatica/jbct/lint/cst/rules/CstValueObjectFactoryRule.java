package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-VO-01: Value objects need a factory method (returning T, Option<T>, or Result<T>).
public class CstValueObjectFactoryRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-VO-01";

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
        var sealedInterfaceNames = collectSealedInterfaceNames(root, source);
        // Check records
        return findAll(root, RuleId.RecordDecl.class).stream()
                      .filter(record -> needsFactoryMethod(root, record, source, sealedInterfaceNames))
                      .map(record -> createDiagnostic(record, source, ctx));
    }

    private boolean needsFactoryMethod(CstNode root, CstNode record, String source, Set<String> sealedInterfaceNames) {
        var recordName = childByRule(record, RuleId.Identifier.class).map(id -> text(id, source))
                                    .or("");
        if (recordName.isEmpty()) return false;
        // Skip 'unused' records (sealed interface utility pattern marker)
        if ("unused".equals(recordName)) return false;
        // Skip zero-component records — nothing to validate
        if (hasNoComponents(record)) return false;
        // Skip records implementing a sealed interface declared in the same file
        if (implementsSealedInterface(record, source, sealedInterfaceNames)) return false;
        // Skip records implementing their enclosing interface (implementation records, not value objects)
        if (implementsEnclosingInterface(root, record, source)) return false;
        // Skip builder-pattern records (have with*() methods returning Self)
        if (hasBuilderMethods(record, recordName, source)) return false;
        // Skip local records defined inside methods (implementation records, not value objects)
        if (isLocalRecord(root, record)) return false;
        // Check for factory returning Result<T>, Option<T>, or T
        var recordText = text(record, source);
        var hasResultFactory = recordText.contains("Result<" + recordName + ">")
                               || recordText.contains("Result<" + recordName + " ")
                               || recordText.contains("Result<" + recordName + "<");
        var hasOptionFactory = recordText.contains("Option<" + recordName + ">")
                               || recordText.contains("Option<" + recordName + " ")
                               || recordText.contains("Option<" + recordName + "<");
        var hasPlainFactory = recordText.contains("static " + recordName + " ");
        return !hasResultFactory && !hasOptionFactory && !hasPlainFactory;
    }

    private boolean implementsEnclosingInterface(CstNode root, CstNode record, String source) {
        var implementedNames = childByRule(record, RuleId.ImplementsClause.class)
                                          .map(clause -> extractImplementedNames(clause, source))
                                          .or(Set.of());
        if (implementedNames.isEmpty()) return false;
        // Find the nearest enclosing InterfaceDecl
        return findAncestor(root, record, RuleId.InterfaceDecl.class)
                          .flatMap(iface -> childByRule(iface, RuleId.Identifier.class))
                          .map(id -> text(id, source))
                          .map(implementedNames::contains)
                          .or(false);
    }

    private boolean isLocalRecord(CstNode root, CstNode record) {
        return findAncestor(root, record, RuleId.MethodDecl.class).isPresent();
    }

    private boolean hasNoComponents(CstNode record) {
        return childByRule(record, RuleId.RecordComponents.class)
                          .map(rc -> childrenByRule(rc, RuleId.RecordComp.class).isEmpty())
                          .or(true);
    }

    private boolean hasBuilderMethods(CstNode record, String recordName, String source) {
        return findAll(record, RuleId.MethodDecl.class).stream()
                      .anyMatch(method -> isWithMethodReturningSelf(method, recordName, source));
    }

    private boolean isWithMethodReturningSelf(CstNode method, String recordName, String source) {
        var methodName = childByRule(method, RuleId.Identifier.class).map(id -> text(id, source))
                                    .or("");
        if (!methodName.startsWith("with")) return false;
        return childByRule(method, RuleId.Type.class).map(type -> text(type, source).trim())
                          .map(recordName::equals)
                          .or(false);
    }

    private boolean implementsSealedInterface(CstNode record, String source, Set<String> sealedInterfaceNames) {
        return childByRule(record, RuleId.ImplementsClause.class)
                          .map(clause -> extractImplementedNames(clause, source))
                          .or(Set.of())
                          .stream()
                          .anyMatch(sealedInterfaceNames::contains);
    }

    private Set<String> extractImplementedNames(CstNode implementsClause, String source) {
        return findAll(implementsClause, RuleId.Identifier.class).stream()
                      .map(id -> text(id, source))
                      .collect(Collectors.toSet());
    }

    private Set<String> collectSealedInterfaceNames(CstNode root, String source) {
        // Check both top-level TypeDecl and nested ClassMember nodes for sealed interfaces
        var fromTypeDecls = findAll(root, RuleId.TypeDecl.class).stream()
                                   .filter(node -> hasSealedModifier(node, source));
        var fromClassMembers = findAll(root, RuleId.ClassMember.class).stream()
                                      .filter(node -> hasSealedModifier(node, source));
        return Stream.concat(fromTypeDecls, fromClassMembers)
                     .filter(node -> contains(node, RuleId.InterfaceDecl.class))
                     .map(node -> extractInterfaceName(node, source))
                     .flatMap(Optional::stream)
                     .collect(Collectors.toSet());
    }

    private boolean hasSealedModifier(CstNode node, String source) {
        return childrenByRule(node, RuleId.Modifier.class).stream()
                            .anyMatch(mod -> "sealed".equals(text(mod, source).trim()));
    }

    private Optional<String> extractInterfaceName(CstNode node, String source) {
        return findFirst(node, RuleId.InterfaceDecl.class)
                          .flatMap(iface -> childByRule(iface, RuleId.Identifier.class))
                          .map(id -> text(id, source))
                          .toOptional();
    }

    private Diagnostic createDiagnostic(CstNode record, String source, LintContext ctx) {
        var name = childByRule(record, RuleId.Identifier.class).map(id -> text(id, source))
                              .or("(unknown)");
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
