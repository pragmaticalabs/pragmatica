package org.pragmatica.jbct.score;

import java.util.Map;

import org.pragmatica.lang.Option;


/// Maps live lint rule IDs to scoring categories.
///
/// The mapping is keyed to the rule registry that actually feeds the scorer —
/// `CstLinter.defaultRules()`. Every rule the registry emits is assigned an explicit
/// [ScoreCategory] in [#MAPPING], chosen by rule semantics. Rules enforcing cosmetic
/// formatting, logging style, member ordering or zone-abstraction naming — concerns with
/// no home among the six principle categories — land in [ScoreCategory#STYLE], which
/// carries weight 0: they are counted and reported without distorting the weighted score.
///
/// Nothing falls through silently: a rule ID absent from [#MAPPING] is unknown, and
/// [ScoreCalculator] reports it loudly.
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
    Map.entry("JBCT-ARCH-01", ScoreCategory.PATTERN_PURITY),   // dependency direction (imports point up only)
    Map.entry("JBCT-ARCH-02", ScoreCategory.PATTERN_PURITY),   // lift(...) confined to adapter-boundary zone
    Map.entry("JBCT-ARCH-03", ScoreCategory.PATTERN_PURITY),   // use case must not call another use case
    Map.entry("JBCT-ARCH-04", ScoreCategory.PATTERN_PURITY),   // slice must not import another slice's internals
    Map.entry("JBCT-MUT-01", ScoreCategory.PATTERN_PURITY),    // no parameter reassignment
    Map.entry("JBCT-INJ-01", ScoreCategory.PATTERN_PURITY),    // constructor/factory injection only
    Map.entry("JBCT-STAGE-01", ScoreCategory.PATTERN_PURITY),  // stage-record conventions (growing context)
    Map.entry("JBCT-SHAPE-01", ScoreCategory.PATTERN_PURITY),  // method blends two patterns at one altitude (#448 census)
    Map.entry("JBCT-SHAPE-02", ScoreCategory.PATTERN_PURITY),  // method has no single pattern (#448 census)
    Map.entry("JBCT-SHAPE-03", ScoreCategory.PATTERN_PURITY),  // method shape disagrees with name-verb zone (#448 mis-leveled)

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
    Map.entry("JBCT-UC-02", ScoreCategory.FACTORY_METHODS),    // use-case interface structure
    Map.entry("JBCT-VAL-01", ScoreCategory.FACTORY_METHODS),   // boolean validation vs Result factory

    // Lambda Compliance (10%) — simple lambdas, method/constructor references
    Map.entry("JBCT-LAM-01", ScoreCategory.LAMBDA_COMPLIANCE), // lambda complexity
    Map.entry("JBCT-LAM-02", ScoreCategory.LAMBDA_COMPLIANCE), // lambda braces
    Map.entry("JBCT-LAM-03", ScoreCategory.LAMBDA_COMPLIANCE), // lambda ternary
    Map.entry("JBCT-STY-02", ScoreCategory.LAMBDA_COMPLIANCE), // constructor references
    Map.entry("JBCT-STY-05", ScoreCategory.LAMBDA_COMPLIANCE), // method reference preference
    Map.entry("JBCT-SIDE-01", ScoreCategory.LAMBDA_COMPLIANCE),// side effects in transformation lambdas

    // Style (0%, advisory) — formatting, logging, ordering and zone-naming conventions
    Map.entry("JBCT-STY-03", ScoreCategory.STYLE),             // fully-qualified names in method bodies
    Map.entry("JBCT-STY-04", ScoreCategory.STYLE),             // utility class → sealed interface
    Map.entry("JBCT-STY-06", ScoreCategory.STYLE),             // import ordering
    Map.entry("JBCT-STY-07", ScoreCategory.STYLE),             // unnecessary var before return
    Map.entry("JBCT-STY-08", ScoreCategory.STYLE),             // if/else with return in both branches
    Map.entry("JBCT-STY-09", ScoreCategory.STYLE),             // nested ternaries
    Map.entry("JBCT-ORD-01", ScoreCategory.STYLE),             // member ordering per file type
    Map.entry("JBCT-STATIC-01", ScoreCategory.STYLE),          // static imports for Pragmatica
    Map.entry("JBCT-LOG-01", ScoreCategory.STYLE),             // conditional logging
    Map.entry("JBCT-LOG-02", ScoreCategory.STYLE),             // logger as parameter
    Map.entry("JBCT-NAM-05", ScoreCategory.STYLE),             // test method naming
    Map.entry("JBCT-ZONE-01", ScoreCategory.STYLE),            // zone 2 verbs for steps
    Map.entry("JBCT-ZONE-02", ScoreCategory.STYLE),            // zone 3 verbs for leaves
    Map.entry("JBCT-ZONE-03", ScoreCategory.STYLE));           // no zone mixing

    /// Scoring category for a rule ID, or [Option#none()] when the rule is unknown to
    /// the mapping.
    static Option<ScoreCategory> categoryFor(String ruleId) {
        return Option.option(MAPPING.get(ruleId));
    }

    /// Whether a rule ID is known to the mapping. Unknown IDs are reported by
    /// [ScoreCalculator].
    static boolean isKnown(String ruleId) {
        return MAPPING.containsKey(ruleId);
    }

    static Map<String, ScoreCategory> mapping() {
        return MAPPING;
    }
}
