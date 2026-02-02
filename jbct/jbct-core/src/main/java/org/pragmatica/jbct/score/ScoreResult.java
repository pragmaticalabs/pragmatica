package org.pragmatica.jbct.score;

import java.util.Map;

/**
 * Immutable result of JBCT compliance scoring.
 */
public record ScoreResult(int overall,
                          Map<ScoreCategory, CategoryScore> breakdown,
                          int filesAnalyzed) {
    /**
     * Score for a single category.
     */
    public record CategoryScore(int score,
                                int checkpoints,
                                int violations,
                                double weightedViolations) {}
}
