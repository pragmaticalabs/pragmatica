package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.lang.Option;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-ZONE-03: No zone mixing in sequencer chains.
///
/// Sequencer chains (flatMap/map sequences) should maintain consistent
/// abstraction at Zone 2 level. Zone 3 operations should be wrapped
/// in Zone 2 step interfaces, not called directly in chains.
public class CstZoneMixingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-ZONE-03";

    // Zone 3 verbs that shouldn't appear directly in chains
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

    // Pattern to find method calls in chains: .flatMap(x -> something.verb(...))
    private static final Pattern CHAIN_CALL_PATTERN = Pattern.compile("\\.(map|flatMap)\\s*\\([^)]*->\\s*[^)]*\\.([a-z][a-zA-Z]*)\\s*\\(");

    // Pattern for method reference in chains: .flatMap(Something::verb)
    private static final Pattern METHOD_REF_PATTERN = Pattern.compile("\\.(map|flatMap)\\s*\\([^:]*::([a-z][a-zA-Z]*)\\s*\\)");

    // Pattern for direct call in chains: .flatMap(this::verb) or .map(obj.verb())
    private static final Pattern DIRECT_CALL_PATTERN = Pattern.compile("\\.(map|flatMap)\\s*\\([^)]*([a-z][a-zA-Z]*)\\s*\\(");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }
        // Find methods with monadic chains
        return findAllMethods(root).stream()
                             .filter(this::hasMonadicChain)
                             .flatMap(method -> checkChainForZoneMixing(method, ctx));
    }

    private boolean hasMonadicChain(Cursor method) {
        var methodText = text(method);

        return methodText.contains(".flatMap(") || methodText.contains(".map(");
    }

    private Stream<Diagnostic> checkChainForZoneMixing(Cursor method, LintContext ctx) {
        var methodText = text(method);
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

    private void findZone3VerbsInPattern(String text, Pattern pattern, int verbGroup, List<String> violations) {
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            extractVerb(matcher.group(verbGroup)).filter(verb -> ZONE_3_VERBS.contains(verb.toLowerCase()))
                       .filter(verb -> !violations.contains(verb))
                       .onPresent(violations::add);
        }
    }

    private Option<String> extractVerb(String methodName) {
        return Option.option(methodName)
                     .filter(name -> !name.isEmpty())
                     .flatMap(this::extractFirstWord);
    }

    private Option<String> extractFirstWord(String name) {
        // Extract first word from camelCase
        var sb = new StringBuilder();

        for (var c : name.toCharArray()) {
            if (Character.isUpperCase(c) && !sb.isEmpty()) {
                break;
            }

            sb.append(c);
        }

        return sb.isEmpty()
               ? Option.none()
               : Option.some(sb.toString());
    }

    private Diagnostic createDiagnostic(Cursor node, List<String> violations, LintContext ctx) {
        var verbList = String.join(", ", violations);

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(node),
                                     startColumn(node),
                                     "Zone mixing in chain - Zone 3 verbs found: " + verbList,
                                     "Sequencer chains should use Zone 2 methods. "
                                    + "Wrap Zone 3 operations ('" + verbList
                                    + "') in step interfaces. "
                                    + "Example: Instead of .flatMap(x -> x.parseData()), "
                                    + "use .flatMap(processData::apply) where ProcessData is a step interface.");
    }
}
