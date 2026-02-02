package org.pragmatica.jbct.score;
/**
 * JBCT score categories with their weights.
 *
 * Categories track compliance across different JBCT principles.
 */
public enum ScoreCategory {
    /**
     * Return Types (25%) - Correct use of four return kinds.
     */
    RETURN_TYPES(25.0),
    /**
     * Null Safety (20%) - No null returns, no nullable parameters.
     */
    NULL_SAFETY(20.0),
    /**
     * Exception Hygiene (20%) - No business exceptions, proper error handling.
     */
    EXCEPTION_HYGIENE(20.0),
    /**
     * Pattern Purity (15%) - Single pattern per function, no mixing.
     */
    PATTERN_PURITY(15.0),
    /**
     * Factory Methods (10%) - Value object factories, naming conventions.
     */
    FACTORY_METHODS(10.0),
    /**
     * Lambda Compliance (10%) - Simple lambdas, no complex logic.
     */
    LAMBDA_COMPLIANCE(10.0);
    private final double weight;
    ScoreCategory(double weight) {
        this.weight = weight;
    }
    /**
     * Get the weight of this category (0-100).
     */
    public double weight() {
        return weight;
    }
    /**
     * Get the weight as a fraction (0.0-1.0).
     */
    public double weightFraction() {
        return weight / 100.0;
    }
}
