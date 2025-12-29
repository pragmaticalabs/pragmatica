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
 * JBCT-PAT-02: No pattern mixing in chains.
 *
 * Detects Fork-Join patterns (Result.all, Promise.all) nested inside
 * Sequencer patterns (flatMap chains). These should be restructured.
 */
public class CstPatternMixingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-PAT-02";

    private static final Set<String>FORK_JOIN_CALLS = Set.of(
    "Result.all(", "Promise.all(", "Option.all(", "Result.allOf(", "Promise.allOf(", "Option.allOf(");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        // Find all Lambda expressions
        return findAll(root, RuleId.Lambda.class)
               .stream()
               .filter(lambda -> isInsideFlatMap(lambda, root, source))
               .filter(lambda -> containsForkJoin(lambda, source))
               .map(lambda -> createDiagnostic(lambda, source, ctx));
    }

    private boolean isInsideFlatMap(CstNode lambda, CstNode root, String source) {
        // Check if this lambda is an argument to flatMap
        // We look at the text before the lambda to see if it contains .flatMap(
        var lambdaText = text(lambda, source);
        // Find the expression containing this lambda
        return findAncestor(root, lambda, RuleId.Expr.class)
               .map(expr -> text(expr, source))
               .filter(exprText -> {
                           var lambdaStart = exprText.indexOf(lambdaText);
                           if (lambdaStart > 0) {
                           var before = exprText.substring(0, lambdaStart);
                           return before.contains(".flatMap(") || before.contains(".andThen(");
                       }
                           return false;
                       })
               .isPresent();
    }

    private boolean containsForkJoin(CstNode lambda, String source) {
        var lambdaText = text(lambda, source);
        return FORK_JOIN_CALLS.stream()
                              .anyMatch(lambdaText::contains);
    }

    private Diagnostic createDiagnostic(CstNode node, String source, LintContext ctx) {
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
