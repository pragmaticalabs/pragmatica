package org.pragmatica.jbct.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.config.JbctConfig;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.JbctLinter;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.layer.LayerCoverage;
import org.pragmatica.jbct.score.ScoreCalculator;
import org.pragmatica.jbct.score.ScoreReport;
import org.pragmatica.jbct.score.ScoreResult;
import org.pragmatica.jbct.shared.FileCollector;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;


/// Score command for JBCT compliance scoring.
@Command(name = "score", description = "Calculate JBCT compliance score", mixinStandardHelpOptions = true)
public class ScoreCommand implements Callable<Integer> {
    @Parameters(paramLabel = "<path>", description = "Files or directories to score", arity = "1..*")
    List<Path> paths;

    @picocli.CommandLine.Option(names = {"--format", "-f"}, description = "Output format: terminal, json, badge", defaultValue = "terminal")
    String format;

    @picocli.CommandLine.Option(names = {"--baseline", "-b"}, description = "Minimum acceptable score (fails if below)")
    Integer baseline;

    @picocli.CommandLine.Option(names = {"--config"}, description = "Path to configuration file")
    Path configPath;

    @Override
    public Integer call() {
        var config = ConfigLoader.load(Option.option(configPath), Option.none());
        var context = createContext(config);
        var linter = JbctLinter.jbctLinter(context);
        var filesToProcess = FileCollector.collectJavaFiles(paths, config.files(), System.err::println);

        if (filesToProcess.isEmpty()) {
            System.err.println("No Java files found");

            return 1;
        }

        var diagnostics = lintFiles(filesToProcess, linter);
        var score = ScoreCalculator.calculate(diagnostics, filesToProcess.size());

        LayerCoverage.coverage(filesToProcess, context)
                     .map(LayerCoverage::render)
                     .onPresent(System.err::println);
        outputScore(score);
        if (baseline != null && score.overall() < baseline) {
            System.err.println("\nScore " + score.overall() + " below baseline " + baseline);

            return 1;
        }

        return 0;
    }

    private LintContext createContext(JbctConfig jbctConfig) {
        return LintContext.defaultContext()
                          .withConfig(jbctConfig.lint())
                          .withExcludePackages(jbctConfig.excludePackages())
                          .withLayers(jbctConfig.layers());
    }

    private List<Diagnostic> lintFiles(List<Path> files, JbctLinter linter) {
        var diagnostics = new ArrayList<Diagnostic>();

        for (var file : files) {
            SourceFile.sourceFile(file)
                      .flatMap(linter::lint)
                      .onSuccess(diagnostics::addAll)
                      .onFailure(cause -> System.err.println("  ✗ " + file + ": " + cause.message()));
        }

        return diagnostics;
    }

    private void outputScore(ScoreResult score) {
        switch (format.toLowerCase()) {
            case "json" -> outputJson(score);
            case "badge" -> outputBadge(score);
            default -> outputTerminal(score);
        }
    }

    private void outputTerminal(ScoreResult score) {
        ScoreReport.terminalLines(score).forEach(System.out::println);
    }

    private void outputJson(ScoreResult score) {
        ScoreReport.jsonLines(score).forEach(System.out::println);
    }

    private void outputBadge(ScoreResult score) {
        ScoreReport.badgeLines(score).forEach(System.out::println);
    }
}
