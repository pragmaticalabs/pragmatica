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


/// Regression gate for #977: `jbct lint` counted files it could not parse as files it had checked,
/// and then reported a pass.
///
/// The measured shape, at `8f02cd3cf`, pointed at one clean file and one unparseable one:
///
/// ```
///   ✗ ./Ell.java: Parse failed: Diagnostic[severity=ERROR, ...] (+5 more)
///
/// Parse errors: 1
/// ✓ All 2 file(s) passed JBCT compliance check.
/// ```
///
/// Two files collected, one analysed, and the summary said two passed. Pointed at the unparseable
/// file ALONE it printed `✓ All 1 file(s) passed JBCT compliance check.` — a perfect score over a set
/// the linter never read, which is this repository's recurring failure in its purest form: a check
/// that passes by not looking.
///
/// **These tests pin the FAILURE, not the success.** The load-bearing assertions are the ones naming
/// values that must NOT appear — `All 2 file(s)`, `Checked 2 file(s)`, the word `passed` — because an
/// over-matching pattern applied to a failure raises a false alarm, which gets chased, while the same
/// defect applied to a success manufactures an all-clear, which invites relief. Each is paired with
/// its positive control:
///
///   - [#lint_reportsAPass_whenEveryCollectedFileParses] produces the very pass line the gap tests
///     forbid, so "the pass line is absent" can never pass vacuously because the line was unreachable;
///   - every gap test also asserts the parser's own `Parse failed:` diagnostic on stderr, so "the run
///     failed" is distinguishable from "the fixture never reached the parser".
class LintCommandTest {
    /// Parses, produces no findings — the file the summary is entitled to speak for.
    private static final String CLEAN = "public record Pure(String a) { public static Pure pure(String a){ return new Pure(a);} }\n";

    /// Does not parse. A bare `...` where a member belongs, exactly the fragment shape #977 surfaced
    /// on: 165 of 496 fenced `java` blocks extracted from the books are incomplete units like this.
    private static final String UNPARSEABLE = """
                                              public interface Ell {
                                                  ...
                                              }
                                              """;

    /// Parses, and violates JBCT-RET-03 and JBCT-RET-06 — findings the summary IS evidence about.
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

    private int lint() {
        out.reset();
        err.reset();

        return new CommandLine(new LintCommand()).execute(sources.toString());
    }

    private String stdout() {
        return out.toString(UTF_8);
    }

    private String stderr() {
        return err.toString(UTF_8);
    }

    /// The ticket's own reproduction. One file of two was analysable; the summary claimed both.
    @Test
    void lint_refusesToReportAPass_whenAFileCannotBeParsed() throws IOException {
        write("Pure.java", CLEAN);
        write("Ell.java", UNPARSEABLE);

        var exitCode = lint();

        // The fixture reached the parser and was rejected by it. Without this, "no pass line" and
        // "the linter never ran" are the same observation.
        assertThat(stderr()).contains("Ell.java")
                            .contains("Parse failed:");
        // The forbidden values: the inflated denominator, and any claim of a pass.
        assertThat(stdout()).doesNotContain("All 2 file(s)")
                            .doesNotContain("passed JBCT compliance check")
                            .doesNotContain("Checked 2 file(s)");
        assertThat(stdout()).contains("1 of 2 file(s), 1 UNPARSEABLE")
                            .contains("COVERAGE GAP")
                            .contains("not evidence about those file(s)");
        assertThat(exitCode).isEqualTo(2);
    }

    /// Positive control for every `doesNotContain` above: the pass line is reachable, and this is the
    /// state that earns it.
    @Test
    void lint_reportsAPass_whenEveryCollectedFileParses() throws IOException {
        write("Pure.java", CLEAN);

        var exitCode = lint();

        assertThat(stdout()).contains("✓ All 1 file(s) passed JBCT compliance check.")
                            .doesNotContain("UNPARSEABLE")
                            .doesNotContain("COVERAGE GAP");
        assertThat(exitCode).isZero();
    }

    /// The other output branch #977 names. Findings are real, but they were collected over one file,
    /// not two, and the line has to say so.
    @Test
    void lint_countsOnlyAnalysedFiles_whenFindingsArePresentToo() throws IOException {
        write("Warny.java", VIOLATING);
        write("Ell.java", UNPARSEABLE);

        var exitCode = lint();

        assertThat(stderr()).contains("Parse failed:");
        assertThat(stdout()).doesNotContain("Checked 2 file(s)");
        assertThat(stdout()).contains("Checked 1 of 2 file(s), 1 UNPARSEABLE:")
                            .contains("2 error(s)");
        assertThat(exitCode).isEqualTo(2);
    }

    /// Pointed at nothing but the unparseable file, the tool used to award it full marks. Kept as its
    /// own case because it is the degenerate one — every file in the set unexamined — and the one
    /// where an inflated denominator is impossible to notice by eye.
    @Test
    void lint_refusesToReportAPass_whenNothingAtAllCouldBeParsed() throws IOException {
        write("Ell.java", UNPARSEABLE);

        var exitCode = lint();

        assertThat(stderr()).contains("Parse failed:");
        assertThat(stdout()).doesNotContain("All 1 file(s)")
                            .doesNotContain("passed JBCT compliance check");
        assertThat(stdout()).contains("0 of 1 file(s), 1 UNPARSEABLE");
        assertThat(exitCode).isEqualTo(2);
    }

    /// The exit-code split. A complete run that found real errors is a different event from a run that
    /// could not read its input, and both used to be `2` — so a job could not tell "your code is
    /// wrong" from "I never read your code". Rule violations are now `1`, matching `jbct check`.
    @Test
    void lint_exitsOne_forRuleViolations_whenCoverageIsComplete() throws IOException {
        write("Warny.java", VIOLATING);

        var exitCode = lint();

        assertThat(stdout()).contains("Checked 1 file(s): 2 error(s)")
                            .doesNotContain("UNPARSEABLE");
        assertThat(exitCode).isOne();
    }

    /// The complete-coverage summary strings are unchanged by this fix, deliberately: every difference
    /// a reader sees lands in the case that was lying. Pinned so a later edit cannot quietly move the
    /// clean-run wording and break readers that never had a reason to change.
    @Test
    void lint_leavesCompleteCoverageSummariesByteIdentical() throws IOException {
        write("Pure.java", CLEAN);
        write("Warny.java", VIOLATING);

        var exitCode = lint();

        assertThat(stdout()).contains("Checked 2 file(s): 2 error(s), 0 warning(s), 0 info(s)");
        assertThat(exitCode).isOne();
    }
}
