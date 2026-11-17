package org.pragmatica.jbct.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;

import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.jbct.lint.cst.RunOutcome.DensityLimit;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.RunResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.Surface;
import org.pragmatica.jbct.score.DensityGate;
import org.pragmatica.jbct.score.ScoreReport;
import org.pragmatica.jbct.score.ScoreResult;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;


/// Score command reporting JBCT violation density.
@Command(name = "score", description = "Report JBCT violation density (violations per KLOC)", mixinStandardHelpOptions = true)
public class ScoreCommand implements Callable<Integer> {
    /// Exit code for a usage error, matching picocli's own.
    static final int USAGE_ERROR = 2;
    /// Output formats the command knows. Anything else is rejected rather than quietly rendered
    /// as a terminal box — `--format badge` used to be real, so silently substituting a different
    /// format for it would hand a CI job the wrong bytes with a zero exit code.
    static final List<String> SUPPORTED_FORMATS = List.of("terminal", "json");

    @Parameters(paramLabel = "<path>", description = "Files or directories to measure", arity = "1..*")
    List<Path> paths;

    @picocli.CommandLine.Option(names = {"--format", "-f"}, description = "Output format: terminal, json", defaultValue = "terminal")
    String format;

    @picocli.CommandLine.Option(names = {"--max-density"}, description = "Maximum acceptable violations per KLOC (fails if above)")
    Double maxDensity;

    /// The removed 0-100 score gate, still bound so a command line that uses it fails loudly with
    /// migration guidance instead of dying on "unknown option" — or, worse, being silently
    /// re-interpreted in the opposite direction.
    @picocli.CommandLine.Option(names = {"--baseline", "-b"}, hidden = true, description = "Removed: use --max-density")
    Integer baseline;

    @picocli.CommandLine.Option(names = {"--config"}, description = "Path to configuration file")
    Path configPath;

    @picocli.CommandLine.Option(names = {"--mode"}, description = "JBCT mode for this run, overriding jbct.toml and --config: business, infrastructure, format-only or disabled")
    String mode;

    /// The exit status when the plan holds no file: unchanged ("No Java files found" exits 1, not 2).
    static final int NOTHING_COLLECTED_EXIT = 1;

    /// The flag the density limit came from, named in the run's sentences.
    static final String MAX_DENSITY_FLAG = "--max-density";

    /// The one place the resolved plan and the invocation reach the run; a test replaces it to capture the result the command received (#2525).
    BiFunction<RootPlan, Invocation, Result<RunResult>> runSeam = CliRoots.PERFORM;

    @Override
    public Integer call() {
        if (baseline != null) {
            System.err.println(DensityGate.REMOVED_BASELINE_MESSAGE);

            return USAGE_ERROR;
        }

        if (!SUPPORTED_FORMATS.contains(format.toLowerCase(Locale.ROOT))) {
            System.err.println("Unknown format '" + format
                              + "'; supported formats: " + String.join(", ", SUPPORTED_FORMATS));

            return USAGE_ERROR;
        }

        var planned = CliRoots.plan(Option.option(configPath), Option.option(mode), paths, false, System.err);

        if (planned.isEmpty()) {
            return CliConfig.CONFIG_ERROR_EXIT;
        }

        var limit = Option.option(maxDensity).map(max -> DensityLimit.densityLimit(max, MAX_DENSITY_FLAG));

        return runSeam.apply(planned.unwrap(), Invocation.score(Surface.CLI, limit))
                      .fold(CliRoots::refused, this::printed);
    }

    /// stdout carries only the score document; the run's own lines and findings go to stderr.
    private int printed(RunResult result) {
        CliRoots.printToStderr(result, false);
        result.score().onPresent(this::outputScore);

        return CliRoots.exitCode(result.verdict(), NOTHING_COLLECTED_EXIT);
    }

    @Contract
    private void outputScore(ScoreResult score) {
        switch (format.toLowerCase(Locale.ROOT)) {
            case "json" -> ScoreReport.jsonLines(score).forEach(System.out::println);
            default -> ScoreReport.terminalLines(score).forEach(System.out::println);
        }
    }
}
