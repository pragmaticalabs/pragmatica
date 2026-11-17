package org.pragmatica.jbct.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;

import org.pragmatica.jbct.lint.cst.LintRun;
import org.pragmatica.jbct.lint.cst.RootResolver;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Level;
import org.pragmatica.jbct.lint.cst.RunOutcome.Line;
import org.pragmatica.jbct.lint.cst.RunOutcome.RunResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.Verdict;
import org.pragmatica.jbct.lint.cst.RootResolver.ModeOverride;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// What the CLI commands collect and print around the shared resolver and run (#2525, P2c).
///
/// The commands hand their path arguments, `--config`, `--mode` and `--fail-on-warning` to [RootResolver] and print what
/// [org.pragmatica.jbct.lint.cst.LintRun] returns. Which source root a file belongs to, that root's configuration and mode, and every
/// count and summary line are decided there, never here.
public sealed interface CliRoots {
    /// The `--mode` flag name as the summary line quotes it.
    String MODE_FLAG = "--mode";

    /// Resolve the roots of the run from the command's arguments; empty (after printing the refusal and the collector's
    /// warnings to `err`) when a configuration layer or the mode override cannot be used.
    static Option<RootPlan> plan(Option<Path> configPath,
                                 Option<String> mode,
                                 List<Path> paths,
                                 boolean failOnWarning,
                                 PrintStream err) {
        return RootResolver.resolve(RootResolver.Request.request(paths)
                                                        .withExplicitConfig(configPath)
                                                        .withModeOverride(mode.map(value -> ModeOverride.modeOverride(value,
                                                                                                                      MODE_FLAG)))
                                                        .withFailOnWarning(failOnWarning))
                           .onFailure(cause -> cause.stream()
                                                    .forEach(single -> err.println("Error: " + single.message())))
                           .onSuccess(plan -> plan.messages()
                                                  .forEach(err::println))
                           .option();
    }

    /// The run as a function of the resolved plan and the invocation; a front end holds one so a test can capture the result
    /// the front end itself received.
    /// The seam every command starts with: the plan's own run performs the invocation.
    BiFunction<RootPlan, Invocation, Result<RunResult>> PERFORM = (plan, invocation) -> LintRun.lintRun(plan)
                                                                                                .perform(invocation);

    /// Print what the run returned and map its verdict to the exit status; a refused run prints its cause and exits with
    /// [CliConfig#CONFIG_ERROR_EXIT].
    static int finish(Result<RunResult> performed, boolean verbose, int nothingCollectedExit) {
        return performed.fold(CliRoots::refused, result -> printed(result, verbose, nothingCollectedExit));
    }

    static int refused(Cause cause) {
        System.err.println("Error: " + cause.message());

        return CliConfig.CONFIG_ERROR_EXIT;
    }

    private static int printed(RunResult result, boolean verbose, int nothingCollectedExit) {
        print(result, verbose);

        return exitCode(result.verdict(), nothingCollectedExit);
    }

    /// The lines before the findings, the findings, then the lines after them: ERROR lines to stderr, DETAIL lines only
    /// with `verbose`, the rest to stdout.
    @Contract
    static void print(RunResult result, boolean verbose) {
        result.before()
              .forEach(line -> printLine(line, verbose));
        result.findings()
              .forEach(CliRoots::printFinding);
        result.after()
              .forEach(line -> printLine(line, verbose));
    }

    /// The run's lines (not its findings) to stderr, for a command whose stdout document carries the findings.
    @Contract
    static void printLinesToStderr(RunResult result, boolean verbose) {
        result.before()
              .forEach(line -> printLineTo(System.err, line, verbose));
        result.after()
              .forEach(line -> printLineTo(System.err, line, verbose));
    }

    /// Every line and finding of the run to stderr, for a command whose stdout carries a document of its own.
    @Contract
    static void printToStderr(RunResult result, boolean verbose) {
        result.before()
              .forEach(line -> printLineTo(System.err, line, verbose));
        result.findings()
              .forEach(finding -> System.err.print(finding.toHumanReadable()));
        result.after()
              .forEach(line -> printLineTo(System.err, line, verbose));
    }

    @Contract
    private static void printLineTo(PrintStream stream, Line line, boolean verbose) {
        if (verbose || line.level() != Level.DETAIL) {
            stream.println(line.text());
        }
    }

    @Contract
    private static void printFinding(Diagnostic finding) {
        System.out.print(finding.toHumanReadable());
    }

    @Contract
    private static void printLine(Line line, boolean verbose) {
        streamOf(line.level(), verbose).onPresent(stream -> stream.println(line.text()));
    }

    private static Option<PrintStream> streamOf(Level level, boolean verbose) {
        return switch (level) {
            case ERROR -> Option.some(System.err);
            case DETAIL -> verbose
                           ? Option.some(System.out)
                           : Option.<PrintStream>none();
            case WARN, INFO -> Option.some(System.out);
        };
    }

    /// The exit status of an INCOMPLETE run: the tool could not do its job.
    int INCOMPLETE_EXIT = 2;

    /// The exit status of a verdict: CLEAN 0, FINDINGS 1, INCOMPLETE 2, and `nothingCollectedExit` when the plan held no file.
    static int exitCode(Verdict verdict, int nothingCollectedExit) {
        return switch (verdict.kind()) {
            case CLEAN -> 0;
            case FINDINGS -> 1;
            case INCOMPLETE -> INCOMPLETE_EXIT;
            case NOTHING_COLLECTED -> nothingCollectedExit;
        };
    }

    record unused() implements CliRoots {}
}
