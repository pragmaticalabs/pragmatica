package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-STY-04: Utility class pattern.
 *
 * Detects:
 * 1. Final classes with private constructor + only static methods → suggest sealed interface
 * 2. Sealed interfaces used as utilities missing 'unused' record
 */
public class CstUtilityClassRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-STY-04";

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
        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }
        // TypeDecl contains: Annotation* Modifier* TypeKind (where TypeKind is ClassDecl/InterfaceDecl/etc.)
        // So we need to look at TypeDecl to get modifiers like 'final' or 'sealed'
        var utilityClassDiagnostics = findAll(root, RuleId.TypeDecl.class).stream()
                                             .filter(td -> contains(td, RuleId.ClassDecl.class))
                                             .filter(td -> isUtilityClass(td, source))
                                             .map(td -> createUtilityClassDiagnostic(td, source, ctx));
        var missingUnusedDiagnostics = findAll(root, RuleId.TypeDecl.class).stream()
                                              .filter(td -> contains(td, RuleId.InterfaceDecl.class))
                                              .filter(td -> isSealedUtilityInterface(td, source))
                                              .filter(td -> !hasUnusedRecord(td, source))
                                              .map(td -> createMissingUnusedDiagnostic(td, source, ctx));
        return Stream.concat(utilityClassDiagnostics, missingUnusedDiagnostics);
    }

    private boolean isUtilityClass(CstNode cls, String source) {
        var classText = text(cls, source);
        // Check for final class
        if (!classText.contains("final ") || !classText.contains("class ")) {
            return false;
        }
        // Check for private constructor
        if (!classText.contains("private ") || !hasPrivateConstructor(classText)) {
            return false;
        }
        // Check that all methods are static (excluding constructor)
        return hasOnlyStaticMethods(classText);
    }

    private boolean hasPrivateConstructor(String classText) {
        // Look for private constructor pattern: private ClassName(
        var classNameMatch = classText.indexOf("class ");
        if (classNameMatch < 0) return false;
        var afterClass = classText.substring(classNameMatch + 6)
                                  .trim();
        var nameEnd = afterClass.indexOf(' ');
        if (nameEnd < 0) nameEnd = afterClass.indexOf('{');
        if (nameEnd < 0) return false;
        var className = afterClass.substring(0, nameEnd)
                                  .trim();
        return classText.contains("private " + className + "(");
    }

    private boolean hasOnlyStaticMethods(String classText) {
        // Find method declarations that are not static and not constructor
        var bodyStart = classText.indexOf('{');
        if (bodyStart < 0) return false;
        var body = classText.substring(bodyStart);
        // Look for non-static method patterns (excluding constructors)
        // A non-static method would be: public/protected/private <return-type> methodName(
        // without static keyword before it
        // Simple heuristic: if body contains methods and all contain "static ", it's utility
        var lines = body.split("\n");
        for (var line : lines) {
            var trimmed = line.trim();
            // Skip if it's a constructor (private ClassName()
            if (trimmed.startsWith("private ") && trimmed.contains("()")) {
                continue;
            }
            // Check for method signature without static
            if ((trimmed.startsWith("public ") || trimmed.startsWith("protected ")) && trimmed.contains("(") && !trimmed.contains("static ")) {
                return false;
            }
        }
        return true;
    }

    private boolean isSealedUtilityInterface(CstNode iface, String source) {
        var ifaceText = text(iface, source);
        // Check for sealed interface with static methods
        if (!ifaceText.contains("sealed ") || !ifaceText.contains("interface ")) {
            return false;
        }
        // Check if it has static methods (utility interface pattern)
        return ifaceText.contains("static ") && ifaceText.contains("(");
    }

    private boolean hasUnusedRecord(CstNode iface, String source) {
        var ifaceText = text(iface, source);
        // Check for "record unused()" pattern
        return ifaceText.contains("record unused()");
    }

    private Diagnostic createUtilityClassDiagnostic(CstNode typeDecl, String source, LintContext ctx) {
        var className = findFirst(typeDecl, RuleId.ClassDecl.class).flatMap(cls -> childByRule(cls,
                                                                                               RuleId.Identifier.class))
                                 .map(id -> text(id, source))
                                 .or("UtilityClass");
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(typeDecl),
                                     startColumn(typeDecl),
                                     "Utility class '" + className + "' should be a sealed interface",
                                     "Convert final class with private constructor to sealed interface with 'unused' record.")
                         .withExample("""
                // Before: utility class
                public final class %s {
                    private %s() {}
                    public static Result<String> process(...) { ... }
                }

                // After: sealed interface
                public sealed interface %s {
                    static Result<String> process(...) { ... }
                    record unused() implements %s {}
                }
                """.formatted(className, className, className, className));
    }

    private Diagnostic createMissingUnusedDiagnostic(CstNode typeDecl, String source, LintContext ctx) {
        var ifaceName = findFirst(typeDecl, RuleId.InterfaceDecl.class).flatMap(iface -> childByRule(iface,
                                                                                                     RuleId.Identifier.class))
                                 .map(id -> text(id, source))
                                 .or("UtilityInterface");
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(typeDecl),
                                     startColumn(typeDecl),
                                     "Sealed utility interface '" + ifaceName + "' missing 'unused' record",
                                     "Add 'record unused() implements " + ifaceName
                                     + " {}' to satisfy sealed permit requirement.")
                         .withExample("""
                public sealed interface %s {
                    static Result<String> process(...) { ... }

                    record unused() implements %s {}  // Add this
                }
                """.formatted(ifaceName, ifaceName));
    }
}
