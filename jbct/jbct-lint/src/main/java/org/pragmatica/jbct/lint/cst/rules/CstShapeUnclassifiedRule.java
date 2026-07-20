package org.pragmatica.jbct.lint.cst.rules;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.shape.MethodShape;
import org.pragmatica.jbct.lint.cst.shape.MethodShapeClassifier;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-SHAPE-02: a method has no single JBCT pattern (UNCLASSIFIED, census, INFO).
///
/// The census surface for [MethodShape#UNCLASSIFIED] from [MethodShapeClassifier]: imperative residue
/// — a multi-statement body, a loop / `if` / `try` / `throw` statement, or an expression with no
/// recognisable composition root. Such a method has no single one of the book's six patterns and is
/// the primary target of the < 5% corpus gate.
///
/// **DEFAULT-DISABLED.** The phase-1 aether corpus census returned thousands of UNCLASSIFIED methods
/// — at INFO this rule would add that many lines to every `jbct:check`. The phase-2 reach (#448) cuts
/// the residual substantially (a pure local-var-then-return body now reads by its tail, not
/// UNCLASSIFIED), but a large genuinely-imperative remainder is a corpus fact, so the rule stays
/// disabled in [org.pragmatica.jbct.lint.LintConfig]'s default `disabledRules` and is run on demand
/// (enable it in config for a shape census); the sibling [CstShapeMixedRule] (JBCT-SHAPE-01) stays
/// enabled because it is corpus-zero and silent.
///
/// **Severity: INFO — phase-1 census, promote after corpus calibration.** Syntax-only classification
/// deliberately marks anything it cannot reduce to a single composition root rather than guessing.
/// After the phase-2 reach a body whose leading statements are all skippable preamble (pure locals,
/// narrow guards, a single logger call) classifies by its tail; only a genuinely imperative body (a
/// side effect, a reassignment, a mutating-initializer local, a lifted try) reads UNCLASSIFIED
/// (accepted — the calibration signal, not a silent verdict). INFO never fails a build (precedent
/// JBCT-SIDE-01). See [MethodShapeClassifier] for the full misclassification surface.
public class CstShapeUnclassifiedRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-SHAPE-02";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        return findAllMethods(root).stream()
                                   .flatMap(method -> diagnose(method, ctx));
    }

    private Stream<Diagnostic> diagnose(Cursor method, LintContext ctx) {
        return MethodShapeClassifier.classify(method)
                                    .filter(verdict -> verdict.shape() == MethodShape.UNCLASSIFIED)
                                    .map(verdict -> createDiagnostic(method, verdict.reason(), ctx))
                                    .stream();
    }

    private Diagnostic createDiagnostic(Cursor method, String reason, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Method has no single JBCT pattern — " + reason,
                                     "The book's single-pattern-per-function rule wants each method to reduce to one "
                                    + "composition root. Restructure the imperative residue into a Leaf/Sequencer/"
                                    + "Condition/Iteration chain, or extract the steps.");
    }
}
