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
                    Map.entry("JBCT-RET-05", DiagnosticSeverity.WARNING), // Always-succeeding Result
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
                    Map.entry("JBCT-LAM-02", DiagnosticSeverity.WARNING), // Lambda braces
                    Map.entry("JBCT-LAM-03", DiagnosticSeverity.WARNING), // Lambda ternary
                    // Use case structure
                    Map.entry("JBCT-UC-01", DiagnosticSeverity.WARNING),  // Nested record factory
                    // Patterns
                    Map.entry("JBCT-PAT-01", DiagnosticSeverity.WARNING), // Raw loops
                    Map.entry("JBCT-SEQ-01", DiagnosticSeverity.WARNING), // Chain length
                    // Style
                    Map.entry("JBCT-STY-01", DiagnosticSeverity.WARNING), // Fluent failure style
                    Map.entry("JBCT-STY-02", DiagnosticSeverity.WARNING), // Constructor references
                    Map.entry("JBCT-STY-03", DiagnosticSeverity.WARNING), // No FQCN
                    Map.entry("JBCT-STY-04", DiagnosticSeverity.WARNING), // Utility class → sealed interface
                    Map.entry("JBCT-STY-05", DiagnosticSeverity.WARNING), // Method reference preference
                    Map.entry("JBCT-STY-06", DiagnosticSeverity.WARNING), // Import ordering
                    // Logging
                    Map.entry("JBCT-LOG-01", DiagnosticSeverity.WARNING), // Conditional logging
                    Map.entry("JBCT-LOG-02", DiagnosticSeverity.WARNING), // Logger as parameter
                    // Architecture
                    Map.entry("JBCT-MIX-01", DiagnosticSeverity.ERROR),   // I/O in domain
                    // Static imports
                    Map.entry("JBCT-STATIC-01", DiagnosticSeverity.WARNING), // Static imports for Pragmatica
                    // Utilities
                    Map.entry("JBCT-UTIL-01", DiagnosticSeverity.WARNING), // Pragmatica parsing utilities
                    Map.entry("JBCT-UTIL-02", DiagnosticSeverity.WARNING), // Verify.Is predicates
                    // Nesting
                    Map.entry("JBCT-NEST-01", DiagnosticSeverity.WARNING), // Nested monadic operations
                    // Zones
                    Map.entry("JBCT-ZONE-01", DiagnosticSeverity.WARNING), // Zone 2 verbs for steps
                    Map.entry("JBCT-ZONE-02", DiagnosticSeverity.WARNING), // Zone 3 verbs for leaves
                    Map.entry("JBCT-ZONE-03", DiagnosticSeverity.WARNING), // No zone mixing
                    // Acronyms and patterns
                    Map.entry("JBCT-ACR-01", DiagnosticSeverity.WARNING),  // Acronym naming
                    Map.entry("JBCT-SEAL-01", DiagnosticSeverity.WARNING), // Sealed error interfaces
                    Map.entry("JBCT-PAT-02", DiagnosticSeverity.WARNING)   // No Fork-Join inside Sequencer
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
