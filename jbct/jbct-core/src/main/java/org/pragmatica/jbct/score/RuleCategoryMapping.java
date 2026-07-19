package org.pragmatica.jbct.score;

import java.util.Map;
import java.util.Set;

import org.pragmatica.lang.Option;


/// Maps live lint rule IDs to scoring categories.
///
/// The mapping is keyed to the rule registry that actually feeds the scorer —
/// `CstLinter.defaultRules()`. Every rule the registry emits is either assigned an
/// explicit [ScoreCategory] in [#MAPPING] (by rule semantics) or listed in
/// [#UNCATEGORIZED]. The uncategorized set holds rules that enforce cosmetic
/// formatting, logging style, or zone-abstraction naming conventions — concerns with
/// no home among the six principle categories — so they are deliberately excluded
/// from the weighted score rather than distorting it.
///
/// Nothing falls through silently: a rule ID that is neither categorized nor
/// uncategorized is unknown, and [ScoreCalculator] reports it loudly.
public sealed interface RuleCategoryMapping permits RuleCategoryMapping.unused {
    record unused() implements RuleCategoryMapping {}

    /// Rule IDs assigned to a scoring category, chosen by rule semantics.
    Map<String, ScoreCategory> MAPPING = Map.ofEntries(
    // Return Types (25%) — correct use of the four return kinds
    Map.entry("JBCT-RET-01", ScoreCategory.RETURN_TYPES),      // four return kinds
    Map.entry("JBCT-RET-02", ScoreCategory.RETURN_TYPES),      // no Promise<Result<T>> nesting
    Map.entry("JBCT-RET-04", ScoreCategory.RETURN_TYPES),      // no Void type parameter
    Map.entry("JBCT-RET-05", ScoreCategory.RETURN_TYPES),      // no always-succeeding Result
    Map.entry("JBCT-RET-07", ScoreCategory.RETURN_TYPES),      // no discarded Result/Promise/Option
    Map.entry("JBCT-BND-01", ScoreCategory.RETURN_TYPES),      // no forbidden boundary types (Optional/CompletableFuture/…)

    // Null Safety (20%) — no null returns, no nullable parameters
    Map.entry("JBCT-RET-03", ScoreCategory.NULL_SAFETY),       // no null return
    Map.entry("JBCT-RET-06", ScoreCategory.NULL_SAFETY),       // no nullable parameter
    Map.entry("JBCT-RET-08", ScoreCategory.NULL_SAFETY),       // no null argument / defensive null comparison
    Map.entry("JBCT-TOT-03", ScoreCategory.NULL_SAFETY),       // wire-record accessor derefs possibly-null component

    // Exception Hygiene (20%) — no business exceptions, proper typed error handling
    Map.entry("JBCT-EX-01", ScoreCategory.EXCEPTION_HYGIENE),  // no business exceptions
    Map.entry("JBCT-EX-02", ScoreCategory.EXCEPTION_HYGIENE),  // no orElseThrow
    Map.entry("JBCT-STY-01", ScoreCategory.EXCEPTION_HYGIENE), // fluent failure (cause.result())
    Map.entry("JBCT-SEAL-01", ScoreCategory.EXCEPTION_HYGIENE),// sealed error interfaces
    Map.entry("JBCT-SEAL-02", ScoreCategory.EXCEPTION_HYGIENE),// cause variant style (enum vs record)
    Map.entry("JBCT-UTIL-01", ScoreCategory.EXCEPTION_HYGIENE),// Result-returning parse utilities
    Map.entry("JBCT-TOT-01", ScoreCategory.EXCEPTION_HYGIENE), // partial op in mapper lambda throws
    Map.entry("JBCT-TOT-02", ScoreCategory.EXCEPTION_HYGIENE), // partial method reference in mapper throws

    // Pattern Purity (15%) — single pattern per function, no mixing
    Map.entry("JBCT-PAT-01", ScoreCategory.PATTERN_PURITY),    // no raw loops
    Map.entry("JBCT-PAT-02", ScoreCategory.PATTERN_PURITY),    // no pattern mixing
    Map.entry("JBCT-PAT-03", ScoreCategory.PATTERN_PURITY),    // no blocking await
    Map.entry("JBCT-SEQ-01", ScoreCategory.PATTERN_PURITY),    // sequencer chain length
    Map.entry("JBCT-NEST-01", ScoreCategory.PATTERN_PURITY),   // no nested monadic operations
    Map.entry("JBCT-MIX-01", ScoreCategory.PATTERN_PURITY),    // no I/O mixed into domain
    Map.entry("JBCT-MUT-01", ScoreCategory.PATTERN_PURITY),    // no parameter reassignment

    // Factory Methods (10%) — value object factories and naming conventions
    Map.entry("JBCT-VO-01", ScoreCategory.FACTORY_METHODS),    // missing Result factory
    Map.entry("JBCT-VO-02", ScoreCategory.FACTORY_METHODS),    // constructor bypass
    Map.entry("JBCT-UC-01", ScoreCategory.FACTORY_METHODS),    // nested record factory
    Map.entry("JBCT-NAM-01", ScoreCategory.FACTORY_METHODS),   // factory naming
    Map.entry("JBCT-NAM-02", ScoreCategory.FACTORY_METHODS),   // Valid not Validated
    Map.entry("JBCT-NAM-03", ScoreCategory.FACTORY_METHODS),   // *State suffix discipline
    Map.entry("JBCT-NAM-04", ScoreCategory.FACTORY_METHODS),   // local records lowercase camelCase
    Map.entry("JBCT-ACR-01", ScoreCategory.FACTORY_METHODS),   // acronym naming
    Map.entry("JBCT-UTIL-02", ScoreCategory.FACTORY_METHODS),  // Verify.Is validation predicates

    // Lambda Compliance (10%) — simple lambdas, method/constructor references
    Map.entry("JBCT-LAM-01", ScoreCategory.LAMBDA_COMPLIANCE), // lambda complexity
    Map.entry("JBCT-LAM-02", ScoreCategory.LAMBDA_COMPLIANCE), // lambda braces
    Map.entry("JBCT-LAM-03", ScoreCategory.LAMBDA_COMPLIANCE), // lambda ternary
    Map.entry("JBCT-STY-02", ScoreCategory.LAMBDA_COMPLIANCE), // constructor references
    Map.entry("JBCT-STY-05", ScoreCategory.LAMBDA_COMPLIANCE));// method reference preference

    /// Rule IDs deliberately excluded from scoring: cosmetic formatting, logging
    /// style, and zone-abstraction naming rules that map to none of the six
    /// principle categories. Listed explicitly so they never fall through silently.
    Set<String> UNCATEGORIZED = Set.of("JBCT-STY-03",     // fully-qualified names in method bodies
                                       "JBCT-STY-04",     // utility class → sealed interface
                                       "JBCT-STY-06",     // import ordering
                                       "JBCT-STY-07",     // unnecessary var before return
                                       "JBCT-STY-08",     // if/else with return in both branches
                                       "JBCT-STY-09",     // nested ternaries
                                       "JBCT-STATIC-01",  // static imports for Pragmatica
                                       "JBCT-LOG-01",     // conditional logging
                                       "JBCT-LOG-02",     // logger as parameter
                                       "JBCT-NAM-05",     // test method naming
                                       "JBCT-ZONE-01",    // zone 2 verbs for steps
                                       "JBCT-ZONE-02",    // zone 3 verbs for leaves
                                       "JBCT-ZONE-03");   // no zone mixing

    /// Scoring category for a rule ID, or [Option#none()] when the rule is
    /// intentionally uncategorized or unknown to the mapping.
    static Option<ScoreCategory> categoryFor(String ruleId) {
        return Option.option(MAPPING.get(ruleId));
    }

    /// Whether a rule ID is known to the mapping — either categorized or
    /// intentionally uncategorized. Unknown IDs are reported by [ScoreCalculator].
    static boolean isKnown(String ruleId) {
        return MAPPING.containsKey(ruleId) || UNCATEGORIZED.contains(ruleId);
    }

    static Map<String, ScoreCategory> mapping() {
        return MAPPING;
    }

    static Set<String> uncategorized() {
        return UNCATEGORIZED;
    }
}
