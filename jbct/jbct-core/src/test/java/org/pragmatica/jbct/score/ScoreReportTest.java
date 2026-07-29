package org.pragmatica.jbct.score;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Output-surface gate for [ScoreReport]. A category rendered as a bare number that
/// silently does not count toward the total is the defect class the advisory category was
/// introduced to remove, so both formats must mark advisory categories as such.
class ScoreReportTest {
    private static ScoreResult scoreResult() {
        var breakdown = new EnumMap<ScoreCategory, ScoreResult.CategoryScore>(ScoreCategory.class);

        for (var category : ScoreCategory.values()) {
            breakdown.put(category, ScoreResult.CategoryScore.categoryScore(60, 10, 4, 4.0));
        }

        return ScoreResult.scoreResult(60, breakdown, 7);
    }

    private static String lineFor(List<String> lines, String fragment) {
        return lines.stream()
                    .filter(line -> line.contains(fragment))
                    .findFirst()
                    .orElse("");
    }

    @Test
    void terminalLines_advisoryCategory_isMarkedAndLegendExplainsIt() {
        var lines = ScoreReport.terminalLines(scoreResult());

        assertThat(lineFor(lines, "STYLE")).contains(ScoreReport.ADVISORY_MARKER);
        assertThat(lines).contains(ScoreReport.ADVISORY_LEGEND);
    }

    @Test
    void terminalLines_weightedCategories_areNotMarkedAdvisory() {
        var lines = ScoreReport.terminalLines(scoreResult());

        assertThat(lineFor(lines, "RETURN TYPES")).doesNotContain(ScoreReport.ADVISORY_MARKER);
        assertThat(lineFor(lines, "LAMBDA COMPLIANCE")).doesNotContain(ScoreReport.ADVISORY_MARKER);
    }

    @Test
    void terminalLines_advisoryCategories_areSeparatedBelowTheWeightedBlock() {
        var lines = ScoreReport.terminalLines(scoreResult());

        assertThat(lines.indexOf(ScoreReport.DIVIDER)).isLessThan(lines.lastIndexOf(ScoreReport.DIVIDER));
        assertThat(lines.lastIndexOf(ScoreReport.DIVIDER)).isLessThan(lines.indexOf(lineFor(lines, "STYLE")));
    }

    @Test
    void terminalLines_everyCategory_isRendered() {
        var lines = ScoreReport.terminalLines(scoreResult());

        for (var category : ScoreCategory.values()) {
            assertThat(lineFor(lines, category.name().replace('_', ' '))).isNotEmpty();
        }
    }

    @Test
    void terminalLines_box_keepsItsBorders() {
        var lines = ScoreReport.terminalLines(scoreResult());

        assertThat(lines).startsWith(ScoreReport.TOP_BORDER);
        assertThat(lines.get(lines.size() - 2)).isEqualTo(ScoreReport.BOTTOM_BORDER);
        assertThat(lines.get(1)).contains("JBCT COMPLIANCE SCORE: 60/100");
    }

    @Test
    void jsonLines_everyCategory_carriesWeightAndAdvisoryFlag() {
        var json = String.join("\n", ScoreReport.jsonLines(scoreResult()));

        assertThat(json).contains("\"return_types\": {\"score\": 60, \"weight\": 25.0, \"advisory\": false}")
                        .contains("\"style\": {\"score\": 60, \"weight\": 0.0, \"advisory\": true}");
    }

    @Test
    void jsonLines_advisoryFlag_isSetForAdvisoryCategoriesOnly() {
        var lines = ScoreReport.jsonLines(scoreResult());

        assertThat(lines).filteredOn(line -> line.contains("\"advisory\": true"))
                         .hasSize(ScoreCategory.advisoryCategories().size());
        assertThat(lines).filteredOn(line -> line.contains("\"advisory\": false"))
                         .hasSize(ScoreCategory.weightedCategories().size());
    }

    @Test
    void jsonLines_document_isWellFormed() {
        var lines = ScoreReport.jsonLines(scoreResult());
        var entries = lines.stream()
                           .filter(line -> line.startsWith("    \""))
                           .toList();

        assertThat(lines).startsWith("{", "  \"score\": 60,", "  \"breakdown\": {");
        assertThat(lines).endsWith("  },", "  \"filesAnalyzed\": 7", "}");
        assertThat(entries).hasSize(ScoreCategory.values().length);
        assertThat(entries.subList(0, entries.size() - 1)).allMatch(line -> line.endsWith(","));
        assertThat(entries.getLast()).doesNotEndWith(",");
    }

    @Test
    void progressBar_percentage_fillsOneBlockPerFivePercent() {
        assertThat(ScoreReport.progressBar(100)).isEqualTo("█".repeat(20));
        assertThat(ScoreReport.progressBar(0)).isEqualTo("░".repeat(20));
        assertThat(ScoreReport.progressBar(50)).isEqualTo("█".repeat(10) + "░".repeat(10));
    }

    @Test
    void terminalLines_categoryRow_reportsTheBreakdownScore() {
        var breakdown = new EnumMap<ScoreCategory, ScoreResult.CategoryScore>(ScoreCategory.class);

        for (var category : ScoreCategory.values()) {
            breakdown.put(category, ScoreResult.CategoryScore.categoryScore(35, 10, 6, 6.0));
        }

        var lines = ScoreReport.terminalLines(ScoreResult.scoreResult(35, Map.copyOf(breakdown), 1));

        assertThat(lineFor(lines, "STYLE")).contains(" 35%");
    }
}
