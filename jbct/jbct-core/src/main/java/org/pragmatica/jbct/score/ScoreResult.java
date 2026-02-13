package org.pragmatica.jbct.score;

import java.util.Map;

/// Immutable result of JBCT compliance scoring.
public record ScoreResult(int overall,
                          Map<ScoreCategory, CategoryScore> breakdown,
                          int filesAnalyzed) {
    public static ScoreResult scoreResult(int overall,
                                          Map<ScoreCategory, CategoryScore> breakdown,
                                          int filesAnalyzed) {
        return new ScoreResult(overall, breakdown, filesAnalyzed);
    }

    /// Score for a single category.
    public record CategoryScore(int score,
                                int checkpoints,
                                int violations,
                                double weightedViolations) {
        public static CategoryScore categoryScore(int score,
                                                  int checkpoints,
                                                  int violations,
                                                  double weightedViolations) {
            return new CategoryScore(score, checkpoints, violations, weightedViolations);
        }
    }
}
