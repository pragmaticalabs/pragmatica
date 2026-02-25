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

/// JBCT-VO-01: Value objects need factory returning Result<T>.
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
                      .filter(record -> needsFactoryMethod(record, source, sealedInterfaceNames))
                      .map(record -> createDiagnostic(record, source, ctx));
    }

    private boolean needsFactoryMethod(CstNode record, String source, Set<String> sealedInterfaceNames) {
        var recordName = childByRule(record, RuleId.Identifier.class).map(id -> text(id, source))
                                    .or("");
        if (recordName.isEmpty()) return false;
        // Skip 'unused' records (sealed interface utility pattern marker)
        if ("unused".equals(recordName)) return false;
        // Skip zero-component records — nothing to validate
        if (hasNoComponents(record)) return false;
        // Skip records implementing a sealed interface declared in the same file
        if (implementsSealedInterface(record, source, sealedInterfaceNames)) return false;
        // Skip builder-pattern records (have with*() methods returning Self)
        if (hasBuilderMethods(record, recordName, source)) return false;
        // Check if has Result-returning static method
        var recordText = text(record, source);
        var resultPrefix = "Result<" + recordName;
        return !recordText.contains(resultPrefix + ">") &&
        !recordText.contains(resultPrefix + " ") &&
        !recordText.contains(resultPrefix + "<");
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
        return findAll(root, RuleId.TypeDecl.class).stream()
                      .filter(td -> isSealedInterfaceDecl(td, source))
                      .map(td -> extractInterfaceName(td, source))
                      .flatMap(Optional::stream)
                      .collect(Collectors.toSet());
    }

    private boolean isSealedInterfaceDecl(CstNode typeDecl, String source) {
        var hasSealedModifier = childrenByRule(typeDecl, RuleId.Modifier.class).stream()
                                             .anyMatch(mod -> "sealed".equals(text(mod, source).trim()));
        var hasInterfaceDecl = contains(typeDecl, RuleId.InterfaceDecl.class);
        return hasSealedModifier && hasInterfaceDecl;
    }

    private Optional<String> extractInterfaceName(CstNode typeDecl, String source) {
        return findFirst(typeDecl, RuleId.InterfaceDecl.class)
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
                                     "Record '" + name + "' should have a factory method returning Result<" + name + ">",
                                     "JBCT value objects use factory methods for validation.")
                         .withExample("""
            public record %s(...) {
                public static Result<%s> %s(...) {
                    // Validate and return
                    return Result.success(new %s(...));
                }
            }
            """.formatted(name, name, camelName, name));
    }

    private String camelCase(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
