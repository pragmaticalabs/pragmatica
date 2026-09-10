package org.pragmatica.jbct.score;

import java.util.Map;

import org.pragmatica.jbct.shared.AnalysisCoverage;


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
/// [#filesUnanalyzed] is part of the result for the same reason the denominators are: a density
/// measured over 1 of 2 files is not the density of 2 files, and a consumer that cannot see the
/// difference will read a clean number as a clean verdict (#977).
public record ScoreResult(double totalDensityPerKloc,
                          Map<ScoreCategory, CategoryScore> breakdown,
                          int filesAnalyzed,
                          int linesOfCode,
                          int filesUnanalyzed) {
    public static ScoreResult scoreResult(double totalDensityPerKloc,
                                          Map<ScoreCategory, CategoryScore> breakdown,
                                          int filesAnalyzed,
                                          int linesOfCode,
                                          int filesUnanalyzed) {
        return new ScoreResult(totalDensityPerKloc, breakdown, filesAnalyzed, linesOfCode, filesUnanalyzed);
    }

    /// A measurement over a fully-analysed file set. Asserts complete coverage by construction —
    /// [ScoreCalculator] carries the real count through from the scan.
    public static ScoreResult scoreResult(double totalDensityPerKloc,
                                          Map<ScoreCategory, CategoryScore> breakdown,
                                          int filesAnalyzed,
                                          int linesOfCode) {
        return scoreResult(totalDensityPerKloc, breakdown, filesAnalyzed, linesOfCode, 0);
    }

    /// What this measurement can and cannot speak for.
    public AnalysisCoverage coverage() {
        return AnalysisCoverage.analysisCoverage(filesAnalyzed + filesUnanalyzed, filesUnanalyzed);
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
