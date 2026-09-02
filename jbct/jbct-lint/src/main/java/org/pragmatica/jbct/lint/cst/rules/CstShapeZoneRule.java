package org.pragmatica.jbct.lint.cst.rules;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.shape.MethodShapeClassifier;
import org.pragmatica.jbct.lint.cst.shape.MethodShapeClassifier.ShapeZoneMismatch;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-SHAPE-03: a method's composition shape disagrees with its name-verb's zone (mis-leveled, INFO).
///
/// Cross-checks the two orthogonal axes of a method: its composition [org.pragmatica.jbct.lint.cst.shape.MethodShape]
/// (from [MethodShapeClassifier#classify]) and the abstraction zone of its name verb. Two disagreements
/// are the book's "right altitude" signal and get flagged:
///   - a Zone-3 implementation verb (`get`/`fetch`/`parse`/`calculate`/…) heading a multi-step
///     SEQUENCER or FORK_JOIN — an implementation-named method that is actually doing orchestration
///     (mis-leveled up); and
///   - a Zone-2 orchestration verb (`validate`/`process`/`handle`/`load`/…) heading a LEAF — an
///     orchestration-named method that is a bare single operation (mis-leveled down).
///
/// MIXED / CONDITION / ITERATION / ASPECT / UNCLASSIFIED shapes are NOT cross-checked (no clear
/// altitude signal); a method whose leading verb is in neither zone table is skipped. Detection is the
/// [MethodShapeClassifier#shapeZoneMismatches] facet — this rule owns only the gate and the two
/// diagnostics.
///
/// **Severity: INFO — naming zone and composition shape are DIFFERENT axes, so agreement is a
/// heuristic, not a rule.** This has a real false-positive surface: a Zone-2 verb legitimately heads a
/// one-line LEAF delegate — most of all `apply` (the step-interface SAM) and `execute` (the use-case
/// SAM) forwarding to a single call, plus `validate`/`check` one-liners — so the mis-leveled-down arm
/// is expected to be high-volume. The mis-leveled-up arm can over-flag a Zone-3 getter that reads as a
/// two-combinator SEQUENCER (`return raw.map(f).filter(p)`). INFO never fails a build (precedent
/// JBCT-SIDE-01); the rule ships for corpus calibration and may end up default-disabled like
/// JBCT-SHAPE-02 once the false-positive rate is bounded. The Zone-2 verb table is copied from
/// `CstZoneThreeVerbsRule` (the sibling "orchestration verb on a leaf" set), NOT consolidated — see
/// [MethodShapeClassifier]'s `ZONE_TWO_VERBS` note.
public class CstShapeZoneRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-SHAPE-03";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        return MethodShapeClassifier.shapeZoneMismatches(root)
                                    .stream()
                                    .map(mismatch -> createDiagnostic(mismatch, ctx));
    }

    private Diagnostic createDiagnostic(ShapeZoneMismatch mismatch, LintContext ctx) {
        return mismatch.misLeveledUp()
               ? misLeveledUp(mismatch, ctx)
               : misLeveledDown(mismatch, ctx);
    }

    private Diagnostic misLeveledUp(ShapeZoneMismatch mismatch, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(mismatch.method()),
                                     startColumn(mismatch.method()),
                                     "Implementation-zone verb '" + mismatch.verb() + "' heads a " + mismatch.shape()
                                    + " orchestration",
                                     "A Zone 3 implementation verb names a multi-step " + mismatch.shape()
                                    + ". Rename to a Zone 2 orchestration verb (load/process/handle/…), or, if it is "
                                    + "genuinely atomic, reduce it to a single operation.");
    }

    private Diagnostic misLeveledDown(ShapeZoneMismatch mismatch, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(mismatch.method()),
                                     startColumn(mismatch.method()),
                                     "Orchestration-zone verb '" + mismatch.verb() + "' heads a bare LEAF",
                                     "A Zone 2 orchestration verb names a single-operation LEAF. Rename to a Zone 3 "
                                    + "implementation verb (get/parse/fetch/…), or, if it is genuinely orchestration, "
                                    + "compose the dependent steps.");
    }
}
