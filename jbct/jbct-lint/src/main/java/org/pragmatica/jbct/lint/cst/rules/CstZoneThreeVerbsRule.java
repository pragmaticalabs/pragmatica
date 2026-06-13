package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;
import org.pragmatica.lang.Option;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-ZONE-02: Leaf functions should use Zone 3 verbs.
///
/// Zone 3 (implementation level) verbs are specific, concrete operations.
/// Private helper methods and leaf functions should use these verbs for
/// clear, implementation-focused naming.
///
/// Zone 3 verbs: get, set, fetch, parse, calculate, convert, hash, format,
///               encode, decode, extract, split, join, log, send, receive,
///               read, write, add, remove
public class CstZoneThreeVerbsRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-ZONE-02";

    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("\\b([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\(");

    // Zone 3 implementation-level verbs
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
                                                           "delete",
                                                           "create",
                                                           "build");

    // Zone 2 orchestration-level verbs (too abstract for leaf functions)
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
                                                           "resolve",
                                                           "verify");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }
        // Find private methods that look like leaf functions
        return findAllMethods(root).stream()
                      .filter(method -> isLeafFunction(method, root))
                      .flatMap(method -> checkMethodName(method, ctx));
    }

    private boolean isLeafFunction(Cursor method, Cursor root) {
        // Find the class member containing this method
        return findAncestor(root, method, RuleKind.CLASS_MEMBER)
                .filter(classMember -> isPrivateLeafMethod(classMember, method))
                .isPresent();
    }

    private boolean isPrivateLeafMethod(Cursor classMember, Cursor method) {
        var memberText = text(classMember);
        // Check if private
        if (!memberText.contains("private ")) {
            return false;
        }
        // Check if it's a simple method (no monadic chains = leaf)
        var methodText = text(method);
        var hasMonadicChain = methodText.contains(".map(") ||
        methodText.contains(".flatMap(") ||
        methodText.contains(".fold(");
        // Leaf functions typically don't have monadic chains (they're at the bottom)
        return ! hasMonadicChain;
    }

    private Stream<Diagnostic> checkMethodName(Cursor method, LintContext ctx) {
        var methodName = extractMethodName(text(method));
        if (methodName.isEmpty()) {
            return Stream.empty();
        }
        // Extract the verb from method name
        return extractVerb(methodName).filter(verb -> ZONE_2_VERBS.contains(verb.toLowerCase()))
                          .map(verb -> createDiagnostic(method,
                                                        methodName,
                                                        verb,
                                                        suggestZone3Verb(verb.toLowerCase()),
                                                        ctx))
                          .stream();
    }

    private static String extractMethodName(String memberText) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);
        return matcher.find() ? matcher.group(1) : "";
    }

    private Option<String> extractVerb(String methodName) {
        // Find the first word (verb) in camelCase name
        var sb = new StringBuilder();
        for (var c : methodName.toCharArray()) {
            if (Character.isUpperCase(c) && !sb.isEmpty()) {
                break;
            }
            sb.append(c);
        }
        return sb.isEmpty()
               ? Option.none()
               : Option.some(sb.toString());
    }

    private String suggestZone3Verb(String zone2Verb) {
        return switch (zone2Verb) {
            case "load" -> "fetch/read/query";
            case "save" -> "write/insert/update";
            case "process", "transform" -> "parse/convert/calculate";
            case "handle" -> "send/receive";
            case "manage" -> "add/remove";
            case "validate", "verify", "check" -> "check";
            default -> "get/set/fetch";
        };
    }

    private Diagnostic createDiagnostic(Cursor node,
                                        String methodName,
                                        String verb,
                                        String suggestedVerb,
                                        LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(node),
                                     startColumn(node),
                                     "Leaf function '" + methodName + "' uses Zone 2 verb '" + verb + "'",
                                     "Leaf functions should use Zone 3 implementation verbs. "
                                     + "Consider using a more specific verb like: " + suggestedVerb + ".");
    }
}
