package org.pragmatica.jbct.derive.model;

import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;

/// The outcome of running the entry gate over a sheet: the findings plus the number of rows
/// checked. This is the Phase-A result object and the documented seam for machine output — a
/// future JSON emitter (Phase B, once the full derive result exists) serializes a superset of it.
///
/// Phase A exposes only human rendering; see [#render()].
public record Verdict(String source, int rowsChecked, List<Diagnostic> findings) {
    public Verdict {
        findings = List.copyOf(findings);
    }

    /// Whether the sheet cleared the gate (no findings).
    public boolean clean() {
        return findings.isEmpty();
    }

    /// The CLI exit code for `check-sheet`: 0 when clean, 1 when the gate rejects the sheet.
    /// (Exit codes 2/3 — halts and pending judgment points — are Phase-B derive outcomes.)
    public int exitCode() {
        return clean()
               ? 0
               : 1;
    }

    /// Render a human-readable gate report.
    public String render() {
        var builder = new StringBuilder();

        builder.append("Sheet: ").append(source).append("\n");
        for (var finding : findings) {
            builder.append(finding.toHumanReadable());
        }

        builder.append(summaryLine());

        return builder.toString();
    }

    private String summaryLine() {
        return clean()
               ? "Entry gate: CLEAN — " + rowsChecked + " row(s) checked, 0 gate error(s)."
               : "Entry gate: " + findings.size() + " gate error(s) across " + rowsChecked + " row(s) checked.";
    }
}
