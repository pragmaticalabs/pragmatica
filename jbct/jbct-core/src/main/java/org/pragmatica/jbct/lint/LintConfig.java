package org.pragmatica.jbct.lint;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;


/// Configuration for the JBCT linter.
public record LintConfig(Map<String, DiagnosticSeverity> ruleSeverities,
                         Set<String> disabledRules,
                         boolean failOnWarning) {
    public LintConfig {
        ruleSeverities = Map.copyOf(ruleSeverities);
        disabledRules = Set.copyOf(disabledRules);
    }

    /// Factory method for creating LintConfig.
    public static LintConfig lintConfig(Map<String, DiagnosticSeverity> ruleSeverities,
                                        Set<String> disabledRules,
                                        boolean failOnWarning) {
        return new LintConfig(ruleSeverities, disabledRules, failOnWarning);
    }

    /// Default lint configuration.
    public static final LintConfig DEFAULT = lintConfig(Map.ofEntries(
    // Return kinds
    Map.entry("JBCT-RET-01", DiagnosticSeverity.ERROR),

    // Bad return types (void, Optional, etc)
    Map.entry("JBCT-RET-02", DiagnosticSeverity.ERROR),

    // Nested wrappers
    Map.entry("JBCT-RET-03", DiagnosticSeverity.ERROR),

    // Return null
    Map.entry("JBCT-RET-04", DiagnosticSeverity.ERROR),

    // Use Unit not Void
    Map.entry("JBCT-RET-05", DiagnosticSeverity.WARNING),

    // Always-succeeding Result
    // Nullable parameter — null-safety family, matches JBCT-RET-03. Corpus clean at ERROR
    // since the #489 burn-down (2026-07-19).
    Map.entry("JBCT-RET-06", DiagnosticSeverity.ERROR),

    // Value objects
    Map.entry("JBCT-VO-01", DiagnosticSeverity.WARNING),

    // Missing Result factory
    Map.entry("JBCT-VO-02", DiagnosticSeverity.ERROR),

    // Constructor bypass
    // Exceptions
    Map.entry("JBCT-EX-01", DiagnosticSeverity.ERROR),

    // Business exceptions
    Map.entry("JBCT-EX-02", DiagnosticSeverity.ERROR),

    // orElseThrow
    // Naming
    Map.entry("JBCT-NAM-01", DiagnosticSeverity.WARNING),

    // Factory naming
    Map.entry("JBCT-NAM-02", DiagnosticSeverity.WARNING),

    // Valid not Validated
    // Lambda/composition
    Map.entry("JBCT-LAM-01", DiagnosticSeverity.WARNING),

    // Complex lambdas
    Map.entry("JBCT-LAM-02", DiagnosticSeverity.WARNING),

    // Lambda braces
    Map.entry("JBCT-LAM-03", DiagnosticSeverity.WARNING),

    // Lambda ternary
    // Use case structure
    Map.entry("JBCT-UC-01", DiagnosticSeverity.WARNING),

    // Nested record factory
    // Patterns
    Map.entry("JBCT-PAT-01", DiagnosticSeverity.WARNING),

    // Raw loops
    Map.entry("JBCT-SEQ-01", DiagnosticSeverity.WARNING),

    // Chain length
    // Style
    Map.entry("JBCT-STY-01", DiagnosticSeverity.WARNING),

    // Fluent failure style
    Map.entry("JBCT-STY-02", DiagnosticSeverity.WARNING),

    // Constructor references
    Map.entry("JBCT-STY-03", DiagnosticSeverity.WARNING),

    // No FQCN
    Map.entry("JBCT-STY-04", DiagnosticSeverity.WARNING),

    // Utility class → sealed interface
    Map.entry("JBCT-STY-05", DiagnosticSeverity.WARNING),

    // Method reference preference
    Map.entry("JBCT-STY-06", DiagnosticSeverity.WARNING),

    // Import ordering
    // Logging
    Map.entry("JBCT-LOG-01", DiagnosticSeverity.WARNING),

    // Conditional logging
    Map.entry("JBCT-LOG-02", DiagnosticSeverity.WARNING),

    // Logger as parameter
    // Architecture
    Map.entry("JBCT-MIX-01", DiagnosticSeverity.ERROR),

    // I/O in domain
    // Architecture / layering (#452). ARCH-01/04 at design ERROR — corpus clean (2026-07-19);
    // ARCH-02/03 WARNING by design.
    Map.entry("JBCT-ARCH-01", DiagnosticSeverity.ERROR),

    // Dependency direction — imports point up only
    Map.entry("JBCT-ARCH-02", DiagnosticSeverity.WARNING),

    // lift(...) confined to the adapter-boundary zone
    Map.entry("JBCT-ARCH-03", DiagnosticSeverity.WARNING),

    // A use case must not call another use case
    Map.entry("JBCT-ARCH-04", DiagnosticSeverity.ERROR),

    // A slice must not import another slice's internals
    // Static imports
    Map.entry("JBCT-STATIC-01", DiagnosticSeverity.WARNING),

    // Static imports for Pragmatica
    // Utilities
    Map.entry("JBCT-UTIL-01", DiagnosticSeverity.WARNING),

    // Pragmatica parsing utilities
    Map.entry("JBCT-UTIL-02", DiagnosticSeverity.WARNING),

    // Verify.Is predicates
    // Nesting
    Map.entry("JBCT-NEST-01", DiagnosticSeverity.WARNING),

    // Nested monadic operations
    // Zones
    Map.entry("JBCT-ZONE-01", DiagnosticSeverity.WARNING),

    // Zone 2 verbs for steps
    Map.entry("JBCT-ZONE-02", DiagnosticSeverity.WARNING),

    // Zone 3 verbs for leaves
    Map.entry("JBCT-ZONE-03", DiagnosticSeverity.WARNING),

    // No zone mixing
    // Acronyms and patterns
    Map.entry("JBCT-ACR-01", DiagnosticSeverity.WARNING),

    // Acronym naming
    Map.entry("JBCT-SEAL-01", DiagnosticSeverity.WARNING),

    // Sealed error interfaces
    Map.entry("JBCT-PAT-02", DiagnosticSeverity.WARNING),

    // No Fork-Join inside Sequencer
    Map.entry("JBCT-PAT-03", DiagnosticSeverity.WARNING),

    // Blocking .await()
    // Discarded values
    Map.entry("JBCT-RET-07", DiagnosticSeverity.ERROR),

    // Discarded Result/Promise/Option
    // Style — expression-based
    Map.entry("JBCT-STY-07", DiagnosticSeverity.WARNING),

    // Unnecessary var before return
    Map.entry("JBCT-STY-08", DiagnosticSeverity.WARNING),

    // Mapper safety / totality (#486). Corpus clean at ERROR since the #489 mapper burn-down
    // (false-positive rule fixes + site fixes, 2026-07-19).
    // Partial op in mapper lambda
    Map.entry("JBCT-TOT-01", DiagnosticSeverity.ERROR),

    // Partial method reference in mapper
    Map.entry("JBCT-TOT-02", DiagnosticSeverity.ERROR),

    // Jackson wire-record accessor dereferences possibly-null component
    Map.entry("JBCT-TOT-03", DiagnosticSeverity.WARNING),

    // Easy-tier batch (#451). BND-01 at design ERROR — all corpus sites dispositioned (#493).
    // Forbidden boundary types in business logic
    Map.entry("JBCT-BND-01", DiagnosticSeverity.ERROR),

    // Nested ternaries
    Map.entry("JBCT-STY-09", DiagnosticSeverity.WARNING),

    // *State suffix discipline
    Map.entry("JBCT-NAM-03", DiagnosticSeverity.WARNING),

    // Local records lowercase camelCase
    Map.entry("JBCT-NAM-04", DiagnosticSeverity.WARNING),

    // Test method naming
    Map.entry("JBCT-NAM-05", DiagnosticSeverity.WARNING),

    // Parameter reassignment
    Map.entry("JBCT-MUT-01", DiagnosticSeverity.WARNING),

    // Null literal argument / defensive null comparison
    Map.entry("JBCT-RET-08", DiagnosticSeverity.WARNING),

    // Cause variant style: enum vs record
    Map.entry("JBCT-SEAL-02", DiagnosticSeverity.WARNING)),
                                                        Set.of(),
                                                        false);

    /// Factory method for default config.
    public static LintConfig defaultConfig() {
        return DEFAULT;
    }

    /// Builder-style method to set rule severity.
    public LintConfig withRuleSeverity(String ruleId, DiagnosticSeverity severity) {
        var newSeverities = new HashMap<>(ruleSeverities);

        newSeverities.put(ruleId, severity);

        return lintConfig(Map.copyOf(newSeverities), disabledRules, failOnWarning);
    }

    /// Builder-style method to disable a rule.
    public LintConfig withDisabledRule(String ruleId) {
        var newDisabled = new HashSet<>(disabledRules);

        newDisabled.add(ruleId);

        return lintConfig(ruleSeverities, Set.copyOf(newDisabled), failOnWarning);
    }

    /// Builder-style method to set fail on warning.
    public LintConfig withFailOnWarning(boolean failOnWarning) {
        return lintConfig(ruleSeverities, disabledRules, failOnWarning);
    }
}
