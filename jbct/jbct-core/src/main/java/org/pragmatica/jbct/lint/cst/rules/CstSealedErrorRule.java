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
        // Find TypeDecl nodes that contain InterfaceDecl
        return findAll(root, RuleId.TypeDecl.class).stream()
                      .filter(typeDecl -> hasInterfaceDecl(typeDecl))
                      .filter(typeDecl -> extendsCause(typeDecl, source))
                      .filter(typeDecl -> !isSealed(typeDecl, source))
                      .map(typeDecl -> createDiagnostic(getInterfaceDecl(typeDecl),
                                                        source,
                                                        ctx));
    }

    private boolean hasInterfaceDecl(CstNode typeDecl) {
        return childByRule(typeDecl, RuleId.InterfaceDecl.class).isPresent();
    }

    private CstNode getInterfaceDecl(CstNode typeDecl) {
        return childByRule(typeDecl, RuleId.InterfaceDecl.class).or(typeDecl);
    }

    private boolean extendsCause(CstNode typeDecl, String source) {
        // Check if interface extends Cause by examining the InterfaceDecl text
        return childByRule(typeDecl, RuleId.InterfaceDecl.class).map(iface -> text(iface, source))
                          .map(ifaceText -> ifaceText.contains("extends Cause") || ifaceText.contains("extends ") && ifaceText.contains("Cause"))
                          .or(false);
    }

    private boolean isSealed(CstNode typeDecl, String source) {
        // Check for 'sealed' modifier in Modifier children of TypeDecl
        return childrenByRule(typeDecl, RuleId.Modifier.class).stream()
                             .anyMatch(mod -> text(mod, source).trim()
                                                  .equals("sealed"));
    }

    private String getInterfaceName(CstNode iface, String source) {
        return childByRule(iface, RuleId.Identifier.class).map(id -> text(id, source).trim())
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
