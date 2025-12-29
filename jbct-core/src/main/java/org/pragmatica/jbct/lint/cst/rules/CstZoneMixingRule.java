package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-ZONE-03: No zone mixing in sequencer chains.
 *
 * Sequencer chains (flatMap/map sequences) should maintain consistent
 * abstraction at Zone 2 level. Zone 3 operations should be wrapped
 * in Zone 2 step interfaces, not called directly in chains.
 */
public class CstZoneMixingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-ZONE-03";

    // Zone 3 verbs that shouldn't appear directly in chains
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
    "delete");

    // Pattern to find method calls in chains: .flatMap(x -> something.verb(...))
    private static final Pattern CHAIN_CALL_PATTERN = Pattern.compile(
    "\\.(map|flatMap)\\s*\\([^)]*->\\s*[^)]*\\.([a-z][a-zA-Z]*)\\s*\\(");

    // Pattern for method reference in chains: .flatMap(Something::verb)
    private static final Pattern METHOD_REF_PATTERN = Pattern.compile(
    "\\.(map|flatMap)\\s*\\([^:]*::([a-z][a-zA-Z]*)\\s*\\)");

    // Pattern for direct call in chains: .flatMap(this::verb) or .map(obj.verb())
    private static final Pattern DIRECT_CALL_PATTERN = Pattern.compile(
    "\\.(map|flatMap)\\s*\\([^)]*([a-z][a-zA-Z]*)\\s*\\(");

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
        // Find methods with monadic chains
        return findAll(root, RuleId.MethodDecl.class)
               .stream()
               .filter(method -> hasMonadicChain(method, source))
               .flatMap(method -> checkChainForZoneMixing(method, source, ctx));
    }

    private boolean hasMonadicChain(CstNode method, String source) {
        var methodText = text(method, source);
        return methodText.contains(".flatMap(") || methodText.contains(".map(");
    }

    private Stream<Diagnostic> checkChainForZoneMixing(CstNode method,
                                                       String source,
                                                       LintContext ctx) {
        var methodText = text(method, source);
        var violations = new ArrayList<String>();
        // Check lambda calls in chains
        findZone3VerbsInPattern(methodText, CHAIN_CALL_PATTERN, 2, violations);
        // Check method references in chains
        findZone3VerbsInPattern(methodText, METHOD_REF_PATTERN, 2, violations);
        if (violations.isEmpty()) {
            return Stream.empty();
        }
        // Return one diagnostic per method with all violations
        return Stream.of(createDiagnostic(method, violations, ctx));
    }

    private void findZone3VerbsInPattern(String text,
                                         Pattern pattern,
                                         int verbGroup,
                                         List<String> violations) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            var verb = extractVerb(matcher.group(verbGroup));
            if (verb != null && ZONE_3_VERBS.contains(verb.toLowerCase())) {
                if (!violations.contains(verb)) {
                    violations.add(verb);
                }
            }
        }
    }

    private String extractVerb(String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            return null;
        }
        // Extract first word from camelCase
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

    private Diagnostic createDiagnostic(CstNode node, List<String> violations, LintContext ctx) {
        var verbList = String.join(", ", violations);
        return Diagnostic.diagnostic(
        RULE_ID,
        ctx.severityFor(RULE_ID),
        ctx.fileName(),
        startLine(node),
        startColumn(node),
        "Zone mixing in chain - Zone 3 verbs found: " + verbList,
        "Sequencer chains should use Zone 2 methods. " + "Wrap Zone 3 operations ('" + verbList
        + "') in step interfaces. " + "Example: Instead of .flatMap(x -> x.parseData()), "
        + "use .flatMap(processData::apply) where ProcessData is a step interface.");
    }
}
