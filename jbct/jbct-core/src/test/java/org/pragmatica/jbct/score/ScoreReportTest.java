package org.pragmatica.jbct.score;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Output-surface gate for [ScoreReport].
///
/// Two properties are load-bearing rather than cosmetic. **A ratio never appears without its raw
/// counts**: density is unbounded and scales with a denominator that can be tiny, so a bare
/// "11.1/KLOC" is unreadable without the "1 violation in 90 lines" behind it. **An advisory
/// category is visibly outside the total**: a number that silently does not count is the defect
/// class the advisory category was introduced to remove.
class ScoreReportTest {
    private static final ScoreResult.CategoryScore CLEAN = ScoreResult.CategoryScore.categoryScore(0.0, 0, 0, 0, 0);

    /// Uniform report: every category measured identically, so the shared rendering is exercised
    /// once per category. Seven counted categories × 11 violations = 77, at 4821 LOC = 13.7/KLOC.
    private static ScoreResult uniformResult() {
        return ScoreResult.scoreResult(13.7, uniformBreakdown(), 38, 4_821);
    }

    private static Map<ScoreCategory, ScoreResult.CategoryScore> uniformBreakdown() {
        var breakdown = new EnumMap<ScoreCategory, ScoreResult.CategoryScore>(ScoreCategory.class);

        for (var category : ScoreCategory.values()) {
            breakdown.put(category, ScoreResult.CategoryScore.categoryScore(2.3, 11, 2, 8, 1));
        }

        return Map.copyOf(breakdown);
    }

    /// Report where a single category carries everything and the rest are clean.
    private static Map<ScoreCategory, ScoreResult.CategoryScore> breakdownWith(ScoreCategory category,
                                                                               ScoreResult.CategoryScore score) {
        var breakdown = new EnumMap<ScoreCategory, ScoreResult.CategoryScore>(ScoreCategory.class);

        for (var each : ScoreCategory.values()) {
            breakdown.put(each, CLEAN);
        }

        breakdown.put(category, score);

        return Map.copyOf(breakdown);
    }

    private static String lineFor(List<String> lines, String fragment) {
        return lines.stream()
                    .filter(line -> line.contains(fragment))
                    .findFirst()
                    .orElse("");
    }

    private static List<String> dividers(List<String> lines) {
        return lines.stream()
                    .filter(line -> line.startsWith("╠"))
                    .toList();
    }

    @Nested
    class Terminal {
        @Test
        void terminalLines_header_carriesTheDenominatorAndTheFileCount() {
            var lines = ScoreReport.terminalLines(uniformResult());

            assertThat(lines.get(1)).contains(ScoreReport.HEADER_LABEL)
                                    .contains("4821 LOC")
                                    .contains("38 files");
        }

        @Test
        void terminalLines_everyCategory_isRenderedWithItsDensityAndRawCounts() {
            var lines = ScoreReport.terminalLines(uniformResult());

            for (var category : ScoreCategory.values()) {
                assertThat(lineFor(lines, category.name().replace('_', ' ')))
                          .as("row for %s", category)
                          .contains("2.3" + ScoreReport.DENSITY_UNIT)
                          .contains("(11 violations: 2E 8W 1I)");
            }
        }

        /// The denominator can be tiny, so no density may ever be printed on its own.
        @Test
        void terminalLines_everyDensity_isAccompaniedByARawViolationCount() {
            var densityRows = ScoreReport.terminalLines(uniformResult())
                                         .stream()
                                         .filter(line -> line.contains(ScoreReport.DENSITY_UNIT))
                                         .toList();

            assertThat(densityRows).isNotEmpty()
                                   .allMatch(line -> line.contains("violation"));
        }

        @Test
        void terminalLines_total_reportsTheTotalDensityAndViolationCount() {
            var lines = ScoreReport.terminalLines(uniformResult());

            assertThat(lineFor(lines, ScoreReport.TOTAL_LABEL)).contains("13.7" + ScoreReport.DENSITY_UNIT)
                                                              .contains("(77 violations)");
        }

        @Test
        void terminalLines_advisoryCategory_isMarkedAndLegendExplainsIt() {
            var lines = ScoreReport.terminalLines(uniformResult());

            assertThat(lineFor(lines, "STYLE")).contains(ScoreReport.ADVISORY_MARKER);
            assertThat(lines).contains(ScoreReport.ADVISORY_LEGEND);
        }

        @Test
        void terminalLines_countedCategories_areNotMarkedAdvisory() {
            var lines = ScoreReport.terminalLines(uniformResult());

            assertThat(lineFor(lines, "RETURN TYPES")).doesNotContain(ScoreReport.ADVISORY_MARKER);
            assertThat(lineFor(lines, "LAMBDA COMPLIANCE")).doesNotContain(ScoreReport.ADVISORY_MARKER);
        }

        @Test
        void terminalLines_advisoryCategories_areSeparatedBelowTheTotal() {
            var lines = ScoreReport.terminalLines(uniformResult());

            assertThat(lines.indexOf(lineFor(lines, ScoreReport.TOTAL_LABEL)))
                      .isLessThan(lines.indexOf(lineFor(lines, "STYLE")));
            assertThat(dividers(lines)).hasSize(3);
            assertThat(lines.indexOf(dividers(lines).getLast()))
                      .isLessThan(lines.indexOf(lineFor(lines, "STYLE")));
        }

        /// An advisory total is a contradiction, so STYLE never appears above the TOTAL row.
        @Test
        void terminalLines_totalRow_countsOnlyTheCountedCategories() {
            var lines = ScoreReport.terminalLines(ScoreResult.scoreResult(0.0,
                                                                          breakdownWith(ScoreCategory.STYLE,
                                                                                        ScoreResult.CategoryScore
                                                                                                   .categoryScore(3.1,
                                                                                                                  15,
                                                                                                                  0,
                                                                                                                  15,
                                                                                                                  0)),
                                                                          38,
                                                                          4_821));

            assertThat(lineFor(lines, ScoreReport.TOTAL_LABEL)).contains("0.0" + ScoreReport.DENSITY_UNIT)
                                                              .contains("0 violations)");
            assertThat(lineFor(lines, "STYLE")).contains("3.1" + ScoreReport.DENSITY_UNIT)
                                               .contains("(15 violations: 0E 15W 0I)");
        }

        /// Counts sit in a column, so a one-digit category does not visually shrink next to a
        /// four-digit one.
        @Test
        void terminalLines_violationCounts_areRightAlignedAcrossRows() {
            var breakdown = new EnumMap<ScoreCategory, ScoreResult.CategoryScore>(ScoreCategory.class);

            for (var category : ScoreCategory.values()) {
                breakdown.put(category, CLEAN);
            }

            breakdown.put(ScoreCategory.RETURN_TYPES, ScoreResult.CategoryScore.categoryScore(2.3, 1_234, 0, 1_234, 0));
            var lines = ScoreReport.terminalLines(ScoreResult.scoreResult(2.3, Map.copyOf(breakdown), 1, 536_000));

            assertThat(lineFor(lines, "RETURN TYPES")).contains("(1234 violations: 0E 1234W 0I)");
            assertThat(lineFor(lines, "NULL SAFETY")).contains("(   0 violations: 0E 0W 0I)");
            assertThat(lineFor(lines, ScoreReport.TOTAL_LABEL)).contains("(1234 violations)");
        }

        @Test
        void terminalLines_box_keepsItsBordersAndAlignsThem() {
            var lines = ScoreReport.terminalLines(uniformResult());
            var width = lines.getFirst().length();

            assertThat(lines.getFirst()).startsWith("╔").endsWith("╗");
            assertThat(lines.get(lines.size() - 2)).startsWith("╚").endsWith("╝");
            assertThat(lines.getLast()).isEqualTo(ScoreReport.ADVISORY_LEGEND);
            assertThat(lines.subList(0, lines.size() - 1)).allMatch(line -> line.length() == width);
        }

        /// Density is unbounded, so a fixed-width box would eventually overflow its own border or
        /// truncate a count. Neither is acceptable: the box grows instead.
        @Test
        void terminalLines_hugeDensity_widensTheBoxInsteadOfBreakingIt() {
            var huge = ScoreResult.CategoryScore.categoryScore(123_456.7, 999_999, 999_999, 0, 0);
            var lines = ScoreReport.terminalLines(ScoreResult.scoreResult(123_456.7,
                                                                          breakdownWith(ScoreCategory.RETURN_TYPES,
                                                                                        huge),
                                                                          1,
                                                                          8_100));
            var width = lines.getFirst().length();

            assertThat(lineFor(lines, "RETURN TYPES")).contains("123456.7" + ScoreReport.DENSITY_UNIT)
                                                      .contains("(999999 violations: 999999E 0W 0I)");
            assertThat(width).isGreaterThan(ScoreReport.MIN_BOX_WIDTH);
            assertThat(lines.subList(0, lines.size() - 1)).allMatch(line -> line.length() == width);
        }

        @Test
        void terminalLines_smallModule_showsTheLargeRatioNextToItsSmallDenominator() {
            var single = ScoreResult.CategoryScore.categoryScore(11.1, 1, 0, 1, 0);
            var lines = ScoreReport.terminalLines(ScoreResult.scoreResult(11.1,
                                                                          breakdownWith(ScoreCategory.RETURN_TYPES,
                                                                                        single),
                                                                          1,
                                                                          90));

            assertThat(lines.get(1)).contains("90 LOC")
                                    .contains("1 files");
            assertThat(lineFor(lines, "RETURN TYPES")).contains("11.1" + ScoreReport.DENSITY_UNIT)
                                                      .contains("(1 violations: 0E 1W 0I)");
            assertThat(lineFor(lines, ScoreReport.TOTAL_LABEL)).contains("(1 violations)");
        }
    }

    @Nested
    class Json {
        @Test
        void jsonLines_document_isWellFormed() {
            var lines = ScoreReport.jsonLines(uniformResult());
            var entries = lines.stream()
                               .filter(line -> line.startsWith("    \""))
                               .toList();

            assertThat(lines).startsWith("{",
                                         "  \"linesOfCode\": 4821,",
                                         "  \"filesAnalyzed\": 38,",
                                         "  \"breakdown\": {");
            assertThat(lines).endsWith("  },",
                                       "  \"totalDensityPerKloc\": 13.7,",
                                       "  \"totalViolations\": 77",
                                       "}");
            assertThat(entries).hasSize(ScoreCategory.values().length);
            assertThat(entries.subList(0, entries.size() - 1)).allMatch(line -> line.endsWith(","));
            assertThat(entries.getLast()).doesNotEndWith(",");
        }

        @Test
        void jsonLines_everyCategory_carriesItsDensityRawCountsAndAdvisoryFlag() {
            var json = String.join("\n", ScoreReport.jsonLines(uniformResult()));

            assertThat(json).contains("\"return_types\": {\"densityPerKloc\": 2.3, \"violations\": 11, "
                                      + "\"errors\": 2, \"warnings\": 8, \"info\": 1, \"advisory\": false}")
                            .contains("\"style\": {\"densityPerKloc\": 2.3, \"violations\": 11, "
                                      + "\"errors\": 2, \"warnings\": 8, \"info\": 1, \"advisory\": true}");
        }

        @Test
        void jsonLines_advisoryFlag_isSetForAdvisoryCategoriesOnly() {
            var lines = ScoreReport.jsonLines(uniformResult());

            assertThat(lines).filteredOn(line -> line.contains("\"advisory\": true"))
                             .hasSize(ScoreCategory.advisoryCategories().size());
            assertThat(lines).filteredOn(line -> line.contains("\"advisory\": false"))
                             .hasSize(ScoreCategory.countedCategories().size());
        }

        /// The denominator is part of the document, not of the rendering: a consumer must be able
        /// to see how small the module was without re-deriving it.
        @Test
        void jsonLines_denominatorAndTotalCount_areAlwaysPresent() {
            var single = ScoreResult.CategoryScore.categoryScore(11.1, 1, 0, 1, 0);
            var json = String.join("\n",
                                   ScoreReport.jsonLines(ScoreResult.scoreResult(11.1,
                                                                                 breakdownWith(ScoreCategory.RETURN_TYPES,
                                                                                               single),
                                                                                 1,
                                                                                 90)));

            assertThat(json).contains("\"linesOfCode\": 90")
                            .contains("\"filesAnalyzed\": 1")
                            .contains("\"totalDensityPerKloc\": 11.1,")
                            .contains("\"totalViolations\": 1");
        }
    }

    @Nested
    class DensityFormat {
        @Test
        void density_value_isOneDecimalWithTheUnit() {
            assertThat(ScoreReport.density(2.3)).isEqualTo("2.3/KLOC");
            assertThat(ScoreReport.density(0.0)).isEqualTo("0.0/KLOC");
            assertThat(ScoreReport.density(1_234.5)).isEqualTo("1234.5/KLOC");
        }

        /// A comma-decimal default locale must not leak into the report or into the JSON, where it
        /// would produce an invalid document.
        @Test
        void density_decimalSeparator_isLocaleIndependent() {
            assertThat(ScoreReport.density(2.3)).contains(".")
                                                .doesNotContain(",");
        }
    }
}
