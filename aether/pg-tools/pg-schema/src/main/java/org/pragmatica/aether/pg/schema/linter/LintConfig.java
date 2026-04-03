package org.pragmatica.aether.pg.schema.linter;

import java.util.Map;
import java.util.Set;


/// Lint configuration: enable/disable rules, override severity.
public record LintConfig(Set<String> disabledRules, Map<String, LintDiagnostic.Severity> severityOverrides) {
    public static LintConfig defaults() {
        return new LintConfig(Set.of(), Map.of());
    }

    public boolean isEnabled(String ruleId) {
        return ! disabledRules.contains(ruleId);
    }

    public LintDiagnostic.Severity severity(String ruleId, LintDiagnostic.Severity defaultSeverity) {
        return severityOverrides.getOrDefault(ruleId, defaultSeverity);
    }
}
