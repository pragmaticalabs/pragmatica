package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;


/// End-to-end gate for `jbct shape-census`: the wiring from options to emitted bytes.
///
/// The classifier itself is covered by `MethodShapeClassifierTest` and the aggregation by
/// `ShapeCensusTest`; what only these tests can see is that the command reaches them at all, that
/// `--format json` yields something a machine can parse, and that an unparseable file is COUNTED
/// rather than silently dropped from the denominator.
///
/// That last one is the reason this command folds per-file reports itself instead of calling
/// `ShapeCensus.census(Collection)`: the collection entry point contributes nothing for a file that
/// fails to parse, so a census over a stranger's code could report a confident histogram over half
/// the tree. Counts are never hard-coded against the classifier's verdicts — the fixtures assert
/// relationships (total > 0, histogram sums to total) so the suite survives classifier changes.
class ShapeCensusCommandTest {
    private static final JsonMapper JSON = JsonMapper.builder()
                                                     .build();

    @TempDir
    Path sources;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void captureStreams() throws Exception {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, UTF_8));
        System.setErr(new PrintStream(err, true, UTF_8));
        Files.writeString(sources.resolve("Sample.java"),
                          """
                          package sample;

                          public class Sample {
                              String leaf(String value) {
                                  return value.trim();
                              }

                              String sequencer(String value) {
                                  return parse(value).flatMap(this::widen)
                                                     .map(String::valueOf)
                                                     .or("");
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
        return new CommandLine(new ShapeCensusCommand()).execute(args);
    }

    @Test
    void shapeCensus_rendersAHistogram_inTextForm() {
        assertThat(run(sources.toString())).isZero();
        assertThat(out.toString(UTF_8))
                  .contains("Method-shape census")
                  .contains("LEAF")
                  .contains("UNCLASSIFIED")
                  .contains("residual (MIXED+UNCLASSIFIED)")
                  .contains("files parsed: 1");
    }

    @Test
    void shapeCensus_emitsParseableJson_whoseHistogramSumsToTheTotal() {
        assertThat(run("--format", "json", sources.toString())).isZero();

        var root = JSON.readTree(out.toString(UTF_8));
        var histogram = root.get("histogram");
        var sum = 0;

        for (var name : histogram.propertyNames()) {
            sum += histogram.get(name).asInt();
        }

        assertThat(root.get("totalMethods").asInt())
                  .as("the histogram must account for every counted method")
                  .isEqualTo(sum)
                  .isPositive();
        assertThat(root.get("parseErrors").asInt()).isZero();
        assertThat(root.get("filesParsed").asInt()).isEqualTo(1);
    }

    /// The property the command exists to protect: a file that does not parse is REPORTED, so the
    /// denominator cannot shrink without the operator noticing.
    @Test
    void shapeCensus_countsUnparseableFiles_ratherThanDroppingThem() throws Exception {
        Files.writeString(sources.resolve("Broken.java"), "package sample; class Broken { this is not java (((\n");

        assertThat(run("--format", "json", sources.toString())).isZero();

        var root = JSON.readTree(out.toString(UTF_8));

        assertThat(root.get("parseErrors").asInt())
                  .as("an unparseable file must be counted, not silently skipped")
                  .isEqualTo(1);
        assertThat(root.get("filesParsed").asInt())
                  .as("the parseable file is still measured")
                  .isEqualTo(1);
        assertThat(err.toString(UTF_8)).contains("Broken.java");
    }

    @Test
    void shapeCensus_rejectsAnUnknownFormat_ratherThanSubstitutingADefault() {
        assertThat(run("--format", "badge", sources.toString()))
                  .as("a format that was never supported must fail loudly, not render as text")
                  .isEqualTo(ShapeCensusCommand.USAGE_ERROR);
        assertThat(err.toString(UTF_8)).contains("Unknown format 'badge'");
    }

    @Test
    void shapeCensus_reportsNoFilesFound_withoutClaimingAnEmptyCensus() {
        assertThat(run(sources.resolve("does-not-exist").toString())).isEqualTo(1);
        assertThat(out.toString(UTF_8))
                  .as("an empty run must not print a zero histogram that reads as a measured result")
                  .doesNotContain("Method-shape census");
    }
}
