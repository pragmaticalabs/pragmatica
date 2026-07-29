package org.pragmatica.jbct.score;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.function.Function.identity;


/// Renders a [ScoreResult] for human and machine consumption, one string per output line.
///
/// All three output formats live here — terminal box, JSON and SVG badge. The CLI `score`
/// command emits them to stdout and the Maven `score` goal emits the terminal box through
/// the Maven log, so the two surfaces cannot drift apart on how a score is presented.
///
/// Advisory categories ([ScoreCategory#advisory()], weight 0) are reported like any other
/// category but contribute nothing to `overall`. Both breakdown formats say so explicitly:
/// the terminal box separates them below a divider, tags each row with [#ADVISORY_MARKER]
/// and closes with [#ADVISORY_LEGEND]; the JSON gives every category its own `weight` and
/// `advisory` field, so a consumer never has to guess which numbers the total is made of.
/// The badge carries the overall score alone — no breakdown, hence no advisory question.
public sealed interface ScoreReport permits ScoreReport.unused {
    record unused() implements ScoreReport {}

    /// Shields-style badge colour bands, ordered from the highest threshold down: a score is
    /// coloured by the first band whose [#minScore] it reaches. [#RED] is the catch-all band,
    /// so every score a [ScoreCalculator] can produce (0-100) lands in exactly one band.
    enum BadgeColor {
        BRIGHT_GREEN(90, "brightgreen"),
        GREEN(75, "green"),
        YELLOW(60, "yellow"),
        ORANGE(50, "orange"),
        RED(0, "red");
        private final int minScore;
        private final String color;
        BadgeColor(int minScore, String color) {
            this.minScore = minScore;
            this.color = color;
        }
        /// Lowest score still coloured by this band.
        public int minScore() {
            return minScore;
        }
        /// Shields colour name used in the badge SVG.
        public String color() {
            return color;
        }
    }

    String TOP_BORDER = "╔═══════════════════════════════════════════════════╗";
    String DIVIDER = "╠═══════════════════════════════════════════════════╣";
    String BOTTOM_BORDER = "╚═══════════════════════════════════════════════════╝";
    String ADVISORY_MARKER = " (advisory)";
    String ADVISORY_LEGEND = "  (advisory) — weight 0: reported for visibility, NOT counted in the score above";
    int BAR_WIDTH = 20;

    /// Shields-style badge SVG: colour name, then the score twice (shadow text and face text).
    /// The template closes with a newline after `</svg>`, so [#badgeLines] ends with an empty
    /// entry and a redirected badge file keeps its trailing blank line.
    String BADGE_TEMPLATE = """
        <svg xmlns="http://www.w3.org/2000/svg" width="100" height="20">
          <linearGradient id="b" x2="0" y2="100%%">
            <stop offset="0" stop-color="#bbb" stop-opacity=".1"/>
            <stop offset="1" stop-opacity=".1"/>
          </linearGradient>
          <mask id="a">
            <rect width="100" height="20" rx="3" fill="#fff"/>
          </mask>
          <g mask="url(#a)">
            <path fill="#555" d="M0 0h45v20H0z"/>
            <path fill="%s" d="M45 0h55v20H45z"/>
            <path fill="url(#b)" d="M0 0h100v20H0z"/>
          </g>
          <g fill="#fff" text-anchor="middle" font-family="DejaVu Sans,Verdana,Geneva,sans-serif" font-size="11">
            <text x="22.5" y="15" fill="#010101" fill-opacity=".3">JBCT</text>
            <text x="22.5" y="14">JBCT</text>
            <text x="71.5" y="15" fill="#010101" fill-opacity=".3">%d/100</text>
            <text x="71.5" y="14">%d/100</text>
          </g>
        </svg>
        """;

    /// Terminal score box, one entry per output line.
    static List<String> terminalLines(ScoreResult score) {
        return Stream.of(Stream.of(TOP_BORDER, headerLine(score), DIVIDER),
                         ScoreCategory.weightedCategories()
                                      .stream()
                                      .map(category -> categoryRow(score, category, "")),
                         Stream.of(DIVIDER),
                         ScoreCategory.advisoryCategories()
                                      .stream()
                                      .map(category -> categoryRow(score, category, ADVISORY_MARKER)),
                         Stream.of(BOTTOM_BORDER, ADVISORY_LEGEND))
                     .flatMap(identity())
                     .toList();
    }

    /// JSON report, one entry per output line. Every category carries its own weight and
    /// advisory flag so the breakdown is self-describing.
    static List<String> jsonLines(ScoreResult score) {
        return Stream.of(Stream.of("{",
                                   String.format("  \"score\": %d,", score.overall()),
                                   "  \"breakdown\": {"),
                         categoryEntries(score),
                         Stream.of("  },",
                                   String.format("  \"filesAnalyzed\": %d", score.filesAnalyzed()),
                                   "}"))
                     .flatMap(identity())
                     .toList();
    }

    /// Progress bar of [#BAR_WIDTH] characters: one block per 5%.
    static String progressBar(int percent) {
        var filled = percent / 5;

        return "█".repeat(filled) + "░".repeat(BAR_WIDTH - filled);
    }

    /// Shields-style SVG badge, one entry per output line. The last entry is the empty line
    /// closing the document, so printing the entries reproduces the badge byte for byte.
    static List<String> badgeLines(ScoreResult score) {
        return List.of(BADGE_TEMPLATE.formatted(badgeColor(score.overall()), score.overall(), score.overall())
                                     .split("\n", -1));
    }

    /// Shields colour for a score: the first [BadgeColor] band the score reaches.
    static String badgeColor(int percent) {
        return Arrays.stream(BadgeColor.values())
                     .filter(band -> percent >= band.minScore())
                     .findFirst()
                     .map(BadgeColor::color)
                     .orElse(BadgeColor.RED.color());
    }

    private static String headerLine(ScoreResult score) {
        return String.format("║     JBCT COMPLIANCE SCORE: %d/100            ║", score.overall());
    }

    private static String categoryRow(ScoreResult score, ScoreCategory category, String marker) {
        var percent = score.breakdown().get(category).score();

        return String.format("║  %-18s %s %3d%%    ║", categoryLabel(category) + marker, progressBar(percent), percent);
    }

    private static String categoryLabel(ScoreCategory category) {
        return category.name().replace('_', ' ');
    }

    private static Stream<String> categoryEntries(ScoreResult score) {
        var categories = ScoreCategory.values();

        return IntStream.range(0, categories.length)
                        .mapToObj(index -> categoryEntry(score, categories[index], index < categories.length - 1));
    }

    private static String categoryEntry(ScoreResult score, ScoreCategory category, boolean hasNext) {
        return String.format(Locale.ROOT,
                             "    \"%s\": {\"score\": %d, \"weight\": %.1f, \"advisory\": %b}%s",
                             category.name().toLowerCase(Locale.ROOT),
                             score.breakdown().get(category).score(),
                             category.weight(),
                             category.advisory(),
                             hasNext
                             ? ","
                             : "");
    }
}
