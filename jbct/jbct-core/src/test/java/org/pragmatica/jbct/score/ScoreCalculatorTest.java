package org.pragmatica.jbct.score;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/// Arithmetic gate for [ScoreCalculator].
///
/// The metric this replaced derived its denominator from its own numerator
/// (`checkpoints = violations × 1.1 + 10`), so it collapsed: a WARNING-only category approached
/// `100 × (1 - 1/1.1)` = 9 and stayed there for any violation count, and an ERROR-only category
/// hit 0 at ten violations. Density's denominator is the measured source, so the tests that matter
/// most here are the ones proving the number still moves when the violation count moves, and that
/// nothing invisible (a severity multiplier, a category weight) is folded into it.
class ScoreCalculatorTest {
    private static final int ONE_KLOC = 1000;

    private static Diagnostic diagnostic(String ruleId, DiagnosticSeverity severity) {
        return Diagnostic.diagnostic(ruleId, severity, "Test.java", 1, 1, "test", "test");
    }

    private static List<Diagnostic> diagnostics(String ruleId, DiagnosticSeverity severity, int count) {
        return IntStream.range(0, count)
                        .mapToObj(_ -> diagnostic(ruleId, severity))
                        .toList();
    }

    private static ScoreResult calculate(List<Diagnostic> diagnostics, int linesOfCode, int filesAnalyzed) {
        return ScoreCalculator.calculate(new SourceScan(diagnostics, linesOfCode, filesAnalyzed));
    }

    private static double returnTypesDensity(int violations, int linesOfCode) {
        return calculate(diagnostics("JBCT-RET-01", DiagnosticSeverity.WARNING, violations), linesOfCode, 1)
                   .breakdown()
                   .get(ScoreCategory.RETURN_TYPES)
                   .densityPerKloc();
    }

    @Nested
    class Arithmetic {
        @Test
        void calculate_violationsPerThousandLines_isTheReportedDensity() {
            assertThat(returnTypesDensity(10, ONE_KLOC)).isCloseTo(10.0, within(1e-9));
            assertThat(returnTypesDensity(23, 10_000)).isCloseTo(2.3, within(1e-9));
            assertThat(returnTypesDensity(0, ONE_KLOC)).isZero();
        }

        /// A small module is exactly where a ratio misleads, so the arithmetic must be plain and
        /// the raw counts must survive: one violation in 90 lines really is 11.1/KLOC.
        @Test
        void calculate_smallModule_reportsTheLargeRatioWithItsRawCounts() {
            var score = calculate(diagnostics("JBCT-RET-01", DiagnosticSeverity.WARNING, 1), 90, 1);

            assertThat(score.totalDensityPerKloc()).isCloseTo(11.1, within(1e-9));
            assertThat(score.totalViolations()).isEqualTo(1);
            assertThat(score.linesOfCode()).isEqualTo(90);
            assertThat(score.filesAnalyzed()).isEqualTo(1);
        }

        @Test
        void calculate_density_isRoundedToOneDecimal() {
            assertThat(returnTypesDensity(1, 3)).isCloseTo(333.3, within(1e-9));
            assertThat(returnTypesDensity(1, 7)).isCloseTo(142.9, within(1e-9));
        }

        @Test
        void calculate_noLines_reportsZeroDensityRatherThanDividingByZero() {
            var score = calculate(diagnostics("JBCT-RET-01", DiagnosticSeverity.WARNING, 3), 0, 0);

            assertThat(score.totalDensityPerKloc()).isZero();
            assertThat(score.totalViolations()).isEqualTo(3);
            assertThat(score.linesOfCode()).isZero();
        }

        /// The defect that ended the 0-100 score: 2,272 warnings and 1,000,000 warnings both
        /// reported 9. Density has to separate them by three orders of magnitude.
        @Test
        void calculate_hugeViolationCounts_stayDistinguishable() {
            var small = returnTypesDensity(2_272, 100_000);
            var huge = returnTypesDensity(1_000_000, 100_000);

            assertThat(small).isCloseTo(22.7, within(1e-9));
            assertThat(huge).isCloseTo(10_000.0, within(1e-9));
            assertThat(huge).isGreaterThan(small);
        }

        /// The old metric saturated at 0 after ten errors; density keeps climbing.
        @Test
        void calculate_moreThanTenErrors_keepsIncreasing() {
            var ten = returnTypesDensity(10, ONE_KLOC);
            var hundred = returnTypesDensity(100, ONE_KLOC);

            assertThat(hundred).isGreaterThan(ten);
            assertThat(hundred).isCloseTo(ten * 10.0, within(1e-9));
        }

        @Test
        void calculate_scanCounts_areCarriedIntoTheResult() {
            var score = calculate(List.of(), 4_821, 38);

            assertThat(score.linesOfCode()).isEqualTo(4_821);
            assertThat(score.filesAnalyzed()).isEqualTo(38);
        }
    }

    @Nested
    class SeverityIsCountedNotWeighted {
        @Test
        void calculate_severity_doesNotChangeDensity() {
            var errors = returnTypesDensity(4, ONE_KLOC);
            var infos = calculate(diagnostics("JBCT-RET-01", DiagnosticSeverity.INFO, 4), ONE_KLOC, 1)
                            .breakdown()
                            .get(ScoreCategory.RETURN_TYPES)
                            .densityPerKloc();

            assertThat(infos).isCloseTo(errors, within(1e-9));
        }

        @Test
        void calculate_severity_isReportedAsRawCounts() {
            var mixed = new ArrayList<Diagnostic>(diagnostics("JBCT-RET-01", DiagnosticSeverity.ERROR, 2));

            mixed.addAll(diagnostics("JBCT-RET-01", DiagnosticSeverity.WARNING, 8));
            mixed.addAll(diagnostics("JBCT-RET-01", DiagnosticSeverity.INFO, 1));
            var categoryScore = calculate(mixed, ONE_KLOC, 1).breakdown()
                                                            .get(ScoreCategory.RETURN_TYPES);

            assertThat(categoryScore.violations()).isEqualTo(11);
            assertThat(categoryScore.errors()).isEqualTo(2);
            assertThat(categoryScore.warnings()).isEqualTo(8);
            assertThat(categoryScore.info()).isEqualTo(1);
            assertThat(categoryScore.densityPerKloc()).isCloseTo(11.0, within(1e-9));
        }
    }

    @Nested
    class AdvisoryExclusion {
        @Test
        void calculate_advisoryViolations_areExcludedFromTheTotal() {
            var counted = diagnostics("JBCT-RET-01", DiagnosticSeverity.WARNING, 4);
            var withStyle = new ArrayList<Diagnostic>(counted);

            withStyle.addAll(diagnostics("JBCT-ZONE-01", DiagnosticSeverity.INFO, 40));

            assertThat(calculate(withStyle, ONE_KLOC, 1).totalDensityPerKloc())
                      .isCloseTo(calculate(counted, ONE_KLOC, 1).totalDensityPerKloc(), within(1e-9));
        }

        @Test
        void calculate_advisoryViolations_areStillMeasuredInTheirOwnCategory() {
            var score = calculate(diagnostics("JBCT-ZONE-01", DiagnosticSeverity.INFO, 15), 4_821, 38);
            var style = score.breakdown().get(ScoreCategory.STYLE);

            assertThat(style.violations()).isEqualTo(15);
            assertThat(style.densityPerKloc()).isCloseTo(3.1, within(1e-9));
            assertThat(score.totalDensityPerKloc()).isZero();
            assertThat(score.totalViolations()).isZero();
        }

        @Test
        void calculate_total_isTheSumOfTheCountedCategoryDensities() {
            var mixed = new ArrayList<Diagnostic>(diagnostics("JBCT-RET-01", DiagnosticSeverity.WARNING, 11));

            mixed.addAll(diagnostics("JBCT-RET-03", DiagnosticSeverity.WARNING, 3));
            mixed.addAll(diagnostics("JBCT-ZONE-01", DiagnosticSeverity.INFO, 15));
            var score = calculate(mixed, ONE_KLOC, 1);
            var summed = ScoreCategory.countedCategories()
                                      .stream()
                                      .mapToDouble(category -> score.breakdown().get(category).densityPerKloc())
                                      .sum();

            assertThat(score.totalViolations()).isEqualTo(14);
            assertThat(score.totalDensityPerKloc()).isCloseTo(summed, within(1e-9))
                                                   .isCloseTo(14.0, within(1e-9));
        }
    }

    @Nested
    class UnknownRules {
        @Test
        void calculate_unknownRule_isExcludedFromEveryCategory() {
            var score = calculate(diagnostics("JBCT-RETIRED-99", DiagnosticSeverity.ERROR, 5), ONE_KLOC, 1);

            assertThat(score.breakdown().values()).allMatch(categoryScore -> categoryScore.violations() == 0);
            assertThat(score.totalDensityPerKloc()).isZero();
        }

        @Test
        void unknownRuleIds_repeatedUnknownId_reportedOnce() {
            assertThat(ScoreCalculator.unknownRuleIds(diagnostics("JBCT-RETIRED-99", DiagnosticSeverity.ERROR, 3)))
                      .containsExactly("JBCT-RETIRED-99");
        }
    }
}
