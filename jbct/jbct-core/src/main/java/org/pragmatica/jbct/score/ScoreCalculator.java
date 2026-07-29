package org.pragmatica.jbct.score;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Calculates JBCT compliance scores using density + severity weighting.
///
/// Formula:
/// weighted_violations = Σ(count[severity] × multiplier[severity])
///   error:    × 2.5
///   warning:  × 1.0
///   info:     × 0.3
///
/// category_score = 100 × (1 - weighted_violations / checkpoints)
/// overall_score = Σ(category_score[i] × weight[i])
///
/// Every category is measured the same way, but only the weighted ones reach
/// `overall_score`: advisory categories ([ScoreCategory#advisory()], weight 0) are
/// reported in the breakdown and contribute nothing to the total.
public sealed interface ScoreCalculator permits ScoreCalculator.unused {
    record unused() implements ScoreCalculator {}

    Logger log = LoggerFactory.getLogger(ScoreCalculator.class);

    double ERROR_MULTIPLIER = 2.5;
    double WARNING_MULTIPLIER = 1.0;
    double INFO_MULTIPLIER = 0.3;

    /// Calculate JBCT score from lint diagnostics.
    static ScoreResult calculate(List<Diagnostic> diagnostics, int filesAnalyzed) {
        warnUnknownRules(diagnostics);
        var categoryViolations = groupByCategory(diagnostics);
        var categoryCheckpoints = countCheckpoints(diagnostics);
        var breakdown = new EnumMap<ScoreCategory, ScoreResult.CategoryScore>(ScoreCategory.class);

        for (var category : ScoreCategory.values()) {
            var violations = categoryViolations.getOrDefault(category, List.of());
            var checkpoints = categoryCheckpoints.getOrDefault(category, 1);
            // Avoid division by zero
            var weightedViolations = calculateWeightedViolations(violations);
            var score = calculateCategoryScore(weightedViolations, checkpoints);

            breakdown.put(category,
                          ScoreResult.CategoryScore.categoryScore(score,
                                                                  checkpoints,
                                                                  violations.size(),
                                                                  weightedViolations));
        }

        var overall = calculateOverallScore(breakdown);

        return ScoreResult.scoreResult(overall, breakdown, filesAnalyzed);
    }

    /// Warn once per distinct unknown rule ID. Unknown diagnostics are excluded from
    /// scoring rather than silently bucketed into a default category.
    private static void warnUnknownRules(List<Diagnostic> diagnostics) {
        unknownRuleIds(diagnostics).forEach(ScoreCalculator::warnUnknownRule);
    }

    /// Distinct rule IDs in the diagnostics that are unknown to [RuleCategoryMapping]
    /// (neither categorized nor intentionally uncategorized). Exposed so the warn-once
    /// behaviour is observable: [#warnUnknownRules] logs exactly one warning per entry.
    static List<String> unknownRuleIds(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                          .map(Diagnostic::ruleId)
                          .filter(ruleId -> !RuleCategoryMapping.isKnown(ruleId))
                          .distinct()
                          .toList();
    }

    private static void warnUnknownRule(String ruleId) {
        log.warn("Lint rule '{}' has no score-category mapping and is excluded from the JBCT score; "
                 + "add it to RuleCategoryMapping (categorized or intentionally uncategorized).", ruleId);
    }

    private static Map<ScoreCategory, List<Diagnostic>> groupByCategory(List<Diagnostic> diagnostics) {
        var grouped = new EnumMap<ScoreCategory, List<Diagnostic>>(ScoreCategory.class);

        for (var category : ScoreCategory.values()) {
            grouped.put(category, violationsIn(category, diagnostics));
        }

        return grouped;
    }

    private static List<Diagnostic> violationsIn(ScoreCategory category, List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                          .filter(diagnostic -> isInCategory(diagnostic, category))
                          .toList();
    }

    private static boolean isInCategory(Diagnostic diagnostic, ScoreCategory category) {
        return RuleCategoryMapping.categoryFor(diagnostic.ruleId())
                                  .map(category::equals)
                                  .or(false);
    }

    private static Map<ScoreCategory, Integer> countCheckpoints(List<Diagnostic> diagnostics) {
        // For now, use violation count as proxy for checkpoints
        // TODO: Get actual checkpoint counts from linter
        var checkpointMap = new EnumMap<ScoreCategory, Integer>(ScoreCategory.class);

        for (var category : ScoreCategory.values()) {
            var violations = violationsIn(category, diagnostics).size();
            // Estimate: at least violations + 10% (so perfect score is possible)
            checkpointMap.put(category, (int)(violations * 1.1 + 10));
        }

        return checkpointMap;
    }

    private static double calculateWeightedViolations(List<Diagnostic> violations) {
        return violations.stream()
                         .mapToDouble(d -> switch (d.severity()) {
            case ERROR -> ERROR_MULTIPLIER;
            case WARNING -> WARNING_MULTIPLIER;
            case INFO -> INFO_MULTIPLIER;
        })
                         .sum();
    }

    private static int calculateCategoryScore(double weightedViolations, int checkpoints) {
        if (checkpoints == 0) {
            return 100;
        }

        var score = 100.0 * (1.0 - weightedViolations / checkpoints);

        return Math.max(0,
                        Math.min(100, (int) Math.round(score)));
    }

    private static int calculateOverallScore(Map<ScoreCategory, ScoreResult.CategoryScore> breakdown) {
        var weightedSum = 0.0;

        for (var category : ScoreCategory.weightedCategories()) {
            var categoryScore = breakdown.get(category);

            weightedSum += categoryScore.score() * category.weightFraction();
        }

        return (int) Math.round(weightedSum);
    }
}
