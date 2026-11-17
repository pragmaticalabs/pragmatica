package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Kind;
import org.pragmatica.jbct.lint.cst.RunOutcome.Level;
import org.pragmatica.jbct.lint.cst.RunOutcome.Line;
import org.pragmatica.jbct.lint.cst.RunOutcome.RunResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.Verdict;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import picocli.CommandLine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/// #2525 step 3a: `format` and `check` print the lines of the run they received and map its verdict to an exit status:
/// CLEAN 0, FINDINGS 1, INCOMPLETE 2, NOTHING_COLLECTED 0 (the status of both commands before the run printed its own lines).
/// Each command is driven once per verdict kind through the seam, which performs the real run over a real tree and then
/// replaces the verdict, so the status can only come from the mapping; the lines the seam adds (one per level) pin the routing
/// of ERROR to stderr and of DETAIL to `--verbose`.
class CliRunVerdictExitTest {
    private static final String CLEAN = """
                                        public record Pure(String a) {
                                            public static Pure pure(String a) {
                                                return new Pure(a);
                                            }
                                        }
                                        """;
    private static final Line BEFORE_INFO = Line.line(Level.INFO, "before-info");
    private static final Line BEFORE_ERROR = Line.line(Level.ERROR, "before-error");
    private static final Line BEFORE_DETAIL = Line.line(Level.DETAIL, "before-detail");
    private static final Line AFTER_WARN = Line.line(Level.WARN, "after-warn");

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
        Files.writeString(sources.resolve("Pure.java"), CLEAN);
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private static BiFunction<RootPlan, Invocation, Result<RunResult>> withVerdict(Kind kind) {
        return (plan, invocation) -> CliRoots.PERFORM.apply(plan, invocation)
                                                     .map(real -> RunResult.runResult(real.files(),
                                                                                      real.roots(),
                                                                                      real.findings(),
                                                                                      verdictOf(kind),
                                                                                      List.of(BEFORE_INFO, BEFORE_ERROR, BEFORE_DETAIL),
                                                                                      List.of(AFTER_WARN),
                                                                                      real.totals(),
                                                                                      real.score()));
    }

    private static Verdict verdictOf(Kind kind) {
        return switch (kind) {
            case CLEAN -> Verdict.clean();
            case FINDINGS -> Verdict.findings("findings");
            case INCOMPLETE -> Verdict.incomplete("incomplete");
            case NOTHING_COLLECTED -> Verdict.nothingCollected();
        };
    }

    private static int expectedExit(Kind kind) {
        return switch (kind) {
            case CLEAN, NOTHING_COLLECTED -> 0;
            case FINDINGS -> 1;
            case INCOMPLETE -> 2;
        };
    }

    private int format(BiFunction<RootPlan, Invocation, Result<RunResult>> seam, String... flags) {
        var command = new FormatCommand();

        command.runSeam = seam;

        return new CommandLine(command).execute(withPath(flags));
    }

    private int check(BiFunction<RootPlan, Invocation, Result<RunResult>> seam, String... flags) {
        var command = new CheckCommand();

        command.runSeam = seam;

        return new CommandLine(command).execute(withPath(flags));
    }

    private int lint(BiFunction<RootPlan, Invocation, Result<RunResult>> seam, String... flags) {
        var command = new LintCommand();

        command.runSeam = seam;

        return new CommandLine(command).execute(withPath(flags));
    }

    private int score(BiFunction<RootPlan, Invocation, Result<RunResult>> seam, String... flags) {
        var command = new ScoreCommand();

        command.runSeam = seam;

        return new CommandLine(command).execute(withPath(flags));
    }

    private String[] withPath(String... flags) {
        var args = new ArrayList<>(List.of(flags));

        args.add(sources.toString());

        return args.toArray(String[]::new);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void format_mapsEveryVerdictKindToItsExitStatus(Kind kind) {
        assertThat(format(withVerdict(kind))).isEqualTo(expectedExit(kind));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void formatCheck_mapsEveryVerdictKindToItsExitStatus(Kind kind) {
        assertThat(format(withVerdict(kind), "--check")).isEqualTo(expectedExit(kind));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void check_mapsEveryVerdictKindToItsExitStatus(Kind kind) {
        assertThat(check(withVerdict(kind))).isEqualTo(expectedExit(kind));
    }

    /// `lint` exits 2 when it collected nothing (#1100: a coverage gap, never a pass).
    private static int expectedLintExit(Kind kind) {
        return kind == Kind.NOTHING_COLLECTED
               ? 2
               : expectedExit(kind);
    }

    /// `score` has always exited 1 for "No Java files found".
    private static int expectedScoreExit(Kind kind) {
        return kind == Kind.NOTHING_COLLECTED
               ? 1
               : expectedExit(kind);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void lint_mapsEveryVerdictKindToItsExitStatus(Kind kind) {
        assertThat(lint(withVerdict(kind))).isEqualTo(expectedLintExit(kind));
        assertThat(lint(withVerdict(kind), "--format", "json")).isEqualTo(expectedLintExit(kind));
        assertThat(lint(withVerdict(kind), "--format", "sarif")).isEqualTo(expectedLintExit(kind));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void score_mapsEveryVerdictKindToItsExitStatus(Kind kind) {
        assertThat(score(withVerdict(kind))).isEqualTo(expectedScoreExit(kind));
    }

    @Test
    void lint_machineFormat_keepsStdoutForTheDocument_andPrintsTheRunsLinesToStderr() {
        lint(withVerdict(Kind.CLEAN), "--format", "json");

        assertThat(err.toString(UTF_8).lines()).containsExactly("before-info", "before-error", "after-warn");
        assertThat(out.toString(UTF_8)).startsWith("{").doesNotContain("before-info");
    }

    @Test
    void format_printsErrorToStderrDetailOnlyVerbose_andTheRestToStdoutInOrder() {
        format(withVerdict(Kind.CLEAN));

        assertThat(out.toString(UTF_8).lines()).containsExactly("before-info", "after-warn");
        assertThat(err.toString(UTF_8).lines()).containsExactly("before-error");
        out.reset();
        err.reset();
        format(withVerdict(Kind.CLEAN), "--verbose");

        assertThat(out.toString(UTF_8).lines()).containsExactly("before-info", "before-detail", "after-warn");
        assertThat(err.toString(UTF_8).lines()).containsExactly("before-error");
    }

    @Test
    void check_printsErrorToStderrDetailOnlyVerbose_andTheRestToStdoutInOrder() {
        check(withVerdict(Kind.CLEAN));

        assertThat(out.toString(UTF_8).lines()).containsExactly("before-info", "after-warn");
        assertThat(err.toString(UTF_8).lines()).containsExactly("before-error");
        out.reset();
        err.reset();
        check(withVerdict(Kind.CLEAN), "--verbose");

        assertThat(out.toString(UTF_8).lines()).containsExactly("before-info", "before-detail", "after-warn");
        assertThat(err.toString(UTF_8).lines()).containsExactly("before-error");
    }

    @Test
    void format_andCheck_refusedRun_printTheCauseWithTheErrorPrefixAndExitWithTheConfigStatus() {
        BiFunction<RootPlan, Invocation, Result<RunResult>> refusing = (plan, invocation) -> Causes.cause("refused for the test")
                                                                                                      .result();

        assertThat(format(refusing)).isEqualTo(CliConfig.CONFIG_ERROR_EXIT);
        assertThat(err.toString(UTF_8).lines()).containsExactly("Error: refused for the test");
        err.reset();
        assertThat(check(refusing)).isEqualTo(CliConfig.CONFIG_ERROR_EXIT);
        assertThat(err.toString(UTF_8).lines()).containsExactly("Error: refused for the test");
        assertThat(out.toString(UTF_8)).isEmpty();
    }
}
