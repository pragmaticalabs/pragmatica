package org.pragmatica.jbct.score;

import java.util.Arrays;
import java.util.List;


/// JBCT report categories.
///
/// Categories bucket lint diagnostics by the JBCT principle they belong to. They carry no
/// weights: violation density is a plain count per KLOC, so every violation counts exactly once
/// and no category is quietly worth more than another. Severity is reported as raw ERROR /
/// WARNING / INFO counts next to each density rather than folded into it, for the same reason.
///
/// [#STYLE] is *advisory*: it is measured and reported exactly like every other category but is
/// excluded from the total density, so formatting and naming findings cannot inflate the headline
/// number. Advisory categories exist so that rules with no home among the principles are still
/// visible instead of silently vanishing from the report.
public enum ScoreCategory {
    /// Return Types - correct use of four return kinds.
    RETURN_TYPES(false),
    /// Null Safety - no null returns, no nullable parameters.
    NULL_SAFETY(false),
    /// Exception Hygiene - no business exceptions, proper error handling.
    EXCEPTION_HYGIENE(false),
    /// Pattern Purity - single pattern per function, no mixing.
    PATTERN_PURITY(false),
    /// Factory Methods - value object factories, naming conventions.
    FACTORY_METHODS(false),
    /// Lambda Compliance - simple lambdas, no complex logic.
    LAMBDA_COMPLIANCE(false),
    /// Style (advisory) - formatting, static-import, logging, member-ordering and zone-naming
    /// conventions. These rules belong to no principle category, so they are reported separately
    /// and excluded from the total density.
    STYLE(true);
    private final boolean advisory;
    ScoreCategory(boolean advisory) {
        this.advisory = advisory;
    }
    /// Whether this category is advisory: reported separately, but excluded from the total density.
    public boolean advisory() {
        return advisory;
    }
    /// Categories that contribute to the total density, in declaration order.
    public static List<ScoreCategory> countedCategories() {
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
