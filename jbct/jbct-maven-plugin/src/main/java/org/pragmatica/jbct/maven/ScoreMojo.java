package org.pragmatica.jbct.maven;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.JbctLinter;
import org.pragmatica.jbct.score.ScoreCalculator;
import org.pragmatica.jbct.score.ScoreCategory;
import org.pragmatica.jbct.score.ScoreResult;
import org.pragmatica.jbct.shared.SourceFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Maven goal for calculating JBCT compliance score.
 */
@Mojo(name = "score", defaultPhase = LifecyclePhase.VERIFY)
public class ScoreMojo extends AbstractJbctMojo {
    @Parameter(property = "jbct.score.baseline")
    Integer baseline;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (shouldSkip("score")) {
            return;
        }
        var jbctConfig = loadConfig();
        var context = createLintContext(jbctConfig);
        var linter = JbctLinter.jbctLinter(context);
        var filesToProcess = collectJavaFiles();
        if (filesToProcess.isEmpty()) {
            getLog().info("No Java files found.");
            return;
        }
        getLog().info("Scoring " + filesToProcess.size() + " Java file(s)");
        var allDiagnostics = lintFiles(filesToProcess, linter);
        var score = ScoreCalculator.calculate(allDiagnostics, filesToProcess.size());
        outputScore(score);
        if (baseline != null && score.overall() < baseline) {
            throw new MojoFailureException("Score " + score.overall() + " below baseline " + baseline);
        }
    }

    private List<Diagnostic> lintFiles(List<Path> files, JbctLinter linter) {
        var diagnostics = new ArrayList<Diagnostic>();
        for (var file : files) {
            SourceFile.sourceFile(file)
                      .flatMap(linter::lint)
                      .onSuccess(diagnostics::addAll)
                      .onFailure(cause -> getLog().error("Parse error in " + file + ": " + cause.message()));
        }
        return diagnostics;
    }

    private void outputScore(ScoreResult score) {
        getLog().info("╔═══════════════════════════════════════════════════╗");
        getLog().info(String.format("║     JBCT COMPLIANCE SCORE: %d/100            ║", score.overall()));
        getLog().info("╠═══════════════════════════════════════════════════╣");
        for (var category : ScoreCategory.values()) {
            var categoryScore = score.breakdown()
                                     .get(category);
            var percent = categoryScore.score();
            var bar = createProgressBar(percent);
            getLog().info(String.format("║  %-18s %s %3d%%    ║",
                                        category.name()
                                                .replace('_', ' '),
                                        bar,
                                        percent));
        }
        getLog().info("╚═══════════════════════════════════════════════════╝");
    }

    private String createProgressBar(int percent) {
        var filled = percent / 5;
        // 20 chars = 100%
        var empty = 20 - filled;
        return "█".repeat(filled) + "░".repeat(empty);
    }
}
