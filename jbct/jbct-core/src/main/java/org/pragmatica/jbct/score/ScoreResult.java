package org.pragmatica.jbct.score;

import java.util.Map;


/// Immutable result of a JBCT violation-density measurement.
///
/// Every ratio in here is reported next to the raw counts it was computed from — a density alone
/// is unreadable, because a single violation in a 90-line file is "11.1/KLOC" purely by
/// denominator scaling. [#linesOfCode] and [#filesAnalyzed] are therefore part of the result, not
/// of the rendering, so no consumer can show a ratio without its denominator.
///
/// [#totalDensityPerKloc] covers the counted categories only: advisory categories
/// ([ScoreCategory#advisory()]) are measured and reported separately. Densities share one
/// denominator, so the total is the plain sum of the counted category densities — there is no
/// average and no weighting.
public record ScoreResult(double totalDensityPerKloc,
                          Map<ScoreCategory, CategoryScore> breakdown,
                          int filesAnalyzed,
                          int linesOfCode) {
    public static ScoreResult scoreResult(double totalDensityPerKloc,
                                          Map<ScoreCategory, CategoryScore> breakdown,
                                          int filesAnalyzed,
                                          int linesOfCode) {
        return new ScoreResult(totalDensityPerKloc, breakdown, filesAnalyzed, linesOfCode);
    }

    /// Violations behind [#totalDensityPerKloc]: counted categories only, advisory excluded.
    public int totalViolations() {
        return ScoreCategory.countedCategories()
                            .stream()
                            .mapToInt(category -> breakdown.get(category).violations())
                            .sum();
    }

    /// Measurement for a single category: the density and every raw count it was derived from.
    ///
    /// Severity is carried as three plain counts rather than as a multiplier folded into
    /// [#densityPerKloc], so an ERROR stays visibly an error instead of becoming an invisible
    /// coefficient.
    public record CategoryScore(double densityPerKloc, int violations, int errors, int warnings, int info) {
        public static CategoryScore categoryScore(double densityPerKloc,
                                                  int violations,
                                                  int errors,
                                                  int warnings,
                                                  int info) {
            return new CategoryScore(densityPerKloc, violations, errors, warnings, info);
        }
    }
}
