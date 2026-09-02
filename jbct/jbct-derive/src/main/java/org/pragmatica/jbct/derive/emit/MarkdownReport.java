package org.pragmatica.jbct.derive.emit;

import java.util.List;

import org.pragmatica.jbct.derive.result.Combination;
import org.pragmatica.jbct.derive.result.DecisionRecord;
import org.pragmatica.jbct.derive.result.DeriveResult;
import org.pragmatica.jbct.derive.result.Halt;
import org.pragmatica.jbct.derive.result.JudgmentPoint;
import org.pragmatica.jbct.derive.result.Pressure;
import org.pragmatica.jbct.derive.result.RecoveryAssignment;
import org.pragmatica.jbct.derive.result.Strike;
import org.pragmatica.jbct.derive.result.VectorPosition;
import org.pragmatica.jbct.derive.result.Verification;
import org.pragmatica.jbct.lint.Diagnostic;

/// Human output for `derive` (SPEC.md §4 emit): a markdown report — the derived vector with each
/// position's citing answers (F10), the pressure matrix (inert rows included), decision records,
/// recovery assignments, the verification lines, halts, and the emitted judgment points.
///
/// The report is explicit about the engine's boundary: `DEFERRED` positions and every judgment
/// point are the derivation stopping where the book stops, by construction (SPEC.md §1) — not gaps
/// to be filled by the tool.
public sealed interface MarkdownReport permits MarkdownReport.unused {
    record unused() implements MarkdownReport {}

    /// Render a derive result as markdown.
    static String render(DeriveResult result) {
        return result.gatePassed()
               ? full(result)
               : gated(result);
    }

    private static String gated(DeriveResult result) {
        return String.join("\n", List.of(header(result),
                                          section("Entry gate — sheet rejected (fake-answer halt)",
                                                  result.gateFindings().stream().map(MarkdownReport::findingLine).toList())));
    }

    private static String full(DeriveResult result) {
        return String.join("\n", List.of(header(result),
                                         section("Derived vector", result.vector().stream().map(MarkdownReport::vectorLine).toList()),
                                         section("Pressure matrix (inert rows included)",
                                                 result.pressures().stream().map(MarkdownReport::pressureLine).toList()),
                                         section("Mandate strikes (prune)", result.strikes().stream().map(MarkdownReport::strikeLine).toList()),
                                         section("Combination checks", result.combinations().stream().map(MarkdownReport::combinationLine).toList()),
                                         section("Decision records", result.decisions().stream().map(MarkdownReport::decisionLine).toList()),
                                         section("Recovery assignments", result.recovery().stream().map(MarkdownReport::recoveryLine).toList()),
                                         section("Verification (exit gate)", result.verifications().stream().map(MarkdownReport::verificationLine).toList()),
                                         section("Halts", result.halts().stream().map(MarkdownReport::haltLine).toList()),
                                         section("Judgment points (emitted, never resolved)",
                                                 result.judgmentPoints().stream().map(MarkdownReport::judgmentLine).toList()),
                                         footnote()));
    }

    private static String header(DeriveResult result) {
        return "# Derivation — " + result.meta().system() + " (" + result.meta().era() + ")\n"
             + "Source: " + result.source() + " · mode: " + kebab(result.meta().mode().name())
             + " · exit " + result.exitCode() + "\n";
    }

    private static String vectorLine(VectorPosition position) {
        return "- **" + position.axis().label() + "**: " + position.value()
             + "  _[" + kebab(position.resolution().name()) + "]_" + citing(position.citing());
    }

    private static String pressureLine(Pressure pressure) {
        return "- " + pressure.axis().label() + " | " + kebab(pressure.mode().name()) + " | "
             + pressure.direction() + " | " + pressure.mechanism() + " | " + pressure.citations();
    }

    private static String strikeLine(Strike strike) {
        return "- " + strike.display() + " struck by " + strike.struckBy().cite();
    }

    private static String combinationLine(Combination combination) {
        return "- " + combination.axis().label() + ": " + combination.note();
    }

    private static String decisionLine(DecisionRecord decision) {
        return "- " + decision.axis().label() + ": **" + decision.position() + "** — forced by "
             + decision.forcedBy() + "; via " + decision.via() + "; costs " + decision.costs()
             + "; revisit when " + decision.revisitWhen();
    }

    private static String recoveryLine(RecoveryAssignment assignment) {
        return "- " + assignment.operation() + ": " + kebab(assignment.recoveryClass().name()) + " — " + assignment.rationale();
    }

    private static String verificationLine(Verification verification) {
        return "- " + verification.rule() + " @ " + verification.scope() + ": " + verification.status()
             + " — " + verification.detail();
    }

    private static String haltLine(Halt halt) {
        return "- " + kebab(halt.kind().name()) + ": " + halt.detail() + menu(halt.renegotiationMenu());
    }

    private static String menu(List<String> branches) {
        return branches.isEmpty()
               ? ""
               : "\n  - renegotiation menu: " + String.join("\n  - ", branches);
    }

    private static String judgmentLine(JudgmentPoint judgment) {
        return "- " + kebab(judgment.kind().name()) + " [" + judgment.subject() + "]: " + judgment.detail();
    }

    private static String findingLine(Diagnostic finding) {
        return "- " + finding.ruleId() + " (line " + finding.line() + "): " + finding.message() + " — " + finding.details();
    }

    private static String footnote() {
        return "---\n_DEFERRED positions and judgment points are the derivation stopping where the book stops "
             + "(SPEC.md §1): the engine is the entry gate and the bookkeeping, not the oracle._\n";
    }

    private static String section(String title, List<String> lines) {
        return "## " + title + "\n" + (lines.isEmpty()
                                       ? "_none_\n"
                                       : String.join("\n", lines) + "\n");
    }

    private static String citing(List<String> cites) {
        return cites.isEmpty()
               ? ""
               : " <- " + String.join(", ", cites);
    }

    private static String kebab(String enumName) {
        return enumName.toLowerCase().replace('_', '-');
    }
}
