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
 * JBCT-ZONE-02: Leaf functions should use Zone 3 verbs.
 *
 * Zone 3 (implementation level) verbs are specific, concrete operations.
 * Private helper methods and leaf functions should use these verbs for
 * clear, implementation-focused naming.
 *
 * Zone 3 verbs: get, set, fetch, parse, calculate, convert, hash, format,
 *               encode, decode, extract, split, join, log, send, receive,
 *               read, write, add, remove
 */
public class CstZoneThreeVerbsRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-ZONE-02";

    // Zone 3 implementation-level verbs
    private static final Set<String>ZONE_3_VERBS = Set.of(
    "get",
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
    "delete",
    "create",
    "build");

    // Zone 2 orchestration-level verbs (too abstract for leaf functions)
    private static final Set<String>ZONE_2_VERBS = Set.of(
    "validate",
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
    "resolve",
    "verify");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        var packageName = findFirst(root, RuleId.PackageDecl.class)
                          .flatMap(pd -> findFirst(pd, RuleId.QualifiedName.class))
                          .map(qn -> text(qn, source))
                          .or("");
        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }
        // Find private methods that look like leaf functions
        return findAll(root, RuleId.MethodDecl.class)
               .stream()
               .filter(method -> isLeafFunction(method, root, source))
               .flatMap(method -> checkMethodName(method, source, ctx));
    }

    private boolean isLeafFunction(CstNode method, CstNode root, String source) {
        // Find the class member containing this method
        var classMember = findAncestor(root, method, RuleId.ClassMember.class);
        if (classMember.isEmpty()) {
            return false;
        }
        var memberText = text(classMember.getOrThrow("ClassMember expected"), source);
        // Check if private
        if (!memberText.contains("private ")) {
            return false;
        }
        // Check if it's a simple method (no monadic chains = leaf)
        var methodText = text(method, source);
        var hasMonadicChain = methodText.contains(".map(") ||
        methodText.contains(".flatMap(") ||
        methodText.contains(".fold(");
        // Leaf functions typically don't have monadic chains (they're at the bottom)
        return !hasMonadicChain;
    }

    private Stream<Diagnostic> checkMethodName(CstNode method, String source, LintContext ctx) {
        var methodName = childByRule(method, RuleId.Identifier.class)
                         .map(id -> text(id, source))
                         .or("");
        if (methodName.isEmpty()) {
            return Stream.empty();
        }
        // Extract the verb from method name
        var verb = extractVerb(methodName);
        if (verb != null && ZONE_2_VERBS.contains(verb.toLowerCase())) {
            var suggestedVerb = suggestZone3Verb(verb.toLowerCase());
            return Stream.of(createDiagnostic(method, methodName, verb, suggestedVerb, ctx));
        }
        return Stream.empty();
    }

    private String extractVerb(String methodName) {
        // Find the first word (verb) in camelCase name
        var sb = new StringBuilder();
        for (var c : methodName.toCharArray()) {
            if (Character.isUpperCase(c) && !sb.isEmpty()) {
                break;
            }
            sb.append(c);
        }
        return sb.isEmpty()
               ? null
               : sb.toString();
    }

    private String suggestZone3Verb(String zone2Verb) {
        return switch (zone2Verb) {
            case"load" -> "fetch/read/query";
            case"save" -> "write/insert/update";
            case"process", "transform" -> "parse/convert/calculate";
            case"handle" -> "send/receive";
            case"manage" -> "add/remove";
            case"validate", "verify", "check" -> "check";
            default -> "get/set/fetch";
        };
    }

    private Diagnostic createDiagnostic(CstNode node,
                                        String methodName,
                                        String verb,
                                        String suggestedVerb,
                                        LintContext ctx) {
        return Diagnostic.diagnostic(
        RULE_ID,
        ctx.severityFor(RULE_ID),
        ctx.fileName(),
        startLine(node),
        startColumn(node),
        "Leaf function '" + methodName + "' uses Zone 2 verb '" + verb + "'",
        "Leaf functions should use Zone 3 implementation verbs. " + "Consider using a more specific verb like: " + suggestedVerb
        + ".");
    }
}
