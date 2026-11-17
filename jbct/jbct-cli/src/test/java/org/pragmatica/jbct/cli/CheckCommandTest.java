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
import org.pragmatica.jbct.lint.cst.RunOutcome.Kind;
import picocli.CommandLine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;


/// #977's sibling case on `jbct check`, the command CI actually runs.
///
/// `check` never claimed a pass over an unread file — a parse error returns 2 before `✓ All checks
/// passed.` is reached — but its summary carried the same defect one step earlier: `Check results: 0
/// format issue(s), 0 lint error(s), 0 warning(s)` states no denominator at all, so counts taken over
/// one analysed file of two read as a clean sweep of both. Zero findings and zero coverage render
/// identically; the line has to say which it is.
///
/// Pinned alongside [LintCommandTest] rather than folded into it because the two commands compute
/// their verdicts independently, and a fix applied to one is not evidence about the other.
class CheckCommandTest {
    /// Parses, formatted to JBCT style, no findings — so the pass line is genuinely reachable and the
    /// `doesNotContain` assertions below cannot pass because it was unreachable.
    private static final String CLEAN = """
                                        public record Pure(String a) {
                                            public static Pure pure(String a) {
                                                return new Pure(a);
                                            }
                                        }
                                        """;

    private static final String UNPARSEABLE = """
                                              public interface Ell {
                                                  ...
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

    private final CapturedRun captured = new CapturedRun();

    private int check() {
        out.reset();
        err.reset();

        var command = new CheckCommand();

        command.runSeam = captured;

        return new CommandLine(command).execute(sources.toString());
    }

    /// What the command printed is the report it received: stdout is its lines and findings in order, stderr holds its ERROR lines.
    private void assertPrintedTheReceivedReport() {
        assertThat(out.toString(UTF_8)).isEqualTo(captured.expectedStdout(false));
        assertThat(err.toString(UTF_8).lines()).containsAll(captured.expectedStderrLines());
    }

    @Test
    void check_printsTheReceivedReportAndExitsTwo_whenAFileCannotBeParsed() throws IOException {
        write("Pure.java", CLEAN);
        write("Ell.java", UNPARSEABLE);

        var exitCode = check();

        assertThat(captured.performs()).isEqualTo(1);
        assertThat(captured.result().verdict().kind()).isEqualTo(Kind.INCOMPLETE);
        assertThat(captured.result().verdict().failure().isPresent()).isTrue();
        assertPrintedTheReceivedReport();
        assertThat(exitCode).isEqualTo(2);
    }

    /// Positive control for the test above: a clean tree gives a CLEAN verdict and the command prints that report and exits 0.
    @Test
    void check_printsTheReceivedReportAndExitsZero_whenEverythingWasAnalysedAndClean() throws IOException {
        write("Pure.java", CLEAN);

        var exitCode = check();

        assertThat(captured.performs()).isEqualTo(1);
        assertThat(captured.result().verdict().kind()).isEqualTo(Kind.CLEAN);
        assertThat(captured.result().findings()).isEmpty();
        assertThat(captured.stdoutLines(false)).isNotEmpty();
        assertPrintedTheReceivedReport();
        assertThat(exitCode).isZero();
    }
}
