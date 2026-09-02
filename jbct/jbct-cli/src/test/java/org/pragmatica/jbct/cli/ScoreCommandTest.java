package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.pragmatica.jbct.score.DensityGate;
import org.pragmatica.jbct.score.ScoreCategory;
import org.pragmatica.jbct.score.ScoreReport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;


/// End-to-end gate for `jbct score`: the wiring from the command's `--format` and `--max-density`
/// options to the emitted bytes and the process exit code.
///
/// Both formats come from [ScoreReport], and nothing but these tests proves the command still
/// reaches the right renderer, still keeps the machine-readable stream clean of the collector's
/// diagnostics, and still gates in the right direction — which is now the *opposite* direction:
/// density is lower-is-better, so the gate fails ABOVE its threshold where the removed
/// `--baseline` failed below it.
///
/// Densities are never hard-coded: the expected density is read back from the JSON run and reused
/// as the threshold, so the assertions stay valid as lint rules are added and the fixture's
/// density moves.
class ScoreCommandTest {
    private static final JsonMapper JSON = JsonMapper.builder()
                                                     .build();

    @TempDir
    Path sources;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void captureStreams() throws IOException {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, UTF_8));
        System.setErr(new PrintStream(err, true, UTF_8));
        Files.writeString(sources.resolve("Sample.java"),
                          """
                          package sample;

                          public class Sample {
                              public String find(String key) {
                                  if (key == null) {
                                      return null;
                                  }

                                  return key.trim();
                              }
                          }
                          """);
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private int run(String... args) {
        out.reset();
        err.reset();

        return new CommandLine(new ScoreCommand()).execute(args);
    }

    private String stdout() {
        return out.toString(UTF_8);
    }

    private String stderr() {
        return err.toString(UTF_8);
    }

    /// Total density of the fixture as the command itself reports it, read back from the JSON
    /// format so no test has to hard-code a number that lint-rule changes will move.
    private double reportedDensity() {
        run("--format", "json", sources.toString());

        return JSON.readTree(stdout())
                   .get("totalDensityPerKloc")
                   .doubleValue();
    }

    private static String threshold(double density) {
        return String.format(Locale.ROOT, "%.1f", density);
    }

    private static JsonNode entryFor(JsonNode breakdown, ScoreCategory category) {
        return breakdown.get(category.name()
                                     .toLowerCase(Locale.ROOT));
    }

    @Test
    void call_terminalFormat_printsTheDensityBox() {
        var exitCode = run(sources.toString());
        var lines = stdout().lines()
                            .toList();

        assertThat(exitCode).isZero();
        assertThat(lines.getFirst()).startsWith("╔");
        assertThat(lines.get(1)).contains(ScoreReport.HEADER_LABEL)
                                .contains(" LOC")
                                .contains("1 files");
        assertThat(lines).anyMatch(line -> line.startsWith("╚"));
        assertThat(lines.getLast()).isEqualTo(ScoreReport.ADVISORY_LEGEND);
    }

    @Test
    void call_terminalFormat_marksTheAdvisoryCategory() {
        run(sources.toString());
        var styleRows = stdout().lines()
                                .filter(line -> line.contains("STYLE"))
                                .toList();

        assertThat(styleRows).hasSize(1);
        assertThat(styleRows.getFirst()).contains(ScoreReport.ADVISORY_MARKER);
    }

    /// A density without its denominator is unreadable, so the box must always carry both.
    @Test
    void call_terminalFormat_everyDensity_carriesItsRawCounts() {
        run(sources.toString());
        var densityRows = stdout().lines()
                                  .filter(line -> line.contains(ScoreReport.DENSITY_UNIT))
                                  .toList();

        assertThat(densityRows).isNotEmpty()
                               .allMatch(line -> line.contains("violation"));
    }

    @Test
    void call_jsonFormat_emitsAParseableDocument() {
        var exitCode = run("--format", "json", sources.toString());
        var root = JSON.readTree(stdout());

        assertThat(exitCode).isZero();
        assertThat(root.get("totalDensityPerKloc")
                       .doubleValue()).isPositive();
        assertThat(root.get("totalViolations")
                       .intValue()).isPositive();
        assertThat(root.get("linesOfCode")
                       .intValue()).isEqualTo(9);
        assertThat(root.get("filesAnalyzed")
                       .intValue()).isEqualTo(1);
        assertThat(root.get("breakdown")
                       .size()).isEqualTo(ScoreCategory.values().length);
    }

    @Test
    void call_jsonFormat_breakdownCarriesDensityRawCountsAndAdvisoryPerCategory() {
        run("--format", "json", sources.toString());
        var breakdown = JSON.readTree(stdout())
                            .get("breakdown");

        for (var category : ScoreCategory.values()) {
            var entry = entryFor(breakdown, category);

            assertThat(entry).as("breakdown entry for %s", category)
                             .isNotNull();
            assertThat(entry.get("densityPerKloc")
                            .doubleValue()).isNotNegative();
            assertThat(entry.get("violations")
                            .intValue()).isNotNegative();
            assertThat(entry.get("errors")
                            .intValue()
                       + entry.get("warnings")
                              .intValue()
                       + entry.get("info")
                              .intValue()).isEqualTo(entry.get("violations")
                                                          .intValue());
            assertThat(entry.get("advisory")
                            .booleanValue()).isEqualTo(category.advisory());
        }
    }

    /// The total is the counted categories only — a consumer must be able to reproduce it from the
    /// breakdown rather than trust it.
    @Test
    void call_jsonFormat_totalViolations_excludeTheAdvisoryCategory() {
        run("--format", "json", sources.toString());
        var root = JSON.readTree(stdout());
        var counted = ScoreCategory.countedCategories()
                                   .stream()
                                   .mapToInt(category -> entryFor(root.get("breakdown"), category).get("violations")
                                                                                                  .intValue())
                                   .sum();

        assertThat(root.get("totalViolations")
                       .intValue()).isEqualTo(counted);
    }

    /// `--format json` is pipeable only while the collector's chatter stays on stderr.
    @Test
    void call_jsonFormat_keepsSkipDiagnosticsOffStdout() throws IOException {
        Files.writeString(sources.resolve("Excluded.java"), "package sample;\n\npublic class Excluded {}\n");
        var configPath = sources.resolve("jbct-excludes.toml");

        Files.writeString(configPath,
                          """
                          [files]
                          excludes = ["Excluded.java"]
                          """);
        run("--format", "json", "--config", configPath.toString(), sources.toString());

        assertThat(stderr()).contains("Skipping Excluded.java");
        assertThat(stdout()).doesNotContain("Skipping");
        assertThat(JSON.readTree(stdout())
                       .get("filesAnalyzed")
                       .intValue()).isEqualTo(1);
    }

    /// `--format badge` was a real format. Substituting a different one for it silently would hand
    /// a CI job the wrong bytes with a zero exit code.
    @Test
    void call_unknownFormat_failsWithAUsageError() {
        var exitCode = run("--format", "badge", sources.toString());

        assertThat(exitCode).isEqualTo(ScoreCommand.USAGE_ERROR);
        assertThat(stderr()).contains("Unknown format 'badge'")
                            .contains("terminal, json");
        assertThat(stdout()).isEmpty();
    }

    @Test
    void call_maxDensityAtDensity_succeeds() {
        var exitCode = run("--max-density", threshold(reportedDensity()), sources.toString());

        assertThat(exitCode).isZero();
        assertThat(stderr()).doesNotContain("exceeds maximum");
    }

    @Test
    void call_maxDensityAboveDensity_succeeds() {
        var exitCode = run("--max-density", threshold(reportedDensity() + 10.0), sources.toString());

        assertThat(exitCode).isZero();
        assertThat(stderr()).doesNotContain("exceeds maximum");
    }

    /// Lower is better, so the gate fires ABOVE the threshold — the opposite of `--baseline`.
    @Test
    void call_maxDensityBelowDensity_failsWithExitCodeOne() {
        var maximum = reportedDensity() - 0.1;
        var exitCode = run("--max-density", threshold(maximum), sources.toString());

        assertThat(exitCode).isOne();
        assertThat(stderr()).contains("exceeds maximum " + threshold(maximum) + ScoreReport.DENSITY_UNIT);
    }

    @Test
    void call_zeroMaxDensity_failsForAnyViolation() {
        var exitCode = run("--max-density", "0", sources.toString());

        assertThat(exitCode).isOne();
        assertThat(stderr()).contains("exceeds maximum");
    }

    /// The removed gate meant the opposite of the new one, so it must be rejected rather than
    /// ignored or re-read: a copied-forward CI snippet would otherwise silently assert the reverse.
    @Test
    void call_removedBaselineOption_failsWithMigrationGuidance() {
        var exitCode = run("--baseline", "70", sources.toString());

        assertThat(exitCode).isEqualTo(ScoreCommand.USAGE_ERROR);
        assertThat(stderr()).isEqualToIgnoringNewLines(DensityGate.REMOVED_BASELINE_MESSAGE);
        assertThat(stdout()).isEmpty();
    }

    @Test
    void call_removedBaselineShortOption_failsWithMigrationGuidance() {
        var exitCode = run("-b", "70", sources.toString());

        assertThat(exitCode).isEqualTo(ScoreCommand.USAGE_ERROR);
        assertThat(stderr()).contains(DensityGate.MAX_DENSITY_OPTION);
    }

    @Test
    void call_noJavaFiles_failsWithExitCodeOne() throws IOException {
        var empty = Files.createDirectory(sources.resolve("empty"));
        var exitCode = run(empty.toString());

        assertThat(exitCode).isOne();
        assertThat(stderr()).contains("No Java files found");
        assertThat(stdout()).isEmpty();
    }
}
