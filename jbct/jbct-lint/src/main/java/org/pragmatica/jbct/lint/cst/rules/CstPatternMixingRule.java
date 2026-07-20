package org.pragmatica.jbct.lint.cst.rules;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.shape.MethodShapeClassifier;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-PAT-02: No pattern mixing in chains.
///
/// Detects Fork-Join patterns (Result.all, Promise.all) nested inside
/// Sequencer patterns (flatMap chains). These should be restructured.
///
/// Thin delegator (#448): detection is the
/// [MethodShapeClassifier#forkJoinInSequencerLambdas] facet, which locates the `flatMap`/`andThen`
/// argument lambdas structurally via the lambda-argument descent and checks their masked bodies for
/// a nested (non-lone) Fork-Join call. This rule owns only the diagnostic.
public class CstPatternMixingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-PAT-02";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        // shouldLint gate added with the #448 absorption so PAT-02 honours excludePackages
        // consistently with its ZONE-03 / NEST-01 siblings (the pre-facet rule lacked it).
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        return MethodShapeClassifier.forkJoinInSequencerLambdas(root)
                                    .stream()
                                    .map(lambda -> createDiagnostic(lambda, ctx));
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
