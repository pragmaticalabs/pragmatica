package org.pragmatica.jbct.derive.model;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;

import static org.assertj.core.api.Assertions.assertThat;

/// The gate outcome: clean sheets exit 0, rejected sheets exit 1, and the human report reflects
/// both (SPEC.md §5 exit-code space; Phase A uses only 0 and 1).
class VerdictTest {
    @Test
    void verdict_isCleanAndExitsZero_whenNoFindings() {
        var verdict = new Verdict("sheet.toml", 3, List.of());

        assertThat(verdict.clean()).isTrue();
        assertThat(verdict.exitCode()).isEqualTo(0);
        assertThat(verdict.render()).contains("CLEAN");
    }

    @Test
    void verdict_isDirtyAndExitsOne_whenFindingsPresent() {
        var finding = Diagnostic.diagnostic("UNPRICED", DiagnosticSeverity.ERROR, "sheet.toml", 6, 0, "unpriced", "Q1 (Card 5)");
        var verdict = new Verdict("sheet.toml", 1, List.of(finding));

        assertThat(verdict.clean()).isFalse();
        assertThat(verdict.exitCode()).isEqualTo(1);
        assertThat(verdict.render()).contains("1 gate error");
    }
}
