package org.pragmatica.jbct.score;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.function.Function.identity;


/// Renders a [ScoreResult] for human and machine consumption, one string per output line.
///
/// Both output formats live here — terminal box and JSON. The CLI `score` command emits them to
/// stdout and the Maven `score` goal emits the terminal box through the Maven log, so the two
/// surfaces cannot drift apart on how a measurement is presented.
///
/// Two properties are structural, not cosmetic:
///
///   - **No ratio is ever shown alone.** Every density is followed by the raw counts behind it,
///     and the header carries the LOC and file count that every density on the report divides by.
///     A 90-line module turns one violation into "11.1/KLOC" purely by denominator scaling, so
///     hiding a small denominator would make the report actively misleading.
///   - **Advisory categories are visibly outside the total.** They sit below a divider, are
///     tagged with [#ADVISORY_MARKER], and the box closes with [#ADVISORY_LEGEND]; in JSON every
///     entry carries its own `advisory` flag. A number that silently does not count toward the
///     total is the defect the advisory category was introduced to remove.
///
/// The box is sized to its widest row rather than to a fixed width: density is unbounded, so a
/// fixed box would eventually either overflow its border or truncate a count.
public sealed interface ScoreReport permits ScoreReport.unused {
    record unused() implements ScoreReport {}

    String HEADER_LABEL = "JBCT DENSITY";
    String TOTAL_LABEL = "TOTAL";
    String ADVISORY_MARKER = " (advisory)";
    String ADVISORY_LEGEND = "  (advisory) — reported for visibility, NOT counted in the TOTAL above";
    String DENSITY_UNIT = "/KLOC";

    /// Minimum interior width of the box, so a small report keeps the familiar proportions.
    int MIN_BOX_WIDTH = 51;

    /// Blanks between the border and the text on either side.
    int BOX_PADDING = 2;

    /// One breakdown line before column alignment: the label, its density, and the raw counts the
    /// density came from. Held apart so the three columns can be padded to a common width.
    record Row(String label, String density, String counts) {}

    /// Terminal report box, one entry per output line.
    static List<String> terminalLines(ScoreResult score) {
        var countWidth = violationCountWidth(score);

        return box(headerLine(score),
                   List.of(rowsFor(score, ScoreCategory.countedCategories(), "", countWidth),
                           List.of(totalRow(score, countWidth)),
                           rowsFor(score, ScoreCategory.advisoryCategories(), ADVISORY_MARKER, countWidth)));
    }

    /// JSON report, one entry per output line. Every category carries its own raw counts and its
    /// advisory flag, so the breakdown is self-describing and the total is reproducible from it.
    static List<String> jsonLines(ScoreResult score) {
        return Stream.of(Stream.of("{",
                                   String.format(Locale.ROOT, "  \"linesOfCode\": %d,", score.linesOfCode()),
                                   String.format(Locale.ROOT, "  \"filesAnalyzed\": %d,", score.filesAnalyzed()),
                                   "  \"breakdown\": {"),
                         categoryEntries(score),
                         Stream.of("  },",
                                   String.format(Locale.ROOT,
                                                 "  \"totalDensityPerKloc\": %.1f,",
                                                 score.totalDensityPerKloc()),
                                   String.format(Locale.ROOT, "  \"totalViolations\": %d", score.totalViolations()),
                                   "}"))
                     .flatMap(identity())
                     .toList();
    }

    /// Density with its unit, e.g. `2.3/KLOC`.
    static String density(double densityPerKloc) {
        return String.format(Locale.ROOT, "%.1f", densityPerKloc) + DENSITY_UNIT;
    }

    private static String headerLine(ScoreResult score) {
        return String.format(Locale.ROOT,
                             "%s — %d LOC, %d files",
                             HEADER_LABEL,
                             score.linesOfCode(),
                             score.filesAnalyzed());
    }

    private static List<Row> rowsFor(ScoreResult score, List<ScoreCategory> categories, String marker, int countWidth) {
        return categories.stream()
                         .map(category -> categoryRow(score, category, marker, countWidth))
                         .toList();
    }

    private static Row categoryRow(ScoreResult score, ScoreCategory category, String marker, int countWidth) {
        var categoryScore = score.breakdown().get(category);

        return new Row(categoryLabel(category) + marker,
                       density(categoryScore.densityPerKloc()),
                       severityCounts(categoryScore, countWidth));
    }

    private static Row totalRow(ScoreResult score, int countWidth) {
        return new Row(TOTAL_LABEL,
                       density(score.totalDensityPerKloc()),
                       String.format(Locale.ROOT, "(%" + countWidth + "d violations)", score.totalViolations()));
    }

    /// Digits needed by the largest violation count on the report, so the counts of every row line
    /// up under each other instead of drifting with the size of the number.
    private static int violationCountWidth(ScoreResult score) {
        return Stream.concat(score.breakdown().values().stream().map(ScoreResult.CategoryScore::violations),
                             Stream.of(score.totalViolations()))
                     .mapToInt(violations -> String.valueOf(violations).length())
                     .max()
                     .orElse(1);
    }

    private static String severityCounts(ScoreResult.CategoryScore categoryScore, int countWidth) {
        return String.format(Locale.ROOT,
                             "(%" + countWidth + "d violations: %dE %dW %dI)",
                             categoryScore.violations(),
                             categoryScore.errors(),
                             categoryScore.warnings(),
                             categoryScore.info());
    }

    private static String categoryLabel(ScoreCategory category) {
        return category.name().replace('_', ' ');
    }

    /// Assemble the box: a header section followed by the non-empty row sections, each separated
    /// by a divider, with the advisory legend below the bottom border.
    private static List<String> box(String header, List<List<Row>> sections) {
        var populated = sections.stream()
                                .filter(section -> !section.isEmpty())
                                .toList();
        var rendered = renderSections(populated);
        var width = Math.max(MIN_BOX_WIDTH,
                             Stream.concat(Stream.of(header), rendered.stream().flatMap(List::stream))
                                   .mapToInt(String::length)
                                   .max()
                                   .orElse(0));

        return Stream.of(Stream.of(border("╔", "╗", width), boxed(header, width)),
                         rendered.stream().flatMap(section -> boxedSection(section, width)),
                         Stream.of(border("╚", "╝", width), ADVISORY_LEGEND))
                     .flatMap(identity())
                     .toList();
    }

    private static Stream<String> boxedSection(List<String> section, int width) {
        return Stream.concat(Stream.of(border("╠", "╣", width)),
                             section.stream().map(line -> boxed(line, width)));
    }

    /// Pad every column to the widest entry across all sections, so the columns line up even
    /// though each section is built separately.
    private static List<List<String>> renderSections(List<List<Row>> sections) {
        var rows = sections.stream().flatMap(List::stream).toList();
        var labelWidth = widthOf(rows, Row::label);
        var densityWidth = widthOf(rows, Row::density);

        return sections.stream()
                       .map(section -> renderSection(section, labelWidth, densityWidth))
                       .toList();
    }

    private static List<String> renderSection(List<Row> section, int labelWidth, int densityWidth) {
        return section.stream()
                      .map(row -> renderRow(row, labelWidth, densityWidth))
                      .toList();
    }

    private static String renderRow(Row row, int labelWidth, int densityWidth) {
        return String.format(Locale.ROOT,
                             "%-" + labelWidth + "s  %" + densityWidth + "s  %s",
                             row.label(),
                             row.density(),
                             row.counts());
    }

    private static int widthOf(List<Row> rows, Function<Row, String> column) {
        return rows.stream()
                   .map(column)
                   .mapToInt(String::length)
                   .max()
                   .orElse(0);
    }

    private static String border(String left, String right, int width) {
        return left + "═".repeat(width + BOX_PADDING) + right;
    }

    private static String boxed(String line, int width) {
        return String.format(Locale.ROOT, "║ %-" + width + "s ║", line);
    }

    private static Stream<String> categoryEntries(ScoreResult score) {
        var categories = ScoreCategory.values();

        return IntStream.range(0, categories.length)
                        .mapToObj(index -> categoryEntry(score, categories[index], index < categories.length - 1));
    }

    private static String categoryEntry(ScoreResult score, ScoreCategory category, boolean hasNext) {
        var categoryScore = score.breakdown().get(category);

        return String.format(Locale.ROOT,
                             "    \"%s\": {\"densityPerKloc\": %.1f, \"violations\": %d, \"errors\": %d, "
                             + "\"warnings\": %d, \"info\": %d, \"advisory\": %b}%s",
                             category.name().toLowerCase(Locale.ROOT),
                             categoryScore.densityPerKloc(),
                             categoryScore.violations(),
                             categoryScore.errors(),
                             categoryScore.warnings(),
                             categoryScore.info(),
                             category.advisory(),
                             hasNext
                             ? ","
                             : "");
    }
}
