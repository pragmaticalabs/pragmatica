package org.pragmatica.jbct.derive.result;

import java.util.List;

import org.pragmatica.jbct.derive.model.Meta;
import org.pragmatica.jbct.lint.Diagnostic;

/// The full result of `derive` (SPEC.md §4 emit): everything computed from a sheet, from one object
/// that both the markdown and JSON emitters render. A sheet that fails the entry gate yields a
/// gate-only result (the fake-answer halt); a clean sheet yields the full pipeline output.
///
/// The four exit codes (SPEC.md §5) fall straight out of this object: gate errors win over halts,
/// halts over pending judgment points, and a fully clean run exits 0.
public record DeriveResult(String source,
                           Meta meta,
                           List<Diagnostic> gateFindings,
                           List<Strike> strikes,
                           List<Pressure> pressures,
                           List<Combination> combinations,
                           List<DecisionRecord> decisions,
                           List<VectorPosition> vector,
                           List<RecoveryAssignment> recovery,
                           List<Verification> verifications,
                           List<Halt> halts,
                           List<JudgmentPoint> judgmentPoints) {
    public DeriveResult {
        gateFindings = List.copyOf(gateFindings);
        strikes = List.copyOf(strikes);
        pressures = List.copyOf(pressures);
        combinations = List.copyOf(combinations);
        decisions = List.copyOf(decisions);
        vector = List.copyOf(vector);
        recovery = List.copyOf(recovery);
        verifications = List.copyOf(verifications);
        halts = List.copyOf(halts);
        judgmentPoints = List.copyOf(judgmentPoints);
    }

    /// A gate-only result: the sheet failed the entry gate, so the pipeline never ran.
    public static DeriveResult gated(String source, Meta meta, List<Diagnostic> gateFindings) {
        return new DeriveResult(source,
                                meta,
                                gateFindings,
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of());
    }

    /// The CLI exit code (SPEC.md §5): 0 clean · 1 gate errors · 2 halts/contradictions · 3
    /// judgment points pending.
    public int exitCode() {
        if (!gateFindings.isEmpty()) {
            return 1;
        }
        if (!halts.isEmpty()) {
            return 2;
        }

        return judgmentPoints.isEmpty()
               ? 0
               : 3;
    }

    /// Whether the sheet cleared the entry gate (the pipeline ran).
    public boolean gatePassed() {
        return gateFindings.isEmpty();
    }
}
