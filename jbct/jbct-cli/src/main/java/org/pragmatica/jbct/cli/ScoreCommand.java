package org.pragmatica.jbct.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.config.JbctConfig;
import org.pragmatica.jbct.lint.JbctLinter;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.score.DensityGate;
import org.pragmatica.jbct.score.ScoreCalculator;
import org.pragmatica.jbct.score.ScoreReport;
import org.pragmatica.jbct.score.ScoreResult;
import org.pragmatica.jbct.score.SourceScan;
import org.pragmatica.jbct.shared.FileCollector;
import org.pragmatica.lang.Option;

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

    @Override
    public Integer call() {
        if (baseline != null) {
            System.err.println(DensityGate.REMOVED_BASELINE_MESSAGE);

            return USAGE_ERROR;
        }

        if (!SUPPORTED_FORMATS.contains(format.toLowerCase(Locale.ROOT))) {
            System.err.println("Unknown format '" + format + "'; supported formats: "
                               + String.join(", ", SUPPORTED_FORMATS));

            return USAGE_ERROR;
        }

        var config = ConfigLoader.load(Option.option(configPath), Option.none());
        var context = createContext(config);
        var linter = JbctLinter.jbctLinter(context);
        var filesToProcess = FileCollector.collectJavaFiles(paths, config.files(), System.err::println);

        if (filesToProcess.isEmpty()) {
            System.err.println("No Java files found");

            return 1;
        }

        var scan = SourceScan.sourceScan(filesToProcess, linter::lint, message -> System.err.println("  ✗ " + message));
        var score = ScoreCalculator.calculate(scan);

        outputScore(score);

        return gateExitCode(score);
    }

    private LintContext createContext(JbctConfig jbctConfig) {
        return LintContext.defaultContext()
                          .withConfig(jbctConfig.lint())
                          .withExcludePackages(jbctConfig.excludePackages())
                          .withLayers(jbctConfig.layers());
    }

    private int gateExitCode(ScoreResult score) {
        if (maxDensity != null && DensityGate.exceeds(score.totalDensityPerKloc(), maxDensity)) {
            System.err.println("\n" + DensityGate.breachMessage(score.totalDensityPerKloc(), maxDensity));

            return 1;
        }

        return 0;
    }

    private void outputScore(ScoreResult score) {
        switch (format.toLowerCase(Locale.ROOT)) {
            case "json" -> outputJson(score);
            default -> outputTerminal(score);
        }
    }

    private void outputTerminal(ScoreResult score) {
        ScoreReport.terminalLines(score).forEach(System.out::println);
    }

    private void outputJson(ScoreResult score) {
        ScoreReport.jsonLines(score).forEach(System.out::println);
    }
}
