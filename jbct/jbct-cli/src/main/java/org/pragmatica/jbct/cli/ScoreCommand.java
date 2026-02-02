package org.pragmatica.jbct.cli;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.config.JbctConfig;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.JbctLinter;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.score.ScoreCalculator;
import org.pragmatica.jbct.score.ScoreCategory;
import org.pragmatica.jbct.score.ScoreResult;
import org.pragmatica.jbct.shared.FileCollector;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Score command for JBCT compliance scoring.
 */
@Command(
 name = "score",
 description = "Calculate JBCT compliance score",
 mixinStandardHelpOptions = true)
public class ScoreCommand implements Callable<Integer> {
    @Parameters(
    paramLabel = "<path>",
    description = "Files or directories to score",
    arity = "1..*")
    List<Path> paths;

    @picocli.CommandLine.Option(
    names = {"--format", "-f"},
    description = "Output format: terminal, json, badge",
    defaultValue = "terminal")
    String format;

    @picocli.CommandLine.Option(
    names = {"--baseline", "-b"},
    description = "Minimum acceptable score (fails if below)")
    Integer baseline;

    @picocli.CommandLine.Option(
    names = {"--config"},
    description = "Path to configuration file")
    Path configPath;

    @Override
    public Integer call() {
        var config = ConfigLoader.load(Option.option(configPath), Option.none());
        var context = createContext(config);
        var linter = JbctLinter.jbctLinter(context);
        var filesToProcess = collectJavaFiles();

        if (filesToProcess.isEmpty()) {
            System.err.println("No Java files found");
            return 1;
        }

        var diagnostics = lintFiles(filesToProcess, linter);
        var score = ScoreCalculator.calculate(diagnostics, filesToProcess.size());

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
                          .withBusinessPackages(jbctConfig.businessPackages());
    }

    private List<Path> collectJavaFiles() {
        return FileCollector.collectJavaFiles(paths, System.err::println);
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
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.printf("║     JBCT COMPLIANCE SCORE: %d/100            ║%n", score.overall());
        System.out.println("╠═══════════════════════════════════════════════════╣");

        for (var category : ScoreCategory.values()) {
            var categoryScore = score.breakdown()
                                     .get(category);
            var percent = categoryScore.score();
            var bar = createProgressBar(percent);
            System.out.printf("║  %-18s %s %3d%%    ║%n",
                              category.name()
                                      .replace('_', ' '),
                              bar,
                              percent);
        }

        System.out.println("╚═══════════════════════════════════════════════════╝");
    }

    private String createProgressBar(int percent) {
        var filled = percent / 5; // 20 chars = 100%
        var empty = 20 - filled;
        return "█".repeat(filled) + "░".repeat(empty);
    }

    private void outputJson(ScoreResult score) {
        System.out.println("{");
        System.out.printf("  \"score\": %d,%n", score.overall());
        System.out.println("  \"breakdown\": {");

        var categories = ScoreCategory.values();
        for (int i = 0; i < categories.length; i++) {
            var category = categories[i];
            var categoryScore = score.breakdown()
                                     .get(category);
            System.out.printf("    \"%s\": %d%s%n",
                              category.name()
                                      .toLowerCase(),
                              categoryScore.score(),
                              i < categories.length - 1
                              ? ","
                              : "");
        }

        System.out.println("  },");
        System.out.printf("  \"filesAnalyzed\": %d%n", score.filesAnalyzed());
        System.out.println("}");
    }

    private void outputBadge(ScoreResult score) {
        var color = score.overall() >= 90
                    ? "brightgreen"
                    : score.overall() >= 75
                      ? "green"
                      : score.overall() >= 60
                        ? "yellow"
                        : score.overall() >= 50
                          ? "orange"
                          : "red";

        var svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="100" height="20">
              <linearGradient id="b" x2="0" y2="100%%">
                <stop offset="0" stop-color="#bbb" stop-opacity=".1"/>
                <stop offset="1" stop-opacity=".1"/>
              </linearGradient>
              <mask id="a">
                <rect width="100" height="20" rx="3" fill="#fff"/>
              </mask>
              <g mask="url(#a)">
                <path fill="#555" d="M0 0h45v20H0z"/>
                <path fill="%s" d="M45 0h55v20H45z"/>
                <path fill="url(#b)" d="M0 0h100v20H0z"/>
              </g>
              <g fill="#fff" text-anchor="middle" font-family="DejaVu Sans,Verdana,Geneva,sans-serif" font-size="11">
                <text x="22.5" y="15" fill="#010101" fill-opacity=".3">JBCT</text>
                <text x="22.5" y="14">JBCT</text>
                <text x="71.5" y="15" fill="#010101" fill-opacity=".3">%d/100</text>
                <text x="71.5" y="14">%d/100</text>
              </g>
            </svg>
            """.formatted(color, score.overall(), score.overall());

        System.out.println(svg);
    }
}
