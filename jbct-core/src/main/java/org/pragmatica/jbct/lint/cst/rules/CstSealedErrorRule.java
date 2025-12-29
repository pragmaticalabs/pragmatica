package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-SEAL-01: Error interfaces should be sealed.
 *
 * Detects interfaces that extend Cause but are not declared as sealed.
 * Sealed error interfaces enable exhaustive pattern matching.
 */
public class CstSealedErrorRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-SEAL-01";
    private static final String DOC_LINK = "https://github.com/siy/coding-technology/blob/main/series/part-04-error-handling.md";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        return findAll(root, RuleId.InterfaceDecl.class)
               .stream()
               .filter(iface -> extendsCause(iface, source))
               .filter(iface -> !isSealed(iface, source))
               .map(iface -> createDiagnostic(iface, source, ctx));
    }

    private boolean extendsCause(CstNode iface, String source) {
        // Check if interface extends Cause by examining the text
        var ifaceText = text(iface, source);
        return ifaceText.contains("extends Cause") ||
        ifaceText.contains("extends ") && ifaceText.contains("Cause");
    }

    private boolean isSealed(CstNode iface, String source) {
        // Check for 'sealed' modifier in interface declaration
        var ifaceText = text(iface, source);
        // Look for 'sealed' keyword before 'interface'
        var interfaceIdx = ifaceText.indexOf("interface");
        if (interfaceIdx > 0) {
            var beforeInterface = ifaceText.substring(0, interfaceIdx);
            return beforeInterface.contains("sealed");
        }
        return false;
    }

    private String getInterfaceName(CstNode iface, String source) {
        return childByRule(iface, RuleId.Identifier.class)
               .map(id -> text(id, source)
                          .trim())
               .or("(unknown)");
    }

    private Diagnostic createDiagnostic(CstNode iface, String source, LintContext ctx) {
        var name = getInterfaceName(iface, source);
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
