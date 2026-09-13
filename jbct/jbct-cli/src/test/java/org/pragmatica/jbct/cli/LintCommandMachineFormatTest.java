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

        return new CommandLine(new LintCommand()).execute(args.toArray(String[]::new));
    }

    private JsonNode parsedStdout() {
        return JSON.readTree(out.toString(UTF_8));
    }

    @Test
    void json_withFindings_stdoutIsExactlyTheArray_summaryGoesToStderr() throws IOException {
        write("Warny.java", VIOLATING);
        var exitCode = lint("--format", "json");

        assertThat(exitCode).isOne();
        var doc = parsedStdout();

        assertThat(doc.isArray()).as("stdout must parse as the diagnostics array and nothing more: " + out).isTrue();
        assertThat(doc.size()).isPositive();
        assertThat(err.toString(UTF_8)).as("the summary is operator-facing and belongs on stderr")
                  .contains("Checked 1 file(s)");
        assertThat(out.toString(UTF_8)).doesNotContain("Checked");
    }

    @Test
    void json_cleanRun_stdoutIsAnEmptyArray_notNothing() throws IOException {
        write("Pure.java", CLEAN);
        var exitCode = lint("--format", "json");

        assertThat(exitCode).isZero();
        assertThat(parsedStdout().isArray()).as("a clean run must still emit a document a parser can load: " + out)
                  .isTrue();
        assertThat(parsedStdout().size()).isZero();
        assertThat(err.toString(UTF_8)).contains("passed JBCT compliance check");
    }

    @Test
    void sarif_stdoutIsOneSarifDocument_summaryGoesToStderr() throws IOException {
        write("Warny.java", VIOLATING);
        lint("--format", "sarif");
        var doc = parsedStdout();

        assertThat(doc.path("version").asText()).isEqualTo("2.1.0");
        assertThat(doc.path("runs").get(0).path("results").size()).isPositive();
        assertThat(err.toString(UTF_8)).contains("Checked 1 file(s)");
    }

    @Test
    void json_verbose_progressLinesGoToStderr() throws IOException {
        write("Pure.java", CLEAN);
        lint("--format", "json", "-v");
        assertThat(parsedStdout().isArray()).as("-v must not corrupt the document: " + out).isTrue();
        assertThat(err.toString(UTF_8)).contains("Found 1 Java file(s) to lint.");
    }

    @Test
    void json_noJavaFiles_stdoutIsAnEmptyArray_noticeGoesToStderr() {
        var exitCode = lint("--format", "json");

        assertThat(exitCode).isZero();
        assertThat(parsedStdout().isArray()).as("nothing to lint is still a document: " + out).isTrue();
        assertThat(err.toString(UTF_8)).contains("No Java files found.");
    }

    @Test
    void text_summaryStaysOnStdout() throws IOException {
        write("Warny.java", VIOLATING);
        lint();
        assertThat(out.toString(UTF_8)).contains("Checked 1 file(s)");
    }
}
