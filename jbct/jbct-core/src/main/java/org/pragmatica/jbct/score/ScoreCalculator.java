package org.pragmatica.jbct.score;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Calculates JBCT compliance scores using density + severity weighting.
 *
 * Formula:
 * weighted_violations = Σ(count[severity] × multiplier[severity])
 *   error:    × 2.5
 *   warning:  × 1.0
 *   info:     × 0.3
 *
 * category_score = 100 × (1 - weighted_violations / checkpoints)
 * overall_score = Σ(category_score[i] × weight[i])
 */
public final class ScoreCalculator {
    private static final double ERROR_MULTIPLIER = 2.5;
    private static final double WARNING_MULTIPLIER = 1.0;
    private static final double INFO_MULTIPLIER = 0.3;

    private ScoreCalculator() {}

    /**
     * Calculate JBCT score from lint diagnostics.
     */
    public static ScoreResult calculate(List<Diagnostic> diagnostics, int filesAnalyzed) {
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
                          new ScoreResult.CategoryScore(score, checkpoints, violations.size(), weightedViolations));
        }
        var overall = calculateOverallScore(breakdown);
        return new ScoreResult(overall, breakdown, filesAnalyzed);
    }

    private static Map<ScoreCategory, List<Diagnostic>> groupByCategory(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                          .collect(java.util.stream.Collectors.groupingBy(d -> RuleCategoryMapping.categoryFor(d.ruleId())));
    }

    private static Map<ScoreCategory, Integer> countCheckpoints(List<Diagnostic> diagnostics) {
        // For now, use violation count as proxy for checkpoints
        // TODO: Get actual checkpoint counts from linter
        var checkpointMap = new EnumMap<ScoreCategory, Integer>(ScoreCategory.class);
        for (var category : ScoreCategory.values()) {
            var violations = diagnostics.stream()
                                        .filter(d -> RuleCategoryMapping.categoryFor(d.ruleId()) == category)
                                        .count();
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
        for (var category : ScoreCategory.values()) {
            var categoryScore = breakdown.get(category);
            weightedSum += categoryScore.score() * category.weightFraction();
        }
        return (int) Math.round(weightedSum);
    }
}
