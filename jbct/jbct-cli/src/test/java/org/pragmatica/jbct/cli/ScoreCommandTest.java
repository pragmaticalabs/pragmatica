package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

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


/// End-to-end gate for `jbct score`: the wiring from the command's `--format` and `--baseline`
/// options to the emitted bytes and the process exit code.
///
/// The three formats used to be rendered inside this command; they now come from [ScoreReport],
/// and nothing but these tests proves the command still reaches the right renderer, still keeps
/// the machine-readable stream clean of the collector's diagnostics, and still gates on the
/// baseline in the right direction.
///
/// Scores are never hard-coded: the expected score is read back from the JSON run and reused as
/// the baseline, so the assertions stay valid as lint rules are added and the fixture's score
/// moves.
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

    /// Score of the fixture as the command itself reports it, read back from the JSON format so
    /// no test has to hard-code a number that lint-rule changes will move.
    private int reportedScore() {
        run("--format", "json", sources.toString());

        return JSON.readTree(stdout())
                   .get("score")
                   .intValue();
    }

    private static JsonNode entryFor(JsonNode breakdown, ScoreCategory category) {
        return breakdown.get(category.name()
                                     .toLowerCase(Locale.ROOT));
    }

    @Test
    void call_terminalFormat_printsTheScoreBox() {
        var score = reportedScore();
        var exitCode = run(sources.toString());
        var lines = stdout().lines()
                            .toList();

        assertThat(exitCode).isZero();
        assertThat(lines.getFirst()).isEqualTo(ScoreReport.TOP_BORDER);
        assertThat(lines.get(1)).contains("JBCT COMPLIANCE SCORE: " + score + "/100");
        assertThat(lines).contains(ScoreReport.BOTTOM_BORDER);
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

    @Test
    void call_jsonFormat_emitsAParseableDocument() {
        var exitCode = run("--format", "json", sources.toString());
        var root = JSON.readTree(stdout());

        assertThat(exitCode).isZero();
        assertThat(root.get("score")
                       .intValue()).isBetween(0, 100);
        assertThat(root.get("filesAnalyzed")
                       .intValue()).isEqualTo(1);
        assertThat(root.get("breakdown")
                       .size()).isEqualTo(ScoreCategory.values().length);
    }

    @Test
    void call_jsonFormat_breakdownCarriesScoreWeightAndAdvisoryPerCategory() {
        run("--format", "json", sources.toString());
        var breakdown = JSON.readTree(stdout())
                            .get("breakdown");

        for (var category : ScoreCategory.values()) {
            var entry = entryFor(breakdown, category);

            assertThat(entry).as("breakdown entry for %s", category)
                             .isNotNull();
            assertThat(entry.get("score")
                            .intValue()).isBetween(0, 100);
            assertThat(entry.get("weight")
                            .doubleValue()).isEqualTo(category.weight());
            assertThat(entry.get("advisory")
                            .booleanValue()).isEqualTo(category.advisory());
        }
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

    @Test
    void call_badgeFormat_emitsTheShieldsSvg() {
        var score = reportedScore();
        var exitCode = run("--format", "badge", sources.toString());
        var badge = stdout();
        var lines = badge.lines()
                         .toList();

        assertThat(exitCode).isZero();
        assertThat(lines.getFirst()).isEqualTo("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"20\">");
        assertThat(lines).contains("    <text x=\"71.5\" y=\"14\">" + score + "/100</text>")
                         .anyMatch(line -> line.contains("<path fill=\"" + ScoreReport.badgeColor(score) + "\""));
        assertThat(badge).as("badge bytes, including the blank line closing the document")
                         .endsWith("</svg>\n\n");
    }

    @Test
    void call_baselineAtScore_succeeds() {
        var baseline = reportedScore();
        var exitCode = run("--baseline", String.valueOf(baseline), sources.toString());

        assertThat(exitCode).isZero();
        assertThat(stderr()).doesNotContain("below baseline");
    }

    @Test
    void call_baselineBelowScore_succeeds() {
        var exitCode = run("--baseline", "0", sources.toString());

        assertThat(exitCode).isZero();
        assertThat(stderr()).doesNotContain("below baseline");
    }

    @Test
    void call_baselineAboveScore_failsWithExitCodeOne() {
        var baseline = reportedScore() + 1;
        var exitCode = run("--baseline", String.valueOf(baseline), sources.toString());

        assertThat(exitCode).isOne();
        assertThat(stderr()).contains("below baseline " + baseline);
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
