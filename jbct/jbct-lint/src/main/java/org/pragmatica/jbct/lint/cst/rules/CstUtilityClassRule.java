package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-STY-04: Utility class pattern.
///
/// Detects:
/// 1. Final classes with private constructor + only static methods → suggest sealed interface
/// 2. Sealed interfaces used as utilities missing 'unused' record
public class CstUtilityClassRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-STY-04";

    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("\\bclass\\s+(\\w+)");
    private static final Pattern INTERFACE_NAME_PATTERN = Pattern.compile("\\binterface\\s+(\\w+)");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }
        // TypeDecl contains: Annotation* Modifier* TypeKind (where TypeKind is ClassDecl/InterfaceDecl/etc.)
        // So we need to look at TypeDecl to get modifiers like 'final' or 'sealed'
        var utilityClassDiagnostics = findAll(root, RuleKind.TYPE_DECL).stream()
                                             .filter(td -> containsClass(td))
                                             .filter(td -> isUtilityClass(td))
                                             .map(td -> createUtilityClassDiagnostic(td, ctx));
        var missingUnusedDiagnostics = findAll(root, RuleKind.TYPE_DECL).stream()
                                              .filter(td -> containsInterface(td))
                                              .filter(td -> isSealedUtilityInterface(td))
                                              .filter(td -> !hasUnusedRecord(td))
                                              .map(td -> createMissingUnusedDiagnostic(td, ctx));
        return Stream.concat(utilityClassDiagnostics, missingUnusedDiagnostics);
    }

    private boolean isUtilityClass(Cursor cls) {
        var classText = text(cls);
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

    private boolean isSealedUtilityInterface(Cursor typeDecl) {
        var declText = text(typeDecl);
        // Detect 'sealed' as a modifier appearing before the interface keyword.
        // In v6 modifiers are tokens (no CST node), so we rely on textual presence.
        if (!declText.contains("sealed ")) return false;
        // Must have static methods (utility interface pattern)
        return declText.contains("static ") && declText.contains("(");
    }

    private boolean hasUnusedRecord(Cursor iface) {
        var ifaceText = text(iface);
        // Check for "record unused()" pattern
        return ifaceText.contains("record unused()");
    }

    private Diagnostic createUtilityClassDiagnostic(Cursor typeDecl, LintContext ctx) {
        var className = extractName(typeDecl, CLASS_NAME_PATTERN, "UtilityClass");
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

    private Diagnostic createMissingUnusedDiagnostic(Cursor typeDecl, LintContext ctx) {
        var ifaceName = extractName(typeDecl, INTERFACE_NAME_PATTERN, "UtilityInterface");
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

    private static String extractName(Cursor typeDecl, Pattern pattern, String fallback) {
        var matcher = pattern.matcher(text(typeDecl));
        return matcher.find() ? matcher.group(1) : fallback;
    }
}
