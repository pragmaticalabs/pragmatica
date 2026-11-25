package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.lint.cst.RunOutcome.Kind;
import org.pragmatica.jbct.lint.cst.RunOutcome.ParseFailed;
import org.pragmatica.jbct.lint.cst.RunOutcome.Tally;
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

    /// The fixture files sit in the default package, which no `[project] basePackage` covers: the source root is an
    /// infrastructure root (structure rules never read the key, #2526), so these tests keep judging the code rules only.
    private static final String INFRASTRUCTURE_CONFIG = "[lint]\nmode = \"infrastructure\"\n";

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
        Files.writeString(sources.resolve("jbct.toml"), INFRASTRUCTURE_CONFIG);
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(sources.resolve(name), content);
    }

    private final CapturedRun run = new CapturedRun();

    private int lint() {
        out.reset();
        err.reset();

        var command = new LintCommand();

        command.runSeam = run;

        return new CommandLine(command).execute(sources.toString());
    }

    private String stdout() {
        return out.toString(UTF_8);
    }

    private String stderr() {
        return err.toString(UTF_8);
    }

    private Tally lintTally() {
        return run.result().totals().lint().unwrap();
    }

    private long parseFailedFiles() {
        return run.result().files().stream().filter(file -> file.lint() instanceof ParseFailed).count();
    }

    /// What the command printed is the run's own lines and findings, nothing else; the exit status is the verdict's.
    private void assertPrintsTheRun(int exitCode, Kind kind) {
        assertThat(stdout()).isEqualTo(run.expectedStdout(false));
        assertThat(stderr().lines().toList()).isEqualTo(run.expectedStderrLines());
        assertThat(run.result().verdict().kind()).isEqualTo(kind);
        assertThat(exitCode).isEqualTo(CliRoots.exitCode(run.result().verdict(), LintCommand.NOTHING_COLLECTED_EXIT));
    }

    /// The ticket's own reproduction. One file of two was analysable; the run records the other as not parsed.
    @Test
    void lint_refusesToReportAPass_whenAFileCannotBeParsed() throws IOException {
        write("Pure.java", CLEAN);
        write("Ell.java", UNPARSEABLE);

        var exitCode = lint();

        assertPrintsTheRun(exitCode, Kind.INCOMPLETE);
        assertThat(parseFailedFiles()).isOne();
        assertThat(lintTally().notParsed()).isOne();
        assertThat(run.result().collected()).isEqualTo(2);
        assertThat(run.result().examined()).isOne();
        assertThat(exitCode).isEqualTo(2);
    }

    /// Positive control for the test above: the clean state is reachable and exits 0.
    @Test
    void lint_reportsAPass_whenEveryCollectedFileParses() throws IOException {
        write("Pure.java", CLEAN);

        var exitCode = lint();

        assertPrintsTheRun(exitCode, Kind.CLEAN);
        assertThat(parseFailedFiles()).isZero();
        assertThat(run.result().examined()).isOne();
        assertThat(run.result().findings()).isEmpty();
        assertThat(exitCode).isZero();
    }

    /// Findings are real, but they were collected over one file, not two; the run records the second as not parsed.
    @Test
    void lint_countsOnlyAnalysedFiles_whenFindingsArePresentToo() throws IOException {
        write("Warny.java", VIOLATING);
        write("Ell.java", UNPARSEABLE);

        var exitCode = lint();

        assertPrintsTheRun(exitCode, Kind.INCOMPLETE);
        assertThat(run.result().findings()).isNotEmpty();
        assertThat(run.result().examined()).isOne();
        assertThat(lintTally().notParsed()).isOne();
        assertThat(exitCode).isEqualTo(2);
    }

    /// Pointed at nothing but the unparseable file: every file in the set unexamined, never a pass.
    @Test
    void lint_refusesToReportAPass_whenNothingAtAllCouldBeParsed() throws IOException {
        write("Ell.java", UNPARSEABLE);

        var exitCode = lint();

        assertPrintsTheRun(exitCode, Kind.INCOMPLETE);
        assertThat(run.result().examined()).isZero();
        assertThat(lintTally().notParsed()).isOne();
        assertThat(exitCode).isEqualTo(2);
    }

    /// The exit-code split: a complete run that found real errors exits `1`, matching `jbct check`.
    @Test
    void lint_exitsOne_forRuleViolations_whenCoverageIsComplete() throws IOException {
        write("Warny.java", VIOLATING);

        var exitCode = lint();

        assertPrintsTheRun(exitCode, Kind.FINDINGS);
        assertThat(run.result().findings()).isNotEmpty();
        assertThat(run.result().examined()).isOne();
        assertThat(exitCode).isOne();
    }

    /// A clean file beside a violating one: both examined, findings from one, exit 1.
    @Test
    void lint_examinesEveryParsedFile_andPrintsTheRunsFindings() throws IOException {
        write("Pure.java", CLEAN);
        write("Warny.java", VIOLATING);

        var exitCode = lint();

        assertPrintsTheRun(exitCode, Kind.FINDINGS);
        assertThat(run.result().examined()).isEqualTo(2);
        assertThat(run.result().collected()).isEqualTo(2);
        assertThat(exitCode).isOne();
    }
}
