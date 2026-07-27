package org.pragmatica.jbct.score;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Output-surface gate for [ScoreReport]. A category rendered as a bare number that
/// silently does not count toward the total is the defect class the advisory category was
/// introduced to remove, so both breakdown formats must mark advisory categories as such.
/// The badge is pinned as a golden document: it is a published artifact, so its bytes are
/// part of the contract.
class ScoreReportTest {
    private static ScoreResult scoreResult() {
        return scoreResult(60);
    }

    private static ScoreResult scoreResult(int overall) {
        var breakdown = new EnumMap<ScoreCategory, ScoreResult.CategoryScore>(ScoreCategory.class);

        for (var category : ScoreCategory.values()) {
            breakdown.put(category, ScoreResult.CategoryScore.categoryScore(60, 10, 4, 4.0));
        }

        return ScoreResult.scoreResult(overall, breakdown, 7);
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

    /// Golden badge document. The badge is a published artifact — README-embedded, committed
    /// to repositories — so every byte of it is pinned here: a silent change to the SVG is a
    /// regression for every consumer, not a rendering detail.
    @Test
    void badgeLines_document_matchesTheShieldsBadgeByteForByte() {
        var expected = """
            <svg xmlns="http://www.w3.org/2000/svg" width="100" height="20">
              <linearGradient id="b" x2="0" y2="100%">
                <stop offset="0" stop-color="#bbb" stop-opacity=".1"/>
                <stop offset="1" stop-opacity=".1"/>
              </linearGradient>
              <mask id="a">
                <rect width="100" height="20" rx="3" fill="#fff"/>
              </mask>
              <g mask="url(#a)">
                <path fill="#555" d="M0 0h45v20H0z"/>
                <path fill="yellow" d="M45 0h55v20H45z"/>
                <path fill="url(#b)" d="M0 0h100v20H0z"/>
              </g>
              <g fill="#fff" text-anchor="middle" font-family="DejaVu Sans,Verdana,Geneva,sans-serif" font-size="11">
                <text x="22.5" y="15" fill="#010101" fill-opacity=".3">JBCT</text>
                <text x="22.5" y="14">JBCT</text>
                <text x="71.5" y="15" fill="#010101" fill-opacity=".3">60/100</text>
                <text x="71.5" y="14">60/100</text>
              </g>
            </svg>
            """;

        assertThat(String.join("\n", ScoreReport.badgeLines(scoreResult()))).isEqualTo(expected);
    }

    @Test
    void badgeLines_trailingBlankLine_closesTheDocument() {
        var lines = ScoreReport.badgeLines(scoreResult());

        assertThat(lines.getFirst()).startsWith("<svg ");
        assertThat(lines.get(lines.size() - 2)).isEqualTo("</svg>");
        assertThat(lines.getLast())
                  .as("the template closes with a newline, so a redirected badge file keeps its trailing blank line")
                  .isEmpty();
    }

    @Test
    void badgeLines_fillColor_followsTheScoreBand() {
        assertThat(lineFor(ScoreReport.badgeLines(scoreResult(95)), "M45 0h55v20H45z")).contains("fill=\"brightgreen\"");
        assertThat(lineFor(ScoreReport.badgeLines(scoreResult(20)), "M45 0h55v20H45z")).contains("fill=\"red\"");
    }

    @Test
    void badgeColor_bandBoundaries_matchShieldsThresholds() {
        assertThat(ScoreReport.badgeColor(100)).isEqualTo("brightgreen");
        assertThat(ScoreReport.badgeColor(90)).isEqualTo("brightgreen");
        assertThat(ScoreReport.badgeColor(89)).isEqualTo("green");
        assertThat(ScoreReport.badgeColor(75)).isEqualTo("green");
        assertThat(ScoreReport.badgeColor(74)).isEqualTo("yellow");
        assertThat(ScoreReport.badgeColor(60)).isEqualTo("yellow");
        assertThat(ScoreReport.badgeColor(59)).isEqualTo("orange");
        assertThat(ScoreReport.badgeColor(50)).isEqualTo("orange");
        assertThat(ScoreReport.badgeColor(49)).isEqualTo("red");
        assertThat(ScoreReport.badgeColor(0)).isEqualTo("red");
    }

    @Test
    void badgeColor_everyScore_getsExactlyOneBandColor() {
        var bandColors = Arrays.stream(ScoreReport.BadgeColor.values())
                               .map(ScoreReport.BadgeColor::color)
                               .toList();

        assertThat(IntStream.rangeClosed(0, 100)
                            .mapToObj(ScoreReport::badgeColor)
                            .distinct()
                            .toList())
                  .as("every band must be reachable from a real score, and no colour may come from outside the table")
                  .containsExactlyInAnyOrderElementsOf(bandColors);
    }
}
