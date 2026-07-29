package org.pragmatica.jbct.score;

import java.util.Arrays;
import java.util.List;


/// JBCT score categories with their weights.
///
/// Categories track compliance across different JBCT principles. The six principle
/// categories carry weights summing to 100 and together produce the weighted overall
/// score. [#STYLE] is *advisory*: it carries weight 0, so it is measured, bucketed and
/// reported exactly like every other category but contributes nothing to the overall
/// score. Advisory categories exist so that rules with no home among the principles are
/// still visible instead of silently vanishing from the report.
public enum ScoreCategory {
    /// Return Types (25%) - Correct use of four return kinds.
    RETURN_TYPES(25.0),
    /// Null Safety (20%) - No null returns, no nullable parameters.
    NULL_SAFETY(20.0),
    /// Exception Hygiene (20%) - No business exceptions, proper error handling.
    EXCEPTION_HYGIENE(20.0),
    /// Pattern Purity (15%) - Single pattern per function, no mixing.
    PATTERN_PURITY(15.0),
    /// Factory Methods (10%) - Value object factories, naming conventions.
    FACTORY_METHODS(10.0),
    /// Lambda Compliance (10%) - Simple lambdas, no complex logic.
    LAMBDA_COMPLIANCE(10.0),
    /// Style (0%, advisory) - Formatting, static-import, logging, member-ordering and
    /// zone-naming conventions. These rules belong to no principle category, so they are
    /// reported for visibility and excluded from the weighted score.
    STYLE(0.0);
    private final double weight;
    ScoreCategory(double weight) {
        this.weight = weight;
    }
    /// Get the weight of this category (0-100).
    public double weight() {
        return weight;
    }
    /// Get the weight as a fraction (0.0-1.0).
    public double weightFraction() {
        return weight / 100.0;
    }
    /// Whether this category is advisory: reported, but excluded from the overall score.
    public boolean advisory() {
        return weight == 0.0;
    }
    /// Categories that contribute to the weighted overall score, in declaration order.
    public static List<ScoreCategory> weightedCategories() {
        return Arrays.stream(values())
                     .filter(category -> !category.advisory())
                     .toList();
    }
    /// Categories reported for visibility only, in declaration order.
    public static List<ScoreCategory> advisoryCategories() {
        return Arrays.stream(values())
                     .filter(ScoreCategory::advisory)
                     .toList();
    }
}
