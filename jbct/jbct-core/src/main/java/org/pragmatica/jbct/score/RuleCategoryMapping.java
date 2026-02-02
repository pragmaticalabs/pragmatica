package org.pragmatica.jbct.score;

import java.util.Map;

/**
 * Maps lint rule IDs to scoring categories.
 */
public final class RuleCategoryMapping {
    private static final Map<String, ScoreCategory> MAPPING = Map.ofEntries(// Return Types (25%)
    Map.entry("JBCT-RET-01", ScoreCategory.RETURN_TYPES),
    // Four Return Kinds
    Map.entry("JBCT-RET-02", ScoreCategory.RETURN_TYPES),
    // No Void
    Map.entry("JBCT-RET-03", ScoreCategory.RETURN_TYPES),
    // No Promise<Result<T>>
    Map.entry("JBCT-NEST-02", ScoreCategory.RETURN_TYPES),
    // No nested wrappers
    Map.entry("JBCT-RES-01", ScoreCategory.RETURN_TYPES),
    // Always-success Result
    // Null Safety (20%)
    Map.entry("JBCT-NULL-01", ScoreCategory.NULL_SAFETY),
    // No null return
    Map.entry("JBCT-NULL-02", ScoreCategory.NULL_SAFETY),
    // No nullable parameters
    // Exception Hygiene (20%)
    Map.entry("JBCT-EXC-01", ScoreCategory.EXCEPTION_HYGIENE),
    // No business exceptions
    Map.entry("JBCT-EXC-02", ScoreCategory.EXCEPTION_HYGIENE),
    // No orElseThrow
    // Pattern Purity (15%)
    Map.entry("JBCT-PAT-01", ScoreCategory.PATTERN_PURITY),
    // Pattern mixing
    Map.entry("JBCT-NEST-01", ScoreCategory.PATTERN_PURITY),
    // Nested operations
    Map.entry("JBCT-CHAIN-01", ScoreCategory.PATTERN_PURITY),
    // Chain length
    // Factory Methods (10%)
    Map.entry("JBCT-VO-01", ScoreCategory.FACTORY_METHODS),
    // Factory naming
    Map.entry("JBCT-VO-02", ScoreCategory.FACTORY_METHODS),
    // Constructor bypass
    Map.entry("JBCT-VO-03", ScoreCategory.FACTORY_METHODS),
    // Constructor reference
    Map.entry("JBCT-VO-04", ScoreCategory.FACTORY_METHODS),
    // Nested record factory
    Map.entry("JBCT-ERR-01", ScoreCategory.FACTORY_METHODS),
    // Sealed error types
    // Lambda Compliance (10%)
    Map.entry("JBCT-LAM-01", ScoreCategory.LAMBDA_COMPLIANCE),
    // Lambda braces
    Map.entry("JBCT-LAM-02", ScoreCategory.LAMBDA_COMPLIANCE),
    // Lambda complexity
    Map.entry("JBCT-LAM-03", ScoreCategory.LAMBDA_COMPLIANCE),
    // Lambda ternary
    Map.entry("JBCT-LAM-04", ScoreCategory.LAMBDA_COMPLIANCE),
    // Method reference preference
    // Cross-cutting (distribute to appropriate categories)
    Map.entry("JBCT-NAME-01", ScoreCategory.FACTORY_METHODS),
    // Acronym naming
    Map.entry("JBCT-NAME-02", ScoreCategory.EXCEPTION_HYGIENE),
    // Fluent failure
    Map.entry("JBCT-STATIC-01", ScoreCategory.LAMBDA_COMPLIANCE),
    // Static imports
    Map.entry("JBCT-IMPORT-01", ScoreCategory.PATTERN_PURITY),
    // Import ordering
    Map.entry("JBCT-FQN-01", ScoreCategory.PATTERN_PURITY),
    // Fully qualified names
    Map.entry("JBCT-DOMAIN-01", ScoreCategory.PATTERN_PURITY),
    // Domain I/O separation
    Map.entry("JBCT-LOOP-01", ScoreCategory.PATTERN_PURITY),
    // Raw loops
    Map.entry("JBCT-LOG-01", ScoreCategory.PATTERN_PURITY),
    // Conditional logging
    Map.entry("JBCT-LOG-02", ScoreCategory.PATTERN_PURITY),
    // Logger parameters
    Map.entry("JBCT-PARSE-01", ScoreCategory.FACTORY_METHODS));

    private RuleCategoryMapping() {}

    public static ScoreCategory categoryFor(String ruleId) {
        return MAPPING.getOrDefault(ruleId, ScoreCategory.PATTERN_PURITY);
    }

    public static Map<String, ScoreCategory> mapping() {
        return MAPPING;
    }
}
