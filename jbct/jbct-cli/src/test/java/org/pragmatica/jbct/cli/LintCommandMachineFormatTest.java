package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;


/// #633 — `jbct lint --format json|sarif` must leave stdout holding ONE parseable document and
/// nothing else. It printed the human summary to stdout after the document (so `json.load` failed
/// with "Extra data"), and on a clean run it printed no document at all (so the same parser failed
/// on empty input). Operator-facing lines — summary, `-v` progress, "No Java files found." — belong
/// on stderr for the machine formats, the way `FileCollector`'s diagnostics already are and the way
/// `ScoreCommandTest` pins for `score --format json`. Text format is untouched: `LintCommandTest`
/// pins its summary on stdout.
class LintCommandMachineFormatTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String CLEAN = "public record Pure(String a) { public static Pure pure(String a){ return new Pure(a);} }\n";

    private static final String VIOLATING = """
                                            public class Warny {
                                                public String find(String k) {
                                                    if (k == null) {
                                                        return null;
                                                    }
                                                    return k.trim();
                                                }
                                            }
                                            """;

    private static final String BROKEN = "class { broken\n";

    @TempDir
    Path sources;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void captureStreams() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, UTF_8));
        System.setErr(new PrintStream(err, true, UTF_8));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(sources.resolve(name), content);
    }

    private int lint(String... flags) {
        out.reset();
        err.reset();
        var args = new java.util.ArrayList<>(java.util.List.of(flags));

        args.add(sources.toString());

        var command = new LintCommand();

        command.runSeam = run;

        return new CommandLine(command).execute(args.toArray(String[]::new));
    }

    private final CapturedRun run = new CapturedRun();

    /// stderr carries exactly the run's lines (all levels; DETAIL only when verbose); stdout is the document alone.
    private void assertStderrIsTheRunsLines(boolean verbose) {
        var expected = java.util.stream.Stream.concat(run.result().before().stream().filter(line -> line.channel() != org.pragmatica.jbct.lint.cst.RunOutcome.Channel.WITH_FINDINGS), run.result().after().stream())
                           .filter(line -> verbose || line.level() != org.pragmatica.jbct.lint.cst.RunOutcome.Level.DETAIL)
                           .map(org.pragmatica.jbct.lint.cst.RunOutcome.Line::text)
                           .toList();

        assertThat(err.toString(UTF_8).lines().toList()).isEqualTo(expected);
    }

    private JsonNode parsedStdout() {
        return JSON.readTree(out.toString(UTF_8));
    }

    @Test
    void json_withFindings_stdoutIsExactlyTheDocument_summaryGoesToStderr() throws IOException {
        write("Warny.java", VIOLATING);
        var exitCode = lint("--format", "json");

        assertThat(exitCode).isOne();
        var doc = parsedStdout();

        assertThat(doc.isObject()).as("stdout must parse as the one result document and nothing more: " + out).isTrue();
        assertThat(doc.path("diagnostics").size()).isPositive();
        assertStderrIsTheRunsLines(false);
        assertThat(java.util.stream.StreamSupport.stream(doc.path("outcome").spliterator(), false).map(JsonNode::asText).toList())
                  .isEqualTo(run.afterBody());
        assertThat(doc.path("collected").asInt()).isEqualTo(run.result().collected());
        assertThat(doc.path("examined").asInt()).isEqualTo(run.result().examined());
        assertThat(doc.path("skippedByMode").asInt()).isEqualTo(run.result().skippedByMode());
        assertThat(doc.path("diagnostics").size()).isEqualTo(run.result().findings().size());
    }

    @Test
    void json_cleanRun_stdoutIsACleanDocument_notNothing() throws IOException {
        write("Pure.java", CLEAN);
        var exitCode = lint("--format", "json");

        assertThat(exitCode).isZero();
        assertThat(parsedStdout().isObject()).as("a clean run must still emit a document a parser can load: " + out)
                  .isTrue();
        assertThat(parsedStdout().path("diagnostics").size()).isZero();
        assertThat(jsonIsClean(parsedStdout())).as("a complete clean run IS a clean scan: " + out).isTrue();
        assertStderrIsTheRunsLines(false);
    }

    @Test
    void sarif_stdoutIsOneSarifDocument_summaryGoesToStderr() throws IOException {
        write("Warny.java", VIOLATING);
        lint("--format", "sarif");
        var doc = parsedStdout();

        assertThat(doc.path("version").asText()).isEqualTo("2.1.0");
        assertThat(doc.path("runs").get(0).path("results").size()).isPositive();
        assertStderrIsTheRunsLines(false);
    }

    @Test
    void json_verbose_progressLinesGoToStderr() throws IOException {
        write("Pure.java", CLEAN);
        lint("--format", "json", "-v");
        assertThat(parsedStdout().isObject()).as("-v must not corrupt the document: " + out).isTrue();
        assertStderrIsTheRunsLines(true);
    }

    @Test
    void json_noJavaFiles_exitsTwo_documentSaysNothingWasExamined_noticeGoesToStderr() {
        var exitCode = lint("--format", "json");

        assertThat(exitCode).as("#1100: examining nothing is a coverage gap, not a pass").isEqualTo(2);
        assertThat(parsedStdout().isObject()).as("nothing to lint is still a document: " + out).isTrue();
        assertThat(parsedStdout().path("examined").asInt(-1)).isZero();
        assertThat(jsonIsClean(parsedStdout())).as("#1100: a run that examined nothing is not a clean scan: " + out)
                  .isFalse();
        assertStderrIsTheRunsLines(false);
        assertThat(run.result().verdict().kind()).isEqualTo(org.pragmatica.jbct.lint.cst.RunOutcome.Kind.NOTHING_COLLECTED);
    }

    /// #1100 — a coverage-gap run (exit 2) must say so IN the document: most CI SARIF uploaders never read
    /// the exit status, so an empty `results` alone recorded a clean scan for files nobody analysed.
    @Test
    void json_unparseableFile_documentNamesTheSkippedFile_andIsNotClean() throws IOException {
        write("Pure.java", CLEAN);
        write("Broken.java", BROKEN);
        var exitCode = lint("--format", "json");
        var doc = parsedStdout();

        assertThat(exitCode).isEqualTo(2);
        assertThat(doc.path("collected").asInt()).isEqualTo(2);
        assertThat(doc.path("examined").asInt()).isOne();
        assertThat(doc.path("skipped").size()).isOne();
        assertThat(doc.path("skipped").get(0).path("file").asText()).endsWith("Broken.java");
        assertThat(doc.path("skipped").get(0).path("reason").asText()).contains("Parse failed");
        assertThat(jsonIsClean(doc)).as("the clean file's empty findings must not make this a clean scan: " + out)
                  .isFalse();
    }

    @Test
    void sarif_unparseableFile_invocationIsUnsuccessful_withAnErrorNotification() throws IOException {
        write("Pure.java", CLEAN);
        write("Broken.java", BROKEN);
        var exitCode = lint("--format", "sarif");
        var invocation = parsedStdout().path("runs").get(0).path("invocations").get(0);

        assertThat(exitCode).isEqualTo(2);
        assertThat(invocation.path("executionSuccessful").asBoolean(true)).isFalse();
        assertThat(invocation.path("executionSuccessful").asBoolean(true)).isEqualTo(run.result().executionSuccessful());
        var notifications = invocation.path("toolExecutionNotifications");

        // The release's list, as released: one entry per file not analysed and no run-level "Parse errors" entry in front of it.
        assertThat(notifications.size()).as("exactly one notification per file not analysed: " + notifications).isEqualTo(1);
        assertThat(notifications.get(0).path("level").asText()).isEqualTo(run.result().notificationLevel());
        assertThat(notifications.get(0).path("level").asText()).isEqualTo("error");
        assertThat(notifications.get(0).path("message").path("text").asText()).startsWith("Not analysed: Parse failed: ");
        assertThat(notifications.get(0)
                                .path("locations").get(0).path("physicalLocation").path("artifactLocation")
                                .path("uri").asText()).endsWith("Broken.java");
        assertThat(sarifIsClean(parsedStdout())).isFalse();
    }

    @Test
    void sarif_noJavaFiles_invocationIsUnsuccessful_withAnErrorThatNothingWasExamined() {
        var exitCode = lint("--format", "sarif");
        var invocation = parsedStdout().path("runs").get(0).path("invocations").get(0);

        assertThat(exitCode).isEqualTo(2);
        assertThat(invocation.path("executionSuccessful").asBoolean(true)).isFalse();
        assertThat(invocation.path("toolExecutionNotifications").get(0).path("level").asText()).isEqualTo("error");
        assertThat(sarifIsClean(parsedStdout())).as("#1100: a run that examined nothing is not a clean scan: " + out)
                  .isFalse();
    }

    @Test
    void sarif_cleanRun_isACleanScan() throws IOException {
        // Control: the predicate the gap cases fail is one a genuinely clean run passes.
        write("Pure.java", CLEAN);
        lint("--format", "sarif");
        assertThat(sarifIsClean(parsedStdout())).as("a complete clean run IS a clean scan: " + out).isTrue();
    }

    /// The JSON clean-scan predicate documented in jbct/README.md:
    /// `.examined > 0 and (.skipped | length) == 0 and (.diagnostics | length) == 0`.
    private static boolean jsonIsClean(JsonNode doc) {
        return doc.path("examined").asInt(0) > 0 && doc.path("skipped").size() == 0 && doc.path("diagnostics").size() == 0;
    }

    /// The SARIF clean-scan predicate documented in jbct/README.md: the invocation succeeded, raised no
    /// notification, and the run has no results.
    private static boolean sarifIsClean(JsonNode doc) {
        var run = doc.path("runs").get(0);
        var invocation = run.path("invocations").get(0);

        return invocation.path("executionSuccessful").asBoolean(false)
               && invocation.path("toolExecutionNotifications").size() == 0
               && run.path("results").size() == 0;
    }

    @Test
    void text_summaryStaysOnStdout() throws IOException {
        write("Warny.java", VIOLATING);
        lint();
        assertThat(out.toString(UTF_8)).isEqualTo(run.expectedStdout(false));
    }
}
