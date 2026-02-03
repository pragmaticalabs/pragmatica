package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.Set;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-ZONE-01: Step interfaces should use Zone 2 verbs.
 *
 * Zone 2 (orchestration level) verbs are high-level coordination operations.
 * Step interfaces in Sequencer and Fork-Join patterns should use these verbs
 * for consistent abstraction levels.
 *
 * Zone 2 verbs: validate, process, handle, transform, apply, check, load, save,
 *               manage, configure, initialize, execute, prepare, complete
 */
public class CstZoneTwoVerbsRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-ZONE-01";

    // Zone 2 orchestration-level verbs
    private static final Set<String> ZONE_2_VERBS = Set.of("validate",
                                                           "process",
                                                           "handle",
                                                           "transform",
                                                           "apply",
                                                           "check",
                                                           "load",
                                                           "save",
                                                           "manage",
                                                           "configure",
                                                           "initialize",
                                                           "execute",
                                                           "prepare",
                                                           "complete",
                                                           "create",
                                                           "build",
                                                           "resolve",
                                                           "verify");

    // Zone 3 implementation-level verbs (should NOT be in step interfaces)
    private static final Set<String> ZONE_3_VERBS = Set.of("get",
                                                           "set",
                                                           "fetch",
                                                           "parse",
                                                           "calculate",
                                                           "convert",
                                                           "hash",
                                                           "format",
                                                           "encode",
                                                           "decode",
                                                           "extract",
                                                           "split",
                                                           "join",
                                                           "log",
                                                           "send",
                                                           "receive",
                                                           "read",
                                                           "write",
                                                           "add",
                                                           "remove",
                                                           "find",
                                                           "query",
                                                           "insert",
                                                           "update",
                                                           "delete");

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
        // Find functional interfaces that look like step interfaces
        return findAll(root, RuleId.InterfaceDecl.class).stream()
                      .filter(iface -> isStepInterface(iface, source))
                      .flatMap(iface -> checkInterfaceName(iface, source, ctx));
    }

    private boolean isStepInterface(CstNode iface, String source) {
        var ifaceText = text(iface, source);
        // Check if it's a functional interface (single method)
        var methodCount = findAll(iface, RuleId.MethodDecl.class).size();
        if (methodCount != 1) {
            return false;
        }
        // Check if method returns Result/Promise/Option (monadic type)
        return ifaceText.contains("Result<") ||
        ifaceText.contains("Promise<") ||
        ifaceText.contains("Option<");
    }

    private Stream<Diagnostic> checkInterfaceName(CstNode iface, String source, LintContext ctx) {
        var interfaceName = childByRule(iface, RuleId.Identifier.class).map(id -> text(id, source))
                                       .or("");
        if (interfaceName.isEmpty()) {
            return Stream.empty();
        }
        // Extract the verb from interface name (e.g., "FetchUserData" -> "fetch")
        var verb = extractVerb(interfaceName);
        if (verb != null && ZONE_3_VERBS.contains(verb.toLowerCase())) {
            var suggestedVerb = suggestZone2Verb(verb.toLowerCase());
            return Stream.of(createDiagnostic(iface, interfaceName, verb, suggestedVerb, ctx));
        }
        return Stream.empty();
    }

    private String extractVerb(String interfaceName) {
        // Find the first word (verb) in CamelCase name
        var sb = new StringBuilder();
        for (var c : interfaceName.toCharArray()) {
            if (Character.isUpperCase(c) && !sb.isEmpty()) {
                break;
            }
            sb.append(c);
        }
        return sb.isEmpty()
               ? null
               : sb.toString();
    }

    private String suggestZone2Verb(String zone3Verb) {
        return switch (zone3Verb) {
            case "get", "fetch", "query", "find", "read" -> "Load";
            case "set", "write", "insert", "update" -> "Save";
            case "parse", "decode", "extract" -> "Process";
            case "format", "encode" -> "Transform";
            case "calculate", "convert" -> "Process";
            case "hash" -> "Process";
            case "send", "receive" -> "Handle";
            case "add", "remove", "delete" -> "Manage";
            default -> "Process";
        };
    }

    private Diagnostic createDiagnostic(CstNode node,
                                        String interfaceName,
                                        String verb,
                                        String suggestedVerb,
                                        LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(node),
                                     startColumn(node),
                                     "Step interface '" + interfaceName + "' uses Zone 3 verb '" + verb + "'",
                                     "Step interfaces should use Zone 2 orchestration verbs. "
                                     + "Consider renaming to '" + suggestedVerb + interfaceName.substring(verb.length())
                                     + "'.");
    }
}
