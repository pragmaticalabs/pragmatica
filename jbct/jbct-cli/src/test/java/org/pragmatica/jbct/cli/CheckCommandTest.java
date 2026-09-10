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

    private int check() {
        out.reset();
        err.reset();

        return new CommandLine(new CheckCommand()).execute(sources.toString());
    }

    @Test
    void check_reportsTheAnalysedDenominator_whenAFileCannotBeParsed() throws IOException {
        write("Pure.java", CLEAN);
        write("Ell.java", UNPARSEABLE);

        var exitCode = check();

        assertThat(err.toString(UTF_8)).contains("Parse failed:");
        assertThat(out.toString(UTF_8)).doesNotContain("Check results: ")
                                       .doesNotContain("✓ All checks passed.");
        assertThat(out.toString(UTF_8)).contains("Check results (checked 1 of 2 file(s), 1 UNPARSEABLE):")
                                       .contains("COVERAGE GAP");
        assertThat(exitCode).isEqualTo(2);
    }

    /// Positive control for both `doesNotContain` assertions above: this is the state that earns the
    /// pass line, and it prints the complete-coverage summary byte-identically to how it always has,
    /// bar the coverage clause this fix adds.
    @Test
    void check_announcesAPass_whenEverythingWasAnalysedAndClean() throws IOException {
        write("Pure.java", CLEAN);

        var exitCode = check();

        assertThat(out.toString(UTF_8)).contains("Check results (checked 1 file(s)): 0 format issue(s), 0 lint error(s), 0 warning(s)")
                                       .contains("✓ All checks passed.")
                                       .doesNotContain("UNPARSEABLE");
        assertThat(exitCode).isZero();
    }
}
