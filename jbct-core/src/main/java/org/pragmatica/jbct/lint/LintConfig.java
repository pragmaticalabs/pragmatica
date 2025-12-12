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
            Map.ofEntries(
                    // Return kinds
                    Map.entry("JBCT-RET-01", DiagnosticSeverity.ERROR),   // Bad return types (void, Optional, etc)
                    Map.entry("JBCT-RET-02", DiagnosticSeverity.ERROR),   // Nested wrappers
                    Map.entry("JBCT-RET-03", DiagnosticSeverity.ERROR),   // Return null
                    Map.entry("JBCT-RET-04", DiagnosticSeverity.ERROR),   // Use Unit not Void
                    // Value objects
                    Map.entry("JBCT-VO-01", DiagnosticSeverity.WARNING),  // Missing Result factory
                    Map.entry("JBCT-VO-02", DiagnosticSeverity.ERROR),    // Constructor bypass
                    // Exceptions
                    Map.entry("JBCT-EX-01", DiagnosticSeverity.ERROR),    // Business exceptions
                    Map.entry("JBCT-EX-02", DiagnosticSeverity.ERROR),    // orElseThrow
                    // Naming
                    Map.entry("JBCT-NAM-01", DiagnosticSeverity.WARNING), // Factory naming
                    Map.entry("JBCT-NAM-02", DiagnosticSeverity.WARNING), // Valid not Validated
                    // Lambda/composition
                    Map.entry("JBCT-LAM-01", DiagnosticSeverity.WARNING), // Complex lambdas
                    // Use case structure
                    Map.entry("JBCT-UC-01", DiagnosticSeverity.WARNING),  // Nested record factory
                    // Patterns
                    Map.entry("JBCT-PAT-01", DiagnosticSeverity.WARNING)  // Raw loops
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
