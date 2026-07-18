package org.pragmatica.jbct.lint.cst.rules;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-PAT-02: No pattern mixing in chains.
///
/// Detects Fork-Join patterns (Result.all, Promise.all) nested inside
/// Sequencer patterns (flatMap chains). These should be restructured.
public class CstPatternMixingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-PAT-02";

    private static final Set<String> FORK_JOIN_CALLS = Set.of("Result.all(",
                                                              "Promise.all(",
                                                              "Option.all(",
                                                              "Result.allOf(",
                                                              "Promise.allOf(",
                                                              "Option.allOf(");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        // Find all Lambda expressions (not method references - those are just transformations)
        return findAllLambdas(root).stream()
                             .filter(lambda -> isInsideFlatMap(lambda, root))
                             .filter(this::containsForkJoinWithLogic)
                             .map(lambda -> createDiagnostic(lambda, ctx));
    }

    private boolean isInsideFlatMap(Cursor lambda, Cursor root) {
        // This lambda is a Sequencer step if some enclosing expression places a `.flatMap(` /
        // `.andThen(` immediately before it. The nearest EXPR ancestor is the lambda's own
        // argument-expression (its text IS the lambda), so we scan outward until an ancestor's
        // text reveals the enclosing call.
        var lambdaText = text(lambda);

        return findAncestorPath(root, lambda).map(path -> precededBySequencerCall(path, lambdaText))
                           .or(false);
    }

    private boolean precededBySequencerCall(List<Cursor> path, String lambdaText) {
        for (int i = path.size() - 2; i >= 0; i--) {
            var nodeText = text(path.get(i));
            var lambdaStart = nodeText.indexOf(lambdaText);

            if (lambdaStart <= 0) {
                continue;
            }

            var before = nodeText.substring(0, lambdaStart)
                                 .stripTrailing();

            if (before.endsWith(".flatMap(") || before.endsWith(".andThen(")) {
                return true;
            }
        }

        return false;
    }

    private boolean containsForkJoinWithLogic(Cursor lambda) {
        var lambdaText = text(lambda).trim();
        // Skip if lambda body is just a single fork-join call (transformation step, not nested pattern)
        // e.g., "results -> Result.allOf(results)" is fine
        if (isSingleForkJoinCall(lambdaText)) {
            return false;
        }

        return FORK_JOIN_CALLS.stream().anyMatch(lambdaText::contains);
    }

    private boolean isSingleForkJoinCall(String lambdaText) {
        // Check if lambda is just "param -> Result.allOf(param)" or similar
        var arrowIdx = lambdaText.indexOf("->");

        if (arrowIdx < 0) {
            return false;
        }

        var body = lambdaText.substring(arrowIdx + 2)
                             .trim();
        // A lone fork-join call (`param -> Result.allOf(param)`) is a transformation step, not
        // pattern mixing. A fork-join that heads a further chain (`Result.all(...).flatMap(...)`)
        // IS mixing and must fall through to be flagged.
        return FORK_JOIN_CALLS.stream()
                              .anyMatch(call -> bodyIsLoneCall(body, call));
    }

    /// True when `body` is exactly one call to `call` (which includes its trailing `(`) with
    /// nothing chained after the matching `)`. Balanced-paren scan so argument-internal calls and
    /// close-parens don't false-trigger.
    private boolean bodyIsLoneCall(String body, String call) {
        if (!body.startsWith(call) || body.contains(";")) {
            return false;
        }

        var depth = 0;

        for (var i = call.length() - 1; i < body.length(); i++) {
            var c = body.charAt(i);

            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;

                if (depth == 0) {
                    return i == body.length() - 1;
                }
            }
        }

        return false;
    }

    private Diagnostic createDiagnostic(Cursor node, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(node),
                                     startColumn(node),
                                     "Fork-Join pattern nested inside Sequencer chain",
                                     "Mixing Result.all() (Fork-Join) inside flatMap() (Sequencer) creates confusing control flow. "
                                    + "Restructure to use Fork-Join at the same level, or extract to a separate method.")
                         .withExample("""
            // Before (mixed patterns)
            return validateEmail(request)
                .flatMap(email -> Result.all(
                    checkDuplicate(email),
                    validatePassword(request))
                    .flatMap(valid -> saveUser(email, valid.second()))
                );

            // After (separated patterns)
            return Result.all(
                    Email.email(request.email()),
                    Password.password(request.password()))
                .flatMap(ValidRequest::validRequest)
                .flatMap(this::checkDuplicate)
                .flatMap(this::saveUser);
            """);
    }
}
