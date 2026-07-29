package org.pragmatica.jbct.score;

import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.function.Function.identity;


/// Renders a [ScoreResult] for human and machine consumption, one string per output line.
///
/// Both the CLI `score` command and the Maven `score` goal emit exactly these lines — the
/// CLI to stdout, the goal through the Maven log — so the two surfaces cannot drift apart
/// on how advisory categories are presented.
///
/// Advisory categories ([ScoreCategory#advisory()], weight 0) are reported like any other
/// category but contribute nothing to `overall`. Both formats say so explicitly: the
/// terminal box separates them below a divider, tags each row with [#ADVISORY_MARKER] and
/// closes with [#ADVISORY_LEGEND]; the JSON gives every category its own `weight` and
/// `advisory` field, so a consumer never has to guess which numbers the total is made of.
public sealed interface ScoreReport permits ScoreReport.unused {
    record unused() implements ScoreReport {}

    String TOP_BORDER = "╔═══════════════════════════════════════════════════╗";
    String DIVIDER = "╠═══════════════════════════════════════════════════╣";
    String BOTTOM_BORDER = "╚═══════════════════════════════════════════════════╝";
    String ADVISORY_MARKER = " (advisory)";
    String ADVISORY_LEGEND = "  (advisory) — weight 0: reported for visibility, NOT counted in the score above";
    int BAR_WIDTH = 20;

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
