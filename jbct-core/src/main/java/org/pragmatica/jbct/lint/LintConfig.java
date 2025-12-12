package org.pragmatica.jbct.lint;

import java.util.Map;
import java.util.Set;

/**
 * Configuration for the JBCT linter.
 */
public record LintConfig(
        Map<String, DiagnosticSeverity> ruleSeverities,
        Set<String> disabledRules,
        boolean failOnWarning
) {

    /**
     * Default lint configuration.
     */
    public static final LintConfig DEFAULT = new LintConfig(
            Map.of(
                    "JBCT-RET-01", DiagnosticSeverity.ERROR,
                    "JBCT-RET-02", DiagnosticSeverity.ERROR,
                    "JBCT-VO-01", DiagnosticSeverity.WARNING,
                    "JBCT-VO-02", DiagnosticSeverity.WARNING,
                    "JBCT-EX-01", DiagnosticSeverity.ERROR,
                    "JBCT-NAM-01", DiagnosticSeverity.WARNING,
                    "JBCT-NAM-02", DiagnosticSeverity.WARNING,
                    "JBCT-PKG-01", DiagnosticSeverity.WARNING
            ),
            Set.of(),
            false
    );

    /**
     * Factory method for default config.
     */
    public static LintConfig defaultConfig() {
        return DEFAULT;
    }

    /**
     * Builder-style method to set rule severity.
     */
    public LintConfig withRuleSeverity(String ruleId, DiagnosticSeverity severity) {
        var newSeverities = new java.util.HashMap<>(ruleSeverities);
        newSeverities.put(ruleId, severity);
        return new LintConfig(Map.copyOf(newSeverities), disabledRules, failOnWarning);
    }

    /**
     * Builder-style method to disable a rule.
     */
    public LintConfig withDisabledRule(String ruleId) {
        var newDisabled = new java.util.HashSet<>(disabledRules);
        newDisabled.add(ruleId);
        return new LintConfig(ruleSeverities, Set.copyOf(newDisabled), failOnWarning);
    }

    /**
     * Builder-style method to set fail on warning.
     */
    public LintConfig withFailOnWarning(boolean failOnWarning) {
        return new LintConfig(ruleSeverities, disabledRules, failOnWarning);
    }
}
