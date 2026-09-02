package org.pragmatica.jbct.lint.cst.rules;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.shape.MethodShape;
import org.pragmatica.jbct.lint.cst.shape.MethodShapeClassifier;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-SHAPE-01: a method blends two JBCT patterns at one altitude (MIXED, census, INFO).
///
/// The census surface for [MethodShape#MIXED] from [MethodShapeClassifier]: the returned expression
/// combines two pattern features (e.g. a fork-join head and a stream pipeline) at the same altitude,
/// so no single one of the book's six patterns fits and the method should be decomposed.
///
/// **Severity: INFO — phase-1 census, promote after corpus calibration.** The classifier has no type
/// resolution, so the shape is inferred from syntax alone (see [MethodShapeClassifier]'s documented
/// misclassification surface). INFO never fails a build (precedent JBCT-SIDE-01); phase 2 promotes
/// MIXED/UNCLASSIFIED to WARNING once the corpus run bounds the false-positive rate, and folds the
/// string-heuristic shadows (JBCT-PAT-02 / JBCT-ZONE-03 / JBCT-NEST-01) into the classifier.
public class CstShapeMixedRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-SHAPE-01";

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
                                    .filter(verdict -> verdict.shape() == MethodShape.MIXED)
                                    .map(verdict -> createDiagnostic(method, verdict.reason(), ctx))
                                    .stream();
    }

    private Diagnostic createDiagnostic(Cursor method, String reason, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(anchorOf(method)),
                                     startColumn(anchorOf(method)),
                                     "Method blends two JBCT patterns at one altitude — " + reason,
                                     "The book's single-pattern-per-function rule wants one of Leaf/Sequencer/Fork-Join/"
                                    + "Condition/Iteration/Aspect per method. Extract the second pattern into its own "
                                    + "method and call it as a step.");
    }
}
