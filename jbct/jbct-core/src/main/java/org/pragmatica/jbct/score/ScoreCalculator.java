package org.pragmatica.jbct.score;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Measures JBCT violation density from lint diagnostics.
///
/// Formula:
/// ```
/// category_density = 1000 × violations[category] / lines_of_code
/// total_density    = 1000 × Σ violations[counted categories] / lines_of_code
/// ```
///
/// where `lines_of_code` is the physical non-blank line count of the analyzed files
/// ([org.pragmatica.jbct.shared.SourceFile#nonBlankLines()]). The unit is violations per 1000
/// lines; **lower is better** and the value is unbounded above, so there is no ceiling, no
/// percentage and no average to take.
///
/// This replaces a 0-100 "compliance score" whose denominator was derived from the violation
/// count itself (`checkpoints = violations × 1.1 + 10`), which made it degenerate: a
/// WARNING-only category asymptotically approached `100 × (1 - 1/1.1)` = 9 no matter how many
/// findings there were (2,272 warnings and 1,000,000 warnings both reported 9), and an ERROR-only
/// category saturated at 0 after ten violations. Density has a denominator that is independent of
/// the numerator, which is the whole point.
///
/// Two deliberate absences: there is no severity multiplier and no category weight. Both were
/// invisible judgements baked into one number. ERROR / WARNING / INFO are reported as raw counts
/// beside each density, and the total is a plain sum, not a weighted average. Advisory categories
/// ([ScoreCategory#advisory()]) are measured the same way and reported separately, so style
/// findings cannot inflate the headline.
public sealed interface ScoreCalculator permits ScoreCalculator.unused {
    record unused() implements ScoreCalculator {}

    Logger log = LoggerFactory.getLogger(ScoreCalculator.class);

    /// Lines per KLOC: the density denominator scale.
    double LINES_PER_KLOC = 1000.0;

    /// Measure violation density from a source scan.
    static ScoreResult calculate(SourceScan scan) {
        warnUnknownRules(scan.diagnostics());
        var breakdown = new EnumMap<ScoreCategory, ScoreResult.CategoryScore>(ScoreCategory.class);

        for (var category : ScoreCategory.values()) {
            breakdown.put(category, categoryScore(violationsIn(category, scan.diagnostics()), scan.linesOfCode()));
        }

        return ScoreResult.scoreResult(totalDensity(breakdown, scan.linesOfCode()),
                                       breakdown,
                                       scan.filesAnalyzed(),
                                       scan.linesOfCode());
    }

    /// Warn once per distinct unknown rule ID. Unknown diagnostics are excluded from the
    /// measurement rather than silently bucketed into a default category.
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
        log.warn("Lint rule '{}' has no score-category mapping and is excluded from the JBCT density report; "
                 + "add it to RuleCategoryMapping (categorized or intentionally uncategorized).", ruleId);
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

    private static ScoreResult.CategoryScore categoryScore(List<Diagnostic> violations, int linesOfCode) {
        return ScoreResult.CategoryScore.categoryScore(densityPerKloc(violations.size(), linesOfCode),
                                                       violations.size(),
                                                       countOf(violations, DiagnosticSeverity.ERROR),
                                                       countOf(violations, DiagnosticSeverity.WARNING),
                                                       countOf(violations, DiagnosticSeverity.INFO));
    }

    private static int countOf(List<Diagnostic> violations, DiagnosticSeverity severity) {
        return (int) violations.stream()
                               .filter(diagnostic -> diagnostic.severity() == severity)
                               .count();
    }

    private static double totalDensity(Map<ScoreCategory, ScoreResult.CategoryScore> breakdown, int linesOfCode) {
        return densityPerKloc(ScoreCategory.countedCategories()
                                           .stream()
                                           .mapToInt(category -> breakdown.get(category).violations())
                                           .sum(),
                              linesOfCode);
    }

    /// Violations per 1000 non-blank lines, rounded to one decimal — the same value the report
    /// prints and the density gate compares, so what an operator reads is exactly what fails a
    /// build.
    ///
    /// With no lines there is no denominator and the density is 0.0; the raw violation count, the
    /// LOC and the file count are reported next to every density, so that degenerate case is
    /// visible rather than mistaken for a clean measurement.
    static double densityPerKloc(int violations, int linesOfCode) {
        if (linesOfCode == 0) {
            return 0.0;
        }

        return Math.round(violations * LINES_PER_KLOC * 10.0 / linesOfCode) / 10.0;
    }
}
