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

    // Failure absorbed with no recorded reason. WARNING, not ERROR: absorption is legitimate and
    // common, and the corpus that motivated the rule documents every one of its eight sites.
    Map.entry("JBCT-REC-01", DiagnosticSeverity.WARNING),

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
    Map.entry("JBCT-SEAL-02", DiagnosticSeverity.WARNING),

    // File-type classifier + structural rules (#453). All provisional WARNING pending a corpus
    // calibration gate (design severity to be fixed after the aether corpus run); SIDE-01 ships at
    // INFO as an expected false-positive surface, promote-after-calibration.
    // Use-case interface structure
    Map.entry("JBCT-UC-02", DiagnosticSeverity.WARNING),

    // Member ordering per file type
    Map.entry("JBCT-ORD-01", DiagnosticSeverity.WARNING),

    // Constructor/factory injection only
    Map.entry("JBCT-INJ-01", DiagnosticSeverity.WARNING),

    // Boolean validation methods
    Map.entry("JBCT-VAL-01", DiagnosticSeverity.WARNING),

    // Stage-record conventions
    Map.entry("JBCT-STAGE-01", DiagnosticSeverity.WARNING),

    // Side effects in transformation lambdas (INFO — expected FP surface, promote after calibration)
    Map.entry("JBCT-SIDE-01", DiagnosticSeverity.INFO),

    // Method-shape classifier census (#448, phase 1). Both INFO — census-only, no build gate; the
    // shape distribution + residual rate is measured on the aether corpus before phase 2 promotes
    // MIXED/UNCLASSIFIED to WARNING (precedent SIDE-01).
    // Method blends two patterns at one altitude (MIXED)
    Map.entry("JBCT-SHAPE-01", DiagnosticSeverity.INFO),

    // Method has no single pattern (UNCLASSIFIED)
    Map.entry("JBCT-SHAPE-02", DiagnosticSeverity.INFO),

    // Method's composition shape disagrees with its name-verb's zone (mis-leveled). INFO — naming
    // zone and composition shape are orthogonal axes. DEFAULT-DISABLED after the corpus gate (622
    // hits: 460 orchestration-verb-on-LEAF one-liners are legitimate naming, the noise the FP doc
    // predicted); census-on-demand like SHAPE-02.
    Map.entry("JBCT-SHAPE-03", DiagnosticSeverity.INFO)),
                                                        // JBCT-SHAPE-02 ships DEFAULT-DISABLED: after the phase-2 preamble reach
                                                        // the aether corpus census returned 3832 UNCLASSIFIED methods (down from
                                                        // 5336; the residue is genuinely imperative code — loops, multi-branch
                                                        // side effects — a corpus fact, not a classifier gap), so leaving it on
                                                        // would spam every `jbct:check` run and the <5% promotion gate is not
                                                        // reachable on this corpus. It stays a registered rule (severity +
                                                        // fixture + category invariants hold) and is run on demand — enable it in
                                                        // config for a shape census. JBCT-SHAPE-03 is likewise DEFAULT-DISABLED
                                                        // (622 corpus hits, mostly orchestration-verb-on-LEAF one-liners — a
                                                        // census signal, not an enforceable rule on this corpus). JBCT-SHAPE-01
                                                        // (MIXED) stays enabled: it is corpus-zero and silent, the real-time
                                                        // single-pattern signal worth having.
                                                        Set.of("JBCT-SHAPE-02", "JBCT-SHAPE-03"),
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
