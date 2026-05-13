package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-SEAL-01: Error interfaces should be sealed.
///
/// Detects interfaces that extend Cause but are not declared as sealed.
/// Sealed error interfaces enable exhaustive pattern matching.
public class CstSealedErrorRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-SEAL-01";
    private static final String DOC_LINK = "https://github.com/siy/coding-technology/blob/main/series/part-04-error-handling.md";

    // Match "interface Name" possibly preceded by modifier tokens. Captures the name.
    private static final Pattern INTERFACE_NAME_PATTERN =
        Pattern.compile("\\binterface\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    // Match modifiers preceding the type keyword. "sealed" must appear before the
    // 'interface'/'class' keyword, not somewhere deep inside the body.
    private static final Pattern SEALED_HEADER_PATTERN =
        Pattern.compile("(?:^|[\\s\\n])sealed\\b[^{]*\\binterface\\b");
    private static final Pattern EXTENDS_CAUSE_PATTERN =
        Pattern.compile("\\bextends\\b[^{]*\\bCause\\b");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        // Find TypeDecl nodes that contain InterfaceDecl
        return findAll(root, RuleKind.TYPE_DECL).stream()
                      .filter(this::hasInterfaceDecl)
                      .filter(this::extendsCause)
                      .filter(typeDecl -> !isSealed(typeDecl))
                      .map(typeDecl -> createDiagnostic(getInterfaceDecl(typeDecl), ctx));
    }

    private boolean hasInterfaceDecl(Cursor typeDecl) {
        return childByRule(typeDecl, RuleKind.TYPE_KIND)
                          .filter(tk -> hasChildOfRule(tk, RuleKind.INTERFACE_DECL))
                          .isPresent();
    }

    private Cursor getInterfaceDecl(Cursor typeDecl) {
        return childByRule(typeDecl, RuleKind.TYPE_KIND)
                          .filter(tk -> hasChildOfRule(tk, RuleKind.INTERFACE_DECL))
                          .or(typeDecl);
    }

    private boolean extendsCause(Cursor typeDecl) {
        // Check if interface extends Cause by examining the TypeKind text
        return childByRule(typeDecl, RuleKind.TYPE_KIND)
                          .filter(tk -> hasChildOfRule(tk, RuleKind.INTERFACE_DECL))
                          .map(iface -> text(iface))
                          .map(ifaceText -> EXTENDS_CAUSE_PATTERN.matcher(ifaceText).find())
                          .or(false);
    }

    private boolean isSealed(Cursor typeDecl) {
        // Modifiers are tokens in v6 (not CST nodes). Scan the TypeDecl header text:
        // 'sealed' must appear before the 'interface' keyword.
        var declText = text(typeDecl);
        return SEALED_HEADER_PATTERN.matcher(declText).find();
    }

    private String getInterfaceName(Cursor iface) {
        var ifaceText = text(iface);
        var matcher = INTERFACE_NAME_PATTERN.matcher(ifaceText);
        return matcher.find() ? matcher.group(1) : "(unknown)";
    }

    private Diagnostic createDiagnostic(Cursor iface, LintContext ctx) {
        var name = getInterfaceName(iface);
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(iface),
                                     startColumn(iface),
                                     "Error interface '" + name + "' extends Cause but is not sealed",
                                     "Sealed error interfaces enable exhaustive pattern matching in switch expressions. "
                                     + "When handling errors, the compiler can verify all cases are covered.")
                         .withExample("""
            // Before (unsealed)
            public interface LoginError extends Cause {
                record InvalidCredentials() implements LoginError { ... }
                record AccountLocked(UserId id) implements LoginError { ... }
            }

            // After (sealed)
            public sealed interface LoginError extends Cause {
                record InvalidCredentials() implements LoginError { ... }
                record AccountLocked(UserId id) implements LoginError { ... }
            }

            // Enables exhaustive matching:
            switch (cause) {
                case InvalidCredentials _ -> handleInvalid();
                case AccountLocked locked -> handleLocked(locked.id());
                // Compiler error if case is missing
            }
            """)
                         .withDocLink(DOC_LINK);
    }
}
