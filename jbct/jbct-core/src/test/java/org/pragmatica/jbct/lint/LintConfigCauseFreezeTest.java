package org.pragmatica.jbct.lint;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the frozen severities of the typed-error (CAUSE) lint pack — ruling #713, 2026-08-28,
/// versioned against JBCT 5.0.0. A change to any of these entries is a change to the freeze and
/// needs its own ruling, not a drive-by edit.
class LintConfigCauseFreezeTest {
    @Test
    void defaultConfig_holdsFrozenErrorSet_forCause010204() {
        var severities = LintConfig.DEFAULT.ruleSeverities();

        assertThat(severities.get("JBCT-CAUSE-01")).isEqualTo(DiagnosticSeverity.ERROR);
        assertThat(severities.get("JBCT-CAUSE-02")).isEqualTo(DiagnosticSeverity.ERROR);
        assertThat(severities.get("JBCT-CAUSE-04")).isEqualTo(DiagnosticSeverity.ERROR);
    }

    @Test
    void defaultConfig_keepsRemainingImplementedCauseRules_atWarning() {
        var severities = LintConfig.DEFAULT.ruleSeverities();

        assertThat(severities.get("JBCT-CAUSE-03")).isEqualTo(DiagnosticSeverity.WARNING);
        assertThat(severities.get("JBCT-CAUSE-05")).isEqualTo(DiagnosticSeverity.WARNING);
        assertThat(severities.get("JBCT-CAUSE-07")).isEqualTo(DiagnosticSeverity.WARNING);
        assertThat(severities.get("JBCT-CAUSE-08")).isEqualTo(DiagnosticSeverity.WARNING);
    }

    @Test
    void defaultConfig_carriesNoEntry_forUnimplementedCause06() {
        // CAUSE-06 is outside the freeze (unimplemented; WARNING-at-introduction when it lands).
        assertThat(LintConfig.DEFAULT.ruleSeverities()).doesNotContainKey("JBCT-CAUSE-06");
    }
}
